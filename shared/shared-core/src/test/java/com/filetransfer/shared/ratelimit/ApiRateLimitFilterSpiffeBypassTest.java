package com.filetransfer.shared.ratelimit;

import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R93: SPIFFE JWT-SVID bypass inside {@link ApiRateLimitFilter}.
 *
 * <p>Covers the regression the R89 perf run surfaced: platform-internal calls
 * were rate-limited as if they were external clients when the filter ran
 * before an auth filter could set {@code ROLE_INTERNAL}. The inline bypass
 * validates Bearer tokens with {@code spiffe://} subjects via
 * {@link SpiffeWorkloadClient} and exempts them, independent of filter order.
 *
 * <p>Security note: tokens that merely *look* like SPIFFE (forged sub claim
 * with no valid signature) must NOT be granted the bypass. The
 * {@code tokenWithSpiffeClaimButInvalidSignatureIsStillRateLimited} test
 * pins that guarantee.
 */
@ExtendWith(MockitoExtension.class)
class ApiRateLimitFilterSpiffeBypassTest {

    @Mock private SpiffeWorkloadClient spiffeClient;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private RateLimitProperties properties;
    private ApiRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setDefaultLimit(100);
        properties.setUserLimit(200);
        properties.setDefaultWindowSeconds(60);
        // No Redis — uses in-memory fallback, fine for unit tests.
        filter = new ApiRateLimitFilter(properties, null, spiffeClient);
    }

    @Test
    void validSpiffeTokenBypassesRateLimit() throws Exception {
        // Request carries a JWT-SVID that the SPIRE workload API validates.
        String svid = spiffeLookingToken("spiffe://filetransfer.io/sftp-service");
        when(request.getRequestURI()).thenReturn("/api/flows");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + svid);
        when(spiffeClient.isAvailable()).thenReturn(true);
        when(spiffeClient.getSelfSpiffeId()).thenReturn("spiffe://filetransfer.io/config-service");
        when(spiffeClient.validate(eq(svid), anyString())).thenReturn(true);

        filter.doFilter(request, response, chain);

        // Bypass → chain proceeds, no 429, no response status set.
        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void tokenWithSpiffeClaimButInvalidSignatureIsStillRateLimited() throws Exception {
        // Attacker forges payload with spiffe:// sub but no valid SPIRE signature.
        String forged = spiffeLookingToken("spiffe://attacker.io/x");
        when(request.getRequestURI()).thenReturn("/api/flows");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + forged);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");
        when(spiffeClient.isAvailable()).thenReturn(true);
        when(spiffeClient.getSelfSpiffeId()).thenReturn("spiffe://filetransfer.io/config-service");
        // validate returns false → bypass must not apply.
        when(spiffeClient.validate(any(), any())).thenReturn(false);

        filter.doFilter(request, response, chain);

        // Bypass NOT applied → IP-based bucket should have counted this
        // request. Chain still proceeds (one request is under the 100/min
        // default), but the downstream bucket tracked it.
        verify(chain, times(1)).doFilter(request, response);
        verify(spiffeClient).validate(eq(forged), eq("spiffe://filetransfer.io/config-service"));
    }

    @Test
    void nonSpiffeBearerTokenSkipsValidationEntirely() throws Exception {
        // A plain platform JWT (admin login) must not hit the SPIRE API —
        // that would be an unnecessary round-trip on every user request.
        String platformJwt = "eyJhbGciOiJIUzI1NiJ9." +
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "{\"sub\":\"admin@example.com\",\"role\":\"ADMIN\"}".getBytes())
                + ".signature";
        when(request.getRequestURI()).thenReturn("/api/flows");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + platformJwt);
        when(request.getRemoteAddr()).thenReturn("10.0.0.10");

        filter.doFilter(request, response, chain);

        // Cheap pre-check (looksLikeSpiffeToken) returned false; validate
        // was never called.
        verify(spiffeClient, never()).validate(any(), any());
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void noSpiffeClientMeansNoBypassButStillFunctional() throws Exception {
        // Services built without SPIRE wiring still work; SPIFFE path just
        // no-ops and falls through to normal IP/user rate limiting.
        ApiRateLimitFilter noSpiffe = new ApiRateLimitFilter(properties, null, null);
        when(request.getRequestURI()).thenReturn("/api/flows");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.11");

        noSpiffe.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void actuatorAndHealthStillShortCircuitBeforeSpiffeCheck() throws Exception {
        // The early /actuator + /health bypass must still fire — the SPIFFE
        // code path should never be reached for those URLs (saves a wasted
        // SPIRE round-trip on every Prometheus scrape).
        when(request.getRequestURI()).thenReturn("/actuator/prometheus");

        filter.doFilter(request, response, chain);

        verify(spiffeClient, never()).validate(any(), any());
        verify(chain, times(1)).doFilter(request, response);
    }

    /**
     * Build a well-formed JWT-looking string with the given SPIFFE subject in
     * the payload. Signature is NOT cryptographically valid — tests that
     * exercise the bypass path stub {@code SpiffeWorkloadClient.validate()}
     * to return true; tests that exercise the forgery-rejection path stub
     * it to return false.
     */
    private static String spiffeLookingToken(String spiffeSub) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + spiffeSub + "\"}").getBytes());
        return "eyJ" + header.substring(3) + "." + payload + ".signature";
    }
}
