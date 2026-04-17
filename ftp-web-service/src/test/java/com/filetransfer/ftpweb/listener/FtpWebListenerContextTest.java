package com.filetransfer.ftpweb.listener;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R91: per-request listener context for FTP_WEB.
 *
 * <p>Covers the three resolution cases that matter for correctness:
 * <ul>
 *   <li>X-Listener-Instance header wins when present.</li>
 *   <li>Falls back to the service-wide ftpweb.instance-id env var.</li>
 *   <li>Source IP honors X-Forwarded-For (DMZ / api-gateway) with fallback
 *       to the socket remote address.</li>
 * </ul>
 */
class FtpWebListenerContextTest {

    @Test
    void headerOverridesDefaultInstanceId() throws Exception {
        FtpWebListenerContext ctx = newContextWithDefault("ftpweb-default");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(FtpWebListenerContext.HEADER)).thenReturn("ftpweb-eu");
        when(req.getRemoteAddr()).thenReturn("10.0.0.5");

        ctx.initFromRequest(req);

        assertThat(ctx.getInstanceId()).isEqualTo("ftpweb-eu");
        assertThat(ctx.getSourceIp()).isEqualTo("10.0.0.5");
    }

    @Test
    void fallsBackToDefaultInstanceIdWhenHeaderMissing() throws Exception {
        FtpWebListenerContext ctx = newContextWithDefault("ftpweb-default");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(FtpWebListenerContext.HEADER)).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("10.0.0.6");

        ctx.initFromRequest(req);

        assertThat(ctx.getInstanceId()).isEqualTo("ftpweb-default");
    }

    @Test
    void blankHeaderFallsBackToDefault() throws Exception {
        FtpWebListenerContext ctx = newContextWithDefault("ftpweb-default");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(FtpWebListenerContext.HEADER)).thenReturn("   ");
        when(req.getRemoteAddr()).thenReturn("10.0.0.7");

        ctx.initFromRequest(req);

        assertThat(ctx.getInstanceId()).isEqualTo("ftpweb-default");
    }

    @Test
    void sourceIpPrefersXForwardedForFirstEntry() throws Exception {
        FtpWebListenerContext ctx = newContextWithDefault("x");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.1");
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");

        ctx.initFromRequest(req);

        // XFF's first (left-most) IP is the real client; proxy IPs follow.
        assertThat(ctx.getSourceIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void nullInstanceIdWhenNoDefaultAndNoHeader() throws Exception {
        FtpWebListenerContext ctx = newContextWithDefault(null);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(FtpWebListenerContext.HEADER)).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("10.0.0.8");

        ctx.initFromRequest(req);

        assertThat(ctx.getInstanceId()).isNull();
    }

    /** Instantiates the context and sets the @Value default reflectively. */
    private static FtpWebListenerContext newContextWithDefault(String defaultId) throws Exception {
        FtpWebListenerContext ctx = new FtpWebListenerContext();
        Field f = FtpWebListenerContext.class.getDeclaredField("defaultInstanceId");
        f.setAccessible(true);
        f.set(ctx, defaultId);
        return ctx;
    }
}
