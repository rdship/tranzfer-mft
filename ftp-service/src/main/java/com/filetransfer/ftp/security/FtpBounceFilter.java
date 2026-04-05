package com.filetransfer.ftp.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * Prevents FTP bounce attacks and controls active mode (PORT/EPRT commands).
 *
 * <p>When bounce prevention is enabled (default), any PORT or EPRT command
 * that specifies an IP different from the client's connection IP is rejected.
 * This prevents the classic FTP bounce attack where a client directs the
 * server to connect to a third-party host.
 *
 * <p>When active mode is disabled, PORT and EPRT commands are rejected
 * entirely, forcing clients to use passive mode (PASV/EPSV).
 *
 * <p>This is the same defense implemented by vsftpd's {@code port_enable}
 * and ProFTPD's {@code AllowForeignAddress off}.
 */
@Slf4j
@Component
public class FtpBounceFilter extends DefaultFtplet {

    @Value("${ftp.security.bounce-prevention:true}")
    private boolean bouncePreventionEnabled;

    @Value("${ftp.active.enabled:true}")
    private boolean activeModeEnabled;

    /**
     * Intercept PORT and EPRT commands.
     *
     * <ul>
     *   <li>If active mode is disabled, reject PORT/EPRT with 500.</li>
     *   <li>If bounce prevention is enabled, verify the target IP matches
     *       the client's connection IP.</li>
     * </ul>
     */
    @Override
    public FtpletResult beforeCommand(FtpSession session, FtpRequest request)
            throws FtpException {
        String cmd = request.getCommand().toUpperCase();

        if ("PORT".equals(cmd)) {
            return handlePort(session, request);
        }
        if ("EPRT".equals(cmd)) {
            return handleEprt(session, request);
        }

        return FtpletResult.DEFAULT;
    }

    /**
     * Handle the PORT command (IPv4 active mode).
     * Format: PORT h1,h2,h3,h4,p1,p2
     */
    private FtpletResult handlePort(FtpSession session, FtpRequest request)
            throws FtpException {
        if (!activeModeEnabled) {
            log.info("Active mode disabled: rejecting PORT command from {}",
                    extractClientIp(session));
            session.write(new DefaultFtpReply(500, "Active mode (PORT) is disabled. Use PASV."));
            return FtpletResult.SKIP;
        }

        if (!bouncePreventionEnabled) {
            return FtpletResult.DEFAULT;
        }

        String arg = request.getArgument();
        if (arg == null || arg.isBlank()) {
            return FtpletResult.DEFAULT;
        }

        // PORT h1,h2,h3,h4,p1,p2
        String[] parts = arg.split(",");
        if (parts.length < 4) {
            return FtpletResult.DEFAULT;
        }

        String portIp = parts[0].trim() + "." + parts[1].trim() + "." + parts[2].trim() + "." + parts[3].trim();
        String clientIp = extractClientIp(session);

        if (clientIp != null && !clientIp.equals(portIp)) {
            log.warn("FTP bounce attack blocked: client={} tried PORT to {}", clientIp, portIp);
            session.write(new DefaultFtpReply(500, "PORT address must match client IP."));
            return FtpletResult.SKIP;
        }

        return FtpletResult.DEFAULT;
    }

    /**
     * Handle the EPRT command (extended active mode, IPv4 and IPv6).
     * Format: EPRT |protocol|address|port|
     * Example: EPRT |1|192.168.1.10|5000|  (IPv4)
     *          EPRT |2|::1|5000|           (IPv6)
     */
    private FtpletResult handleEprt(FtpSession session, FtpRequest request)
            throws FtpException {
        if (!activeModeEnabled) {
            log.info("Active mode disabled: rejecting EPRT command from {}",
                    extractClientIp(session));
            session.write(new DefaultFtpReply(500, "Active mode (EPRT) is disabled. Use EPSV."));
            return FtpletResult.SKIP;
        }

        if (!bouncePreventionEnabled) {
            return FtpletResult.DEFAULT;
        }

        String arg = request.getArgument();
        if (arg == null || arg.isBlank()) {
            return FtpletResult.DEFAULT;
        }

        // EPRT |protocol|address|port|
        // The delimiter is the first character of the argument (RFC 2428)
        String delimiter = String.valueOf(arg.charAt(0));
        String[] parts = arg.split(java.util.regex.Pattern.quote(delimiter));
        // parts[0] is empty (before first delimiter), parts[1] is protocol,
        // parts[2] is address, parts[3] is port
        if (parts.length < 3) {
            return FtpletResult.DEFAULT;
        }

        String eprtIp = parts[2].trim();
        String clientIp = extractClientIp(session);

        if (clientIp != null && !clientIp.equals(eprtIp)) {
            log.warn("FTP bounce attack blocked: client={} tried EPRT to {}", clientIp, eprtIp);
            session.write(new DefaultFtpReply(500, "EPRT address must match client IP."));
            return FtpletResult.SKIP;
        }

        return FtpletResult.DEFAULT;
    }

    private String extractClientIp(FtpSession session) {
        if (session.getClientAddress() instanceof InetSocketAddress isa) {
            return isa.getAddress().getHostAddress();
        }
        return null;
    }
}
