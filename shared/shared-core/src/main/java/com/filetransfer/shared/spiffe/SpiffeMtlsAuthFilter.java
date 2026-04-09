package com.filetransfer.shared.spiffe;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

/**
 * Pre-Spring-Security servlet filter that extracts the SPIFFE ID from a TLS
 * client certificate and stores it in a request attribute for downstream filters.
 *
 * <p>Runs at {@code Ordered.HIGHEST_PRECEDENCE + 1} (registered by
 * {@link SpiffeAutoConfiguration}), before Spring Security's FilterChainProxy.
 * This filter does <em>not</em> set the Spring Security context directly — it stores
 * the discovered SPIFFE ID in {@link #PEER_SPIFFE_ID_ATTR} and lets
 * {@code PlatformJwtAuthFilter} (inside the Security chain) pick it up as Path 0.
 *
 * <p>Two extraction modes are supported:
 * <ol>
 *   <li><b>Direct Tomcat mTLS</b> — Tomcat populates
 *       {@code jakarta.servlet.request.X509Certificate} when the connector is configured
 *       with {@code clientAuth=want} or {@code clientAuth=need}.
 *   <li><b>Proxy TLS offload</b> — Nginx / HAProxy terminates TLS and forwards the
 *       PEM-encoded client certificate in the {@code X-SSL-Client-Cert} header
 *       (URL-encoded, standard Nginx {@code $ssl_client_escaped_cert} variable).
 * </ol>
 *
 * <p>Activated only when {@code spiffe.mtls-enabled=true}.
 */
@Slf4j
public class SpiffeMtlsAuthFilter extends OncePerRequestFilter {

    /**
     * Request attribute key where this filter stores the authenticated SPIFFE ID.
     * Consumed by {@code PlatformJwtAuthFilter} as authentication Path 0.
     */
    public static final String PEER_SPIFFE_ID_ATTR = "spiffe.mtls.peer-id";

    /** SAN type integer for URI GeneralName (RFC 5280 §4.2.1.6). */
    private static final int SAN_TYPE_URI = 6;

    private static final String SPIFFE_PREFIX = "spiffe://";

    /** Nginx TLS offload header — URL-encoded PEM certificate. */
    private static final String CERT_HEADER = "X-SSL-Client-Cert";

    /** Tomcat direct mTLS attribute — array of {@link X509Certificate}. */
    private static final String CERT_ATTR = "jakarta.servlet.request.X509Certificate";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String spiffeId = extractSpiffeId(request);
        if (spiffeId != null) {
            request.setAttribute(PEER_SPIFFE_ID_ATTR, spiffeId);
            log.debug("[SPIFFE] mTLS peer cert extracted: {}", spiffeId);
        }
        chain.doFilter(request, response);
    }

    private String extractSpiffeId(HttpServletRequest request) {
        // Path A: direct Tomcat mTLS — cert array in servlet attribute
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute(CERT_ATTR);
        if (certs != null && certs.length > 0) {
            return spiffeIdFromCert(certs[0]);
        }

        // Path B: Nginx/HAProxy TLS offload — cert forwarded as URL-encoded PEM header
        String certHeader = request.getHeader(CERT_HEADER);
        if (certHeader != null && !certHeader.isBlank()) {
            return spiffeIdFromPemHeader(certHeader);
        }

        return null;
    }

    /** Extract the SPIFFE URI SAN from a parsed X.509 certificate. */
    private String spiffeIdFromCert(X509Certificate cert) {
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) return null;
            for (List<?> san : sans) {
                if (san.size() >= 2 && SAN_TYPE_URI == (int) san.get(0)) {
                    String uri = (String) san.get(1);
                    if (uri != null && uri.startsWith(SPIFFE_PREFIX)) {
                        return uri;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SPIFFE] Could not parse SAN from client cert: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Parse a URL-encoded PEM certificate from the {@code X-SSL-Client-Cert} header,
     * then extract the SPIFFE URI SAN from the parsed certificate.
     */
    private String spiffeIdFromPemHeader(String urlEncodedPem) {
        try {
            String pem = URLDecoder.decode(urlEncodedPem, StandardCharsets.UTF_8);
            // Strip PEM headers and whitespace → raw Base64 DER
            byte[] der = java.util.Base64.getDecoder().decode(
                    pem.replace("-----BEGIN CERTIFICATE-----", "")
                       .replace("-----END CERTIFICATE-----", "")
                       .replaceAll("\\s+", ""));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(der));
            return spiffeIdFromCert(cert);
        } catch (Exception e) {
            log.debug("[SPIFFE] Could not parse cert from X-SSL-Client-Cert header: {}", e.getMessage());
            return null;
        }
    }
}
