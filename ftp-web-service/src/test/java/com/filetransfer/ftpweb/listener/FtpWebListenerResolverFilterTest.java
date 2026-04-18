package com.filetransfer.ftpweb.listener;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R103: pins the {@code shouldNotFilter} contract introduced to fix the
 * tester-reported 403 on {@code /actuator/health/liveness}.
 *
 * <p>The root cause was this filter touching a {@code @RequestScope} bean
 * ({@link FtpWebListenerContext}) on actuator paths where Spring's
 * request-scope wiring isn't set the same way as on DispatcherServlet
 * paths. The fix — skip actuator + error + swagger paths entirely — is
 * purely additive and load-bearing for Docker healthchecks, so pinning
 * the skip list here means a future refactor can't regress it silently.
 */
class FtpWebListenerResolverFilterTest {

    @Test
    void skipsActuatorHealthLiveness() throws Exception {
        assertShouldNotFilter("/actuator/health/liveness", true);
    }

    @Test
    void skipsActuatorHealthReadiness() throws Exception {
        assertShouldNotFilter("/actuator/health/readiness", true);
    }

    @Test
    void skipsActuatorPrometheus() throws Exception {
        assertShouldNotFilter("/actuator/prometheus", true);
    }

    @Test
    void skipsActuatorMetrics() throws Exception {
        assertShouldNotFilter("/actuator/metrics/jvm.memory.used", true);
    }

    @Test
    void skipsActuatorThreadAndHeapDumps() throws Exception {
        assertShouldNotFilter("/actuator/threaddump", true);
        assertShouldNotFilter("/actuator/heapdump", true);
    }

    @Test
    void skipsSpringBootErrorDispatch() throws Exception {
        assertShouldNotFilter("/error", true);
    }

    @Test
    void skipsSwaggerAndApiDocs() throws Exception {
        assertShouldNotFilter("/v3/api-docs", true);
        assertShouldNotFilter("/v3/api-docs/swagger-config", true);
        assertShouldNotFilter("/swagger-ui/index.html", true);
    }

    @Test
    void doesNotSkipApiPaths() throws Exception {
        assertShouldNotFilter("/api/files/list", false);
        assertShouldNotFilter("/api/auth/login", false);
        assertShouldNotFilter("/", false);
    }

    @Test
    void doFilterOnActuatorPathNeverTouchesListenerContext() throws Exception {
        // The whole point of shouldNotFilter: actuator requests never
        // invoke initFromRequest, so the @RequestScope proxy is never
        // resolved on paths where Spring's request-scope wiring differs.
        FtpWebListenerContext ctx = mock(FtpWebListenerContext.class);
        FtpWebListenerResolverFilter filter = new FtpWebListenerResolverFilter(ctx);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/actuator/health/liveness");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(ctx, never()).initFromRequest(req);
        verify(chain).doFilter(req, resp);
    }

    private static void assertShouldNotFilter(String path, boolean expected) throws Exception {
        FtpWebListenerResolverFilter filter = new FtpWebListenerResolverFilter(
                mock(FtpWebListenerContext.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(path);

        Method m = org.springframework.web.filter.OncePerRequestFilter.class
                .getDeclaredMethod("shouldNotFilter", HttpServletRequest.class);
        m.setAccessible(true);
        boolean actual = (boolean) m.invoke(filter, req);
        assertThat(actual)
                .as("shouldNotFilter(%s)", path)
                .isEqualTo(expected);
    }
}
