package com.filetransfer.ftpweb.listener;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Per-request listener binding for FTP_WEB — parallels
 * {@code ListenerContext} (SFTP) and {@code FtpListenerContext} (FTP) but
 * in HTTP-request scope rather than session-attribute/ThreadLocal.
 *
 * <p>Today ftp-web-service is a single-Tomcat Spring Boot app, so the
 * effective listener is the one identified by the service-wide
 * {@code ftpweb.instance-id} environment variable. This class lets
 * downstream code (credential resolution, audit logging, routing-engine
 * stamping) consistently ask "which ServerInstance is serving this
 * request?" without each caller re-reading env vars.
 *
 * <p>Future-proofing: when multi-listener support lands (Undertow with
 * multiple connectors, or api-gateway path-based routing), an upstream
 * filter can override the instance id via the {@code X-Listener-Instance}
 * request header without touching downstream code.
 */
@Component
@RequestScope
@Getter
public class FtpWebListenerContext {

    /** HTTP header an upstream router (DMZ/api-gateway) uses to disambiguate listener. */
    public static final String HEADER = "X-Listener-Instance";

    @Value("${ftpweb.instance-id:#{null}}")
    private String defaultInstanceId;

    private String instanceId;
    private String sourceIp;

    /**
     * Seed the context from the incoming request. Called by
     * {@link FtpWebListenerResolverFilter} on every request before
     * authentication runs so downstream auth/FS/audit see a stable id.
     */
    public void initFromRequest(HttpServletRequest request) {
        String headerId = request.getHeader(HEADER);
        this.instanceId = (headerId != null && !headerId.isBlank()) ? headerId : defaultInstanceId;
        this.sourceIp = resolveClientIp(request);
    }

    /**
     * Honors X-Forwarded-For (DMZ / api-gateway adds this), falls back to
     * socket remote address. Returns first comma-separated IP from XFF to
     * avoid attributing proxied requests to the proxy itself.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
