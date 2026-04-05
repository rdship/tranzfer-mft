package com.filetransfer.ftp.server;

import com.filetransfer.ftp.keystore.KeystoreManagerClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FTPS (FTP over TLS) configuration with production hardening.
 *
 * <p>Supports:
 * <ul>
 *   <li>Explicit FTPS (AUTH TLS on port 21)</li>
 *   <li>Implicit FTPS (direct TLS on port 990)</li>
 *   <li>Configurable TLS protocol versions (disable TLSv1.0/1.1)</li>
 *   <li>Cipher suite selection</li>
 *   <li>Mandatory FTPS (reject plain FTP connections)</li>
 *   <li>Mutual TLS (client certificate authentication: none/want/need)</li>
 *   <li>PROT P enforcement (require encrypted data channel)</li>
 *   <li>Configurable keystore type (JKS, PKCS12)</li>
 *   <li>Certificate info in health endpoint</li>
 * </ul>
 */
@Component
@Slf4j
public class FtpsConfig {

    @Autowired
    private KeystoreManagerClient keystoreManagerClient;

    @Value("${ftp.ftps.enabled:false}")
    private boolean enabled;

    @Value("${ftp.ftps.keystore-path:./ftp-keystore.jks}")
    private String keystorePath;

    @Value("${ftp.ftps.keystore-password:changeit}")
    private String keystorePassword;

    /** Keystore type: JKS or PKCS12 (default: JKS). */
    @Value("${ftp.ftps.keystore-type:JKS}")
    private String keystoreType;

    @Value("${ftp.ftps.protocol:TLSv1.2}")
    private String protocol;

    /**
     * Client certificate authentication mode: none, want, or need.
     * <ul>
     *   <li>{@code none} -- no client cert requested (default)</li>
     *   <li>{@code want} -- client cert requested but not required</li>
     *   <li>{@code need} -- client cert required (mutual TLS)</li>
     * </ul>
     */
    @Value("${ftp.ftps.client-auth:none}")
    private String clientAuth;

    /** Use implicit FTPS (direct TLS on port 990) instead of explicit (AUTH TLS on port 21). */
    @Value("${ftp.ftps.implicit:false}")
    private boolean implicit;

    /** Require FTPS -- reject any plain (unencrypted) FTP connections. */
    @Value("${ftp.ftps.require:false}")
    private boolean requireTls;

    /** Require encrypted data channel (PROT P). When true, PROT C is rejected. */
    @Value("${ftp.ftps.require-data-tls:false}")
    private boolean requireDataTls;

    /** Comma-separated list of enabled cipher suites (empty = JVM defaults). */
    @Value("${ftp.ftps.cipher-suites:}")
    private String cipherSuitesRaw;

    /** Comma-separated list of enabled TLS protocol versions (e.g., TLSv1.2,TLSv1.3). */
    @Value("${ftp.ftps.enabled-protocols:}")
    private String enabledProtocolsRaw;

    /** Truststore path for client certificate validation (mutual TLS). */
    @Value("${ftp.ftps.truststore-path:}")
    private String truststorePath;

    /** Truststore password. */
    @Value("${ftp.ftps.truststore-password:changeit}")
    private String truststorePassword;

    public boolean isEnabled() { return enabled; }

    /** Whether implicit FTPS mode is configured. */
    public boolean isImplicit() { return implicit; }

    /** Whether plain FTP connections should be rejected (FTPS required). */
    public boolean isRequireTls() { return requireTls; }

    /** Whether encrypted data channel (PROT P) is required. */
    public boolean isRequireDataTls() { return requireDataTls; }

    /**
     * Return the client auth mode as an uppercase string for Apache FtpServer.
     *
     * @return "NONE", "WANT", or "NEED"
     */
    public String getClientAuthMode() {
        if (clientAuth == null || clientAuth.isBlank()) {
            return "NONE";
        }
        return clientAuth.trim().toUpperCase();
    }

    /**
     * Return the configured TLS protocol version string.
     *
     * @return the TLS protocol (e.g., "TLSv1.2")
     */
    public String getProtocol() { return protocol; }

    /** Return the keystore type (JKS or PKCS12). */
    public String getKeystoreType() { return keystoreType; }

    /**
     * Return the set of enabled cipher suites, or an empty set for JVM defaults.
     *
     * @return unmodifiable set of cipher suite names
     */
    public Set<String> getEnabledCipherSuites() {
        return parseCommaSeparated(cipherSuitesRaw);
    }

    /**
     * Return the set of enabled TLS protocol versions, or an empty set for default.
     *
     * @return unmodifiable set of protocol version strings
     */
    public Set<String> getEnabledProtocols() {
        return parseCommaSeparated(enabledProtocolsRaw);
    }

    /**
     * Build SSL configuration for Apache FTP Server.
     * Returns null if FTPS is disabled or keystore not found.
     *
     * @return the SSL configuration, or {@code null} if FTPS is disabled
     */
    public SslConfiguration buildSslConfig() {
        if (!enabled) {
            log.info("FTPS disabled. To enable: set ftp.ftps.enabled=true and provide keystore");
            return null;
        }

        File ksFile = new File(keystorePath);
        if (!ksFile.exists()) {
            // Try to get TLS certificate from Keystore Manager first
            if (keystoreManagerClient.isEnabled()) {
                String alias = keystoreManagerClient.getDefaultAlias();
                log.info("FTPS keystore not found locally — checking Keystore Manager for '{}'", alias);
                String certMaterial = keystoreManagerClient.getTlsCertificate(alias);
                if (certMaterial == null) {
                    certMaterial = keystoreManagerClient.generateAndStoreTlsCert(alias, "CN=TranzFer FTP Server", 365);
                }
                if (certMaterial != null) {
                    log.info("TLS certificate retrieved from Keystore Manager — generating local keystore");
                } else {
                    log.warn("Keystore Manager unavailable — falling back to local self-signed generation");
                }
            }
            // Fall back to local self-signed if Keystore Manager didn't provide one
            if (!ksFile.exists()) {
                log.warn("Generating self-signed FTPS keystore at {}", keystorePath);
                generateSelfSignedKeystore(ksFile);
            }
        }

        try {
            SslConfigurationFactory sslFactory = new SslConfigurationFactory();
            sslFactory.setKeystoreFile(ksFile);
            sslFactory.setKeystorePassword(keystorePassword);
            sslFactory.setKeystoreType(keystoreType);
            sslFactory.setSslProtocol(protocol);

            // Client auth: NONE, WANT, or NEED
            String authMode = getClientAuthMode();
            sslFactory.setClientAuthentication(authMode);

            // Truststore for client certificate validation (WANT or NEED)
            boolean needsTruststore = "WANT".equals(authMode) || "NEED".equals(authMode);
            if (needsTruststore && truststorePath != null && !truststorePath.isBlank()) {
                File tsFile = new File(truststorePath);
                if (tsFile.exists()) {
                    sslFactory.setTruststoreFile(tsFile);
                    sslFactory.setTruststorePassword(truststorePassword);
                    log.info("FTPS truststore configured: {}", truststorePath);
                } else {
                    log.warn("FTPS truststore not found: {} -- client cert validation may fail", truststorePath);
                }
            }

            // Cipher suite restriction
            Set<String> ciphers = getEnabledCipherSuites();
            if (!ciphers.isEmpty()) {
                sslFactory.setEnabledCipherSuites(ciphers.toArray(new String[0]));
                log.info("FTPS cipher suites restricted to: {}", ciphers);
            }

            // TLS protocol version restriction
            Set<String> protocols = getEnabledProtocols();
            if (!protocols.isEmpty()) {
                sslFactory.setSslProtocol(protocols.toArray(new String[0]));
                log.info("FTPS enabled protocols: {}", protocols);
            }

            log.info("FTPS configured: protocol={}, implicit={}, requireTls={}, requireDataTls={}, " +
                            "clientAuth={}, keystoreType={}, keystore={}",
                    protocol, implicit, requireTls, requireDataTls, authMode, keystoreType, keystorePath);
            return sslFactory.createSslConfiguration();
        } catch (Exception e) {
            log.error("FTPS configuration failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Return certificate information from the keystore for the health endpoint.
     * Includes subject, issuer, expiry date, serial number, and algorithm.
     *
     * @return map of certificate details, or an empty map if unavailable
     */
    public Map<String, Object> getCertificateInfo() {
        if (!enabled) {
            return Map.of();
        }

        File ksFile = new File(keystorePath);
        if (!ksFile.exists()) {
            return Map.of("error", "keystore not found");
        }

        try (FileInputStream fis = new FileInputStream(ksFile)) {
            KeyStore ks = KeyStore.getInstance(keystoreType);
            ks.load(fis, keystorePassword.toCharArray());

            Map<String, Object> certInfo = new LinkedHashMap<>();
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = ks.getCertificate(alias);
                if (cert instanceof X509Certificate x509) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("subject", x509.getSubjectX500Principal().getName());
                    entry.put("issuer", x509.getIssuerX500Principal().getName());
                    entry.put("serialNumber", x509.getSerialNumber().toString(16));
                    entry.put("notBefore", x509.getNotBefore().toInstant().toString());
                    entry.put("notAfter", x509.getNotAfter().toInstant().toString());
                    entry.put("sigAlgorithm", x509.getSigAlgName());

                    // Check if expired or expiring soon (within 30 days)
                    Instant expiry = x509.getNotAfter().toInstant();
                    Instant now = Instant.now();
                    boolean expired = now.isAfter(expiry);
                    boolean expiringSoon = !expired && now.isAfter(expiry.minusSeconds(30L * 24 * 60 * 60));
                    entry.put("expired", expired);
                    entry.put("expiringSoon", expiringSoon);

                    certInfo.put(alias, entry);
                }
            }
            return certInfo;
        } catch (Exception e) {
            log.debug("Could not read certificate info: {}", e.getMessage());
            return Map.of("error", "could not read keystore: " + e.getMessage());
        }
    }

    private void generateSelfSignedKeystore(File ksFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("keytool",
                    "-genkeypair", "-alias", "ftps",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-keystore", ksFile.getAbsolutePath(),
                    "-storetype", keystoreType,
                    "-storepass", keystorePassword,
                    "-dname", "CN=TranzFer FTPS,O=TranzFer MFT,C=US",
                    "-keypass", keystorePassword);
            pb.inheritIO();
            Process p = pb.start();
            p.waitFor();
            log.info("Generated self-signed FTPS keystore at {} (type={})", ksFile.getAbsolutePath(), keystoreType);
        } catch (Exception e) {
            log.error("Could not generate keystore: {}", e.getMessage());
        }
    }

    private Set<String> parseCommaSeparated(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
