package com.filetransfer.dmz.inspection;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * FTP Command Filter — inspects in-flight FTP commands to block dangerous operations.
 *
 * Prevents:
 * <ul>
 *   <li>FTP bounce attacks via PORT/EPRT with forged IP addresses</li>
 *   <li>Server-side execution via SITE command</li>
 *   <li>Path traversal via {@code ../} in arguments</li>
 *   <li>Null byte injection in arguments</li>
 *   <li>Non-standard or blacklisted commands</li>
 *   <li>Oversized command lines (DoS prevention)</li>
 * </ul>
 *
 * Thread-safe: immutable configuration, no shared mutable state.
 *
 * @see DeepPacketInspector
 */
@Slf4j
public class FtpCommandFilter {

    // ── Nested types ──────────────────────────────────────────────────

    /**
     * FTP filter configuration.
     */
    public record FtpFilterConfig(
        boolean enabled,
        boolean blockPortCommand,
        boolean blockSiteCommand,
        boolean blockPathTraversal,
        boolean requirePassiveMode,
        List<String> allowedCommands,
        List<String> blockedCommands,
        int maxCommandLength,
        boolean logAllCommands
    ) {}

    /**
     * Result of an FTP command check.
     */
    public record FtpCommandResult(
        boolean allowed,
        String command,
        String argument,
        String reason
    ) {}

    // ── Standard FTP command whitelist ─────────────────────────────────

    private static final Set<String> DEFAULT_ALLOWED_COMMANDS = Set.of(
        "USER", "PASS", "ACCT", "CWD", "CDUP", "QUIT",
        "PASV", "EPSV", "TYPE", "STRU", "MODE",
        "RETR", "STOR", "APPE", "LIST", "NLST",
        "STAT", "HELP", "NOOP", "SYST", "FEAT",
        "PWD", "MKD", "RMD", "DELE", "RNFR", "RNTO",
        "SIZE", "MDTM", "REST", "ABOR",
        "AUTH", "PBSZ", "PROT"
    );

    // ── Path traversal patterns ───────────────────────────────────────

    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
        "(\\.\\./|\\.\\.\\\\|%2[eE]%2[eE]|%2[eE]\\.|\\." +
        "%2[eE]|%252[eE]%252[eE])"
    );

    // ── PORT argument format: h1,h2,h3,h4,p1,p2 ──────────────────────

    private static final Pattern PORT_PATTERN = Pattern.compile(
        "^(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})$"
    );

    // ── EPRT argument format: |af|addr|port| ──────────────────────────

    private static final Pattern EPRT_PATTERN = Pattern.compile(
        "^\\|(\\d)\\|([^|]+)\\|(\\d+)\\|$"
    );

    // ── State ─────────────────────────────────────────────────────────

    private final FtpFilterConfig config;
    private final Set<String> effectiveAllowed;
    private final Set<String> effectiveBlocked;

    // ── Constructor ───────────────────────────────────────────────────

    /**
     * Create a new FTP command filter.
     *
     * @param config filter configuration
     */
    public FtpCommandFilter(FtpFilterConfig config) {
        this.config = config;

        // Build effective allowed set
        if (config.allowedCommands() != null && !config.allowedCommands().isEmpty()) {
            this.effectiveAllowed = Set.copyOf(config.allowedCommands().stream()
                .map(String::toUpperCase)
                .toList());
        } else {
            this.effectiveAllowed = DEFAULT_ALLOWED_COMMANDS;
        }

        // Build effective blocked set
        if (config.blockedCommands() != null && !config.blockedCommands().isEmpty()) {
            this.effectiveBlocked = Set.copyOf(config.blockedCommands().stream()
                .map(String::toUpperCase)
                .toList());
        } else {
            this.effectiveBlocked = Set.of();
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Check an FTP command line (without client IP).
     * PORT and EPRT commands will be rejected because bounce attack prevention
     * cannot be performed without a client IP.
     *
     * @param commandLine raw FTP command line (e.g., "RETR /path/to/file.txt")
     * @return check result
     * @deprecated Use {@link #checkCommand(String, String)} with a client IP to enable
     *             full bounce attack prevention. This overload rejects PORT/EPRT commands.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public FtpCommandResult checkCommand(String commandLine) {
        log.warn("FTP checkCommand called without client IP — PORT/EPRT will be rejected");
        return checkCommand(commandLine, null);
    }

    /**
     * Check an FTP command line with client IP for bounce attack prevention.
     *
     * @param commandLine raw FTP command line
     * @param clientIp    the client's IP address (for PORT/EPRT validation)
     * @return check result
     */
    public FtpCommandResult checkCommand(String commandLine, String clientIp) {
        if (!config.enabled()) {
            return parseAndReturn(commandLine, true, "filter_disabled");
        }

        if (commandLine == null || commandLine.isBlank()) {
            return new FtpCommandResult(false, "", "", "empty_command");
        }

        // ── 1. Max length check ──
        if (commandLine.length() > config.maxCommandLength()) {
            log.warn("FTP filter: command exceeds max length {}: {} bytes",
                config.maxCommandLength(), commandLine.length());
            return parseAndReturn(commandLine, false, "command_too_long");
        }

        // ── Parse command and argument ──
        String trimmed = commandLine.trim();
        // Strip trailing \r\n if present
        if (trimmed.endsWith("\r\n")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        } else if (trimmed.endsWith("\r") || trimmed.endsWith("\n")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        int spaceIndex = trimmed.indexOf(' ');
        String command = spaceIndex > 0 ? trimmed.substring(0, spaceIndex).toUpperCase() : trimmed.toUpperCase();
        String argument = spaceIndex > 0 ? trimmed.substring(spaceIndex + 1) : "";

        if (config.logAllCommands()) {
            log.info("FTP command: {} {}", command, "PASS".equals(command) ? "****" : argument);
        }

        // ── 2. Command whitelist/blacklist ──
        if (effectiveBlocked.contains(command)) {
            log.warn("FTP filter: blocked command: {}", command);
            return new FtpCommandResult(false, command, argument, "command_blacklisted");
        }

        if (!effectiveAllowed.contains(command)) {
            log.warn("FTP filter: command not in allowed list: {}", command);
            return new FtpCommandResult(false, command, argument, "command_not_allowed");
        }

        // ── 3. PORT/EPRT bounce attack prevention ──
        if ("PORT".equals(command)) {
            if (config.blockPortCommand() || config.requirePassiveMode()) {
                log.warn("FTP filter: PORT command blocked (passive mode required)");
                return new FtpCommandResult(false, command, argument,
                    config.requirePassiveMode() ? "passive_mode_required" : "port_command_blocked");
            }
            // Reject if client IP unavailable — bounce check cannot be performed safely
            if (clientIp == null) {
                log.warn("FTP PORT command rejected — client IP unavailable for bounce attack check");
                return new FtpCommandResult(false, command, argument, "port_bounce_no_client_ip");
            }
            FtpCommandResult bounceCheck = checkPortBounce(command, argument, clientIp);
            if (!bounceCheck.allowed()) return bounceCheck;
        }

        if ("EPRT".equals(command)) {
            if (config.blockPortCommand() || config.requirePassiveMode()) {
                log.warn("FTP filter: EPRT command blocked (passive mode required)");
                return new FtpCommandResult(false, command, argument,
                    config.requirePassiveMode() ? "passive_mode_required" : "eprt_command_blocked");
            }
            // Reject if client IP unavailable — bounce check cannot be performed safely
            if (clientIp == null) {
                log.warn("FTP EPRT command rejected — client IP unavailable for bounce attack check");
                return new FtpCommandResult(false, command, argument, "eprt_bounce_no_client_ip");
            }
            FtpCommandResult bounceCheck = checkEprtBounce(command, argument, clientIp);
            if (!bounceCheck.allowed()) return bounceCheck;
        }

        // ── 4. SITE command block ──
        if ("SITE".equals(command) && config.blockSiteCommand()) {
            log.warn("FTP filter: SITE command blocked");
            return new FtpCommandResult(false, command, argument, "site_command_blocked");
        }

        // ── 5. Path traversal in argument ──
        if (config.blockPathTraversal() && !argument.isEmpty()) {
            if (PATH_TRAVERSAL_PATTERN.matcher(argument).find()) {
                log.warn("FTP filter: path traversal detected in argument: {}", argument);
                return new FtpCommandResult(false, command, argument, "path_traversal_detected");
            }
        }

        // ── 6. Null byte injection ──
        if (!argument.isEmpty() && argument.indexOf('\0') >= 0) {
            log.warn("FTP filter: null byte injection detected in argument");
            return new FtpCommandResult(false, command, argument, "null_byte_injection");
        }

        return new FtpCommandResult(true, command, argument, "allowed");
    }

    /**
     * Check an FTP command extracted from a ByteBuf (without client IP).
     * PORT and EPRT commands will be rejected because bounce attack prevention
     * cannot be performed without a client IP.
     *
     * @param data the ByteBuf containing the FTP command
     * @return check result
     * @deprecated Use {@link #checkCommand(ByteBuf, String)} with a client IP to enable
     *             full bounce attack prevention. This overload rejects PORT/EPRT commands.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public FtpCommandResult checkCommand(ByteBuf data) {
        log.warn("FTP checkCommand(ByteBuf) called without client IP — PORT/EPRT will be rejected");
        return checkCommand(data, null);
    }

    /**
     * Check an FTP command from a ByteBuf with client IP for bounce attack prevention.
     *
     * @param data     the ByteBuf containing the FTP command
     * @param clientIp the client's IP address
     * @return check result
     */
    public FtpCommandResult checkCommand(ByteBuf data, String clientIp) {
        int readerIndex = data.readerIndex();
        int readable = data.readableBytes();

        if (readable == 0) {
            return new FtpCommandResult(false, "", "", "empty_data");
        }

        // Read up to maxCommandLength or available bytes, whichever is smaller
        int readLen = Math.min(readable, config.maxCommandLength() + 4); // extra for \r\n margin
        byte[] bytes = new byte[readLen];
        data.getBytes(readerIndex, bytes);

        String text = new String(bytes, StandardCharsets.US_ASCII);

        // Extract first line
        int lineEnd = text.indexOf('\n');
        String commandLine = lineEnd >= 0 ? text.substring(0, lineEnd) : text;
        // Strip trailing \r
        if (commandLine.endsWith("\r")) {
            commandLine = commandLine.substring(0, commandLine.length() - 1);
        }

        return checkCommand(commandLine, clientIp);
    }

    // ── Private Helpers ───────────────────────────────────────────────

    /**
     * Validate PORT argument IP matches the client IP.
     * PORT format: h1,h2,h3,h4,p1,p2
     */
    private FtpCommandResult checkPortBounce(String command, String argument, String clientIp) {
        var matcher = PORT_PATTERN.matcher(argument.trim());
        if (!matcher.matches()) {
            log.warn("FTP filter: invalid PORT argument format: {}", argument);
            return new FtpCommandResult(false, command, argument, "invalid_port_format");
        }

        String portIp = matcher.group(1) + "." + matcher.group(2) + "."
                       + matcher.group(3) + "." + matcher.group(4);

        if (!portIp.equals(clientIp)) {
            log.warn("FTP filter: PORT bounce attack — command IP {} != client IP {}", portIp, clientIp);
            return new FtpCommandResult(false, command, argument,
                "bounce_attack_detected: PORT IP " + portIp + " != client " + clientIp);
        }

        return new FtpCommandResult(true, command, argument, "port_ip_verified");
    }

    /**
     * Validate EPRT argument IP matches the client IP.
     * EPRT format: |af|addr|port|
     */
    private FtpCommandResult checkEprtBounce(String command, String argument, String clientIp) {
        var matcher = EPRT_PATTERN.matcher(argument.trim());
        if (!matcher.matches()) {
            log.warn("FTP filter: invalid EPRT argument format: {}", argument);
            return new FtpCommandResult(false, command, argument, "invalid_eprt_format");
        }

        String eprtAddr = matcher.group(2);

        if (!eprtAddr.equals(clientIp)) {
            log.warn("FTP filter: EPRT bounce attack — command addr {} != client IP {}", eprtAddr, clientIp);
            return new FtpCommandResult(false, command, argument,
                "bounce_attack_detected: EPRT addr " + eprtAddr + " != client " + clientIp);
        }

        return new FtpCommandResult(true, command, argument, "eprt_addr_verified");
    }

    /**
     * Parse command line and return a result with the parsed command/argument.
     */
    private FtpCommandResult parseAndReturn(String commandLine, boolean allowed, String reason) {
        if (commandLine == null || commandLine.isBlank()) {
            return new FtpCommandResult(allowed, "", "", reason);
        }
        String trimmed = commandLine.trim();
        int spaceIndex = trimmed.indexOf(' ');
        String command = spaceIndex > 0 ? trimmed.substring(0, spaceIndex).toUpperCase() : trimmed.toUpperCase();
        String argument = spaceIndex > 0 ? trimmed.substring(spaceIndex + 1) : "";
        return new FtpCommandResult(allowed, command, argument, reason);
    }
}
