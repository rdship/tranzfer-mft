package com.filetransfer.sftp.session;

import com.filetransfer.sftp.audit.AuditEventLogger;
import com.filetransfer.sftp.throttle.BandwidthThrottleManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.session.ServerSession;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to SSH session lifecycle events to enforce connection management
 * policies and emit audit log entries for connect/disconnect events.
 *
 * <p>Registered as a listener on the {@code SshServer} in
 * {@code SftpServerConfig}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpSessionListener implements SessionListener {

    private final ConnectionManager connectionManager;
    private final AuditEventLogger auditEventLogger;
    private final BandwidthThrottleManager bandwidthThrottleManager;

    /** Tracks session creation time for duration calculation. */
    private final ConcurrentHashMap<String, Instant> sessionStartTimes = new ConcurrentHashMap<>();

    @Override
    public void sessionCreated(Session session) {
        String sessionId = session.toString();
        sessionStartTimes.put(sessionId, Instant.now());
        log.debug("SSH session created: {}", sessionId);
    }

    @Override
    public void sessionEvent(Session session, Event event) {
        if (event == Event.Authenticated && session instanceof ServerSession serverSession) {
            String sessionId = session.toString();
            String username = serverSession.getUsername();
            String ip = serverSession.getClientAddress() != null
                    ? serverSession.getClientAddress().toString() : "unknown";

            boolean registered = connectionManager.tryRegisterSession(sessionId, username, ip);
            if (!registered) {
                auditEventLogger.logConnectionRejected(username, ip, "connection limit exceeded");
                log.warn("Closing session: connection limit exceeded for user={} ip={}", username, ip);
                try {
                    serverSession.disconnect(11, "Connection limit exceeded");
                } catch (Exception e) {
                    log.debug("Error disconnecting session: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void sessionClosed(Session session) {
        String sessionId = session.toString();
        Instant startTime = sessionStartTimes.remove(sessionId);
        long durationMs = startTime != null ? Instant.now().toEpochMilli() - startTime.toEpochMilli() : 0;

        connectionManager.unregisterSession(sessionId);

        if (session instanceof ServerSession serverSession) {
            String username = serverSession.getUsername();
            String ip = serverSession.getClientAddress() != null
                    ? serverSession.getClientAddress().toString() : "unknown";
            if (username != null) {
                auditEventLogger.logDisconnect(username, ip, durationMs);
                bandwidthThrottleManager.unregisterUser(username);
            }
        }

        log.debug("SSH session closed: {} (duration={}ms)", sessionId, durationMs);
    }
}
