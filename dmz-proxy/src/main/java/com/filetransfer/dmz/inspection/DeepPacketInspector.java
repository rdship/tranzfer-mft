package com.filetransfer.dmz.inspection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Deep Packet Inspection Engine — validates protocol-level content beyond simple detection.
 *
 * Performs security inspections on:
 * <ul>
 *   <li><b>TLS ClientHello:</b> version enforcement, weak cipher blocking, SNI extraction</li>
 *   <li><b>SSH:</b> version 1 blocking, attack tool fingerprinting</li>
 *   <li><b>HTTP:</b> SQL injection, command injection, path traversal, header validation</li>
 *   <li><b>FTP:</b> delegates to {@link FtpCommandFilter}</li>
 * </ul>
 *
 * Zero-copy: reads ByteBuf via {@code getByte()}/{@code getBytes()} without consuming the buffer.
 * Thread-safe: stateless per inspection — all mutable state is local to each call.
 *
 * @see FtpCommandFilter
 */
@Slf4j
public class DeepPacketInspector {

    // ── Nested types ──────────────────────────────────────────────────

    /**
     * Inspection configuration. All flags default to safe/conservative values.
     */
    public record InspectionConfig(
        boolean enabled,
        boolean enforceMinTls,
        String minTlsVersion,
        boolean blockWeakCiphers,
        boolean blockSshV1,
        List<String> allowedSshKex,
        boolean validateHttpHeaders,
        int maxHttpHeaderSize,
        boolean blockSqlInjection,
        boolean blockCommandInjection,
        boolean blockPathTraversal
    ) {}

    /**
     * Result of a single inspection.
     */
    public record InspectionResult(
        boolean allowed,
        String finding,
        Severity severity,
        String detail
    ) {}

    /**
     * Severity levels for inspection findings.
     */
    public enum Severity {
        INFO,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    // ── TLS version ordinals (for comparison) ─────────────────────────

    private static final int TLS_ORD_SSL3   = 0;
    private static final int TLS_ORD_TLS10  = 1;
    private static final int TLS_ORD_TLS11  = 2;
    private static final int TLS_ORD_TLS12  = 3;
    private static final int TLS_ORD_TLS13  = 4;

    // ── SQL injection patterns ────────────────────────────────────────

    private static final Pattern[] SQL_INJECTION_PATTERNS = {
        Pattern.compile("(?i)'\\s*(OR|AND)\\s+\\d+\\s*=\\s*\\d+"),
        Pattern.compile("(?i)UNION\\s+(ALL\\s+)?SELECT"),
        Pattern.compile("(?i);\\s*DROP\\s+TABLE"),
        Pattern.compile("(?i);\\s*DELETE\\s+FROM"),
        Pattern.compile("(?i);\\s*INSERT\\s+INTO"),
        Pattern.compile("(?i);\\s*UPDATE\\s+\\w+\\s+SET"),
        Pattern.compile("(?i)--\\s*$"),
        Pattern.compile("(?i)/\\*.*\\*/"),
        Pattern.compile("(?i)EXEC(UTE)?\\s+(XP_|SP_)"),
        Pattern.compile("(?i)WAITFOR\\s+DELAY"),
        Pattern.compile("(?i)BENCHMARK\\s*\\("),
        Pattern.compile("(?i)LOAD_FILE\\s*\\("),
        Pattern.compile("(?i)INTO\\s+(OUT|DUMP)FILE"),
        Pattern.compile("(?i)SLEEP\\s*\\("),
        Pattern.compile("(?i)pg_sleep\\s*\\("),
        Pattern.compile("(?i)WAITFOR\\s+TIME"),
        Pattern.compile("(?i)0x[0-9a-fA-F]{4,}"),
        Pattern.compile("(?i)CONCAT\\s*\\("),
        Pattern.compile("(?i)(CHR|CHAR)\\s*\\("),
        Pattern.compile("(?i)CONVERT\\s*\\("),
        Pattern.compile("(?i)CAST\\s*\\("),
    };

    // ── Command injection patterns ────────────────────────────────────

    private static final Pattern[] CMD_INJECTION_PATTERNS = {
        Pattern.compile(";\\s*(ls|cat|rm|wget|curl|bash|sh|python|perl|ruby|nc|ncat)\\b"),
        Pattern.compile("\\|\\s*(cat|ls|id|whoami|uname|pwd|env)\\b"),
        Pattern.compile("`[^`]+`"),
        Pattern.compile("\\$\\([^)]+\\)"),
        Pattern.compile("\\$\\{[^}]+\\}"),
        Pattern.compile("(?i)&&\\s*(ls|cat|rm|wget|curl|bash|sh)\\b"),
        Pattern.compile("\\|\\|\\s*(ls|cat|rm|wget|curl|bash|sh|id|whoami|uname|pwd|env)\\b"),
    };

    // ── Path traversal patterns ───────────────────────────────────────

    private static final Pattern[] PATH_TRAVERSAL_PATTERNS = {
        Pattern.compile("\\.\\./"),
        Pattern.compile("\\.\\.\\\\"),
        Pattern.compile("%2[eE]%2[eE]"),
        Pattern.compile("%2[eE]\\."),
        Pattern.compile("\\.%2[eE]"),
        Pattern.compile("%252[eE]%252[eE]"),  // double-encoded
        Pattern.compile("\\.\\.%2[fF]"),
        Pattern.compile("%2[fF]\\.\\."),
    };

    // ── Suspicious User-Agents ────────────────────────────────────────

    private static final Pattern SUSPICIOUS_USER_AGENT = Pattern.compile(
        "(?i)(sqlmap|nikto|nmap|masscan|nessus|openvas|w3af|burpsuite|dirbuster|" +
        "gobuster|ffuf|wfuzz|hydra|medusa|ncrack|zgrab|censys|shodan)"
    );

    // ── SSH attack tool patterns ──────────────────────────────────────

    private static final Pattern SSH_ATTACK_TOOL = Pattern.compile(
        "(?i)(hydra|medusa|ncrack|libssh-scanner|go-ssh-brute|sshbrute)"
    );

    // ── SSH KEX accumulation ─────────────────────────────────────────

    /** Max bytes to accumulate per connection for SSH KEX inspection. */
    private static final int SSH_KEX_MAX_ACCUMULATION = 32 * 1024; // 32 KB

    /** Max packets to accumulate per connection (KEX_INIT is in packet 1 or 2). */
    private static final int SSH_KEX_MAX_PACKETS = 2;

    // ── State ─────────────────────────────────────────────────────────

    private final InspectionConfig config;
    private final int minTlsOrdinal;

    /**
     * Per-connection SSH handshake accumulation buffer. Keyed by connection ID
     * (e.g., channelId or clientIp:port). Used to reassemble SSH_MSG_KEXINIT
     * that may arrive in a follow-up packet after the version banner.
     */
    private final ConcurrentHashMap<String, SshAccumulator> sshAccumulators = new ConcurrentHashMap<>();

    /**
     * Tracks accumulated SSH handshake data for a single connection.
     */
    private static class SshAccumulator {
        final CompositeByteBuf buffer;
        int packetCount;
        boolean kexInspected;

        SshAccumulator() {
            this.buffer = Unpooled.compositeBuffer();
            this.packetCount = 0;
            this.kexInspected = false;
        }

        void release() {
            if (buffer.refCnt() > 0) {
                buffer.release();
            }
        }
    }

    // ── Constructor ───────────────────────────────────────────────────

    /**
     * Create a new deep packet inspector.
     *
     * @param config inspection configuration
     */
    public DeepPacketInspector(InspectionConfig config) {
        this.config = config;
        this.minTlsOrdinal = tlsVersionToOrdinal(config.minTlsVersion());
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Inspect the first bytes of a connection for security violations.
     * Does NOT consume bytes from the buffer (uses getByte/getBytes for zero-copy).
     *
     * <p>This overload does not track per-connection SSH state. Use
     * {@link #inspect(ByteBuf, String, int, String)} for SSH KEX accumulation
     * across multiple packets.</p>
     *
     * @param data             the first bytes received on the connection
     * @param detectedProtocol protocol string from ProtocolDetector (SSH, FTP, HTTP, TLS, etc.)
     * @param port             the listen port
     * @return inspection result — {@code allowed=true} if no violations found
     */
    public InspectionResult inspect(ByteBuf data, String detectedProtocol, int port) {
        return inspect(data, detectedProtocol, port, null);
    }

    /**
     * Inspect the first bytes of a connection for security violations, with
     * per-connection SSH KEX accumulation support.
     *
     * <p>For SSH connections, if the SSH_MSG_KEXINIT message is not present in
     * the current packet, data is accumulated in a per-connection buffer and
     * KEX inspection is deferred until a subsequent packet delivers the KEXINIT.
     * Accumulation is bounded to {@value #SSH_KEX_MAX_PACKETS} packets or
     * {@value #SSH_KEX_MAX_ACCUMULATION} bytes, whichever comes first.</p>
     *
     * @param data             the bytes received on the connection
     * @param detectedProtocol protocol string from ProtocolDetector (SSH, FTP, HTTP, TLS, etc.)
     * @param port             the listen port
     * @param connectionId     unique connection identifier (e.g., channel ID) for SSH
     *                         KEX buffering; may be null to skip accumulation
     * @return inspection result — {@code allowed=true} if no violations found
     */
    public InspectionResult inspect(ByteBuf data, String detectedProtocol, int port,
                                    String connectionId) {
        if (!config.enabled()) {
            return new InspectionResult(true, "disabled", Severity.INFO, "DPI disabled");
        }

        int readable = data.readableBytes();
        if (readable == 0) {
            return new InspectionResult(true, "empty", Severity.INFO, "No data to inspect");
        }

        String protocol = detectedProtocol != null ? detectedProtocol.toUpperCase() : "UNKNOWN";

        return switch (protocol) {
            case "TLS", "FTPS" -> inspectTls(data);
            case "SSH"         -> inspectSsh(data, connectionId);
            case "HTTP"        -> inspectHttp(data);
            case "FTP"         -> inspectFtp(data);
            default            -> new InspectionResult(true, "no_inspection", Severity.INFO,
                                      "No DPI rules for protocol: " + protocol);
        };
    }

    /**
     * Release accumulated SSH handshake buffers for a connection.
     * Must be called when a connection closes to prevent memory leaks.
     *
     * @param connectionId the connection identifier used in
     *                     {@link #inspect(ByteBuf, String, int, String)}
     */
    public void cleanupConnection(String connectionId) {
        if (connectionId == null) return;
        SshAccumulator acc = sshAccumulators.remove(connectionId);
        if (acc != null) {
            acc.release();
        }
    }

    // ── TLS Inspection ────────────────────────────────────────────────

    /**
     * Parse TLS ClientHello to extract version, cipher suites, and SNI.
     * Blocks connections below the configured minimum TLS version and connections
     * that only offer weak cipher suites.
     */
    private InspectionResult inspectTls(ByteBuf data) {
        int readerIndex = data.readerIndex();
        int readable = data.readableBytes();

        // Need at least 5 bytes: ContentType(1) + Version(2) + Length(2)
        if (readable < 5) {
            return new InspectionResult(true, "tls_insufficient", Severity.INFO,
                "Insufficient TLS data for inspection");
        }

        byte contentType = data.getByte(readerIndex);
        if (contentType != 0x16) {
            return new InspectionResult(true, "tls_not_handshake", Severity.INFO,
                "Not a TLS handshake record");
        }

        // Record-layer version
        byte recordMajor = data.getByte(readerIndex + 1);
        byte recordMinor = data.getByte(readerIndex + 2);

        // Record length
        int recordLength = ((data.getByte(readerIndex + 3) & 0xFF) << 8)
                         | (data.getByte(readerIndex + 4) & 0xFF);

        // Need at least 6 more bytes for HandshakeType(1) + Length(3) + ClientVersion(2)
        if (readable < 11) {
            return new InspectionResult(true, "tls_short_hello", Severity.INFO,
                "ClientHello too short for full inspection");
        }

        byte handshakeType = data.getByte(readerIndex + 5);
        if (handshakeType != 0x01) {
            return new InspectionResult(true, "tls_not_client_hello", Severity.INFO,
                "Not a ClientHello message");
        }

        // Client version (highest version client supports)
        byte clientMajor = data.getByte(readerIndex + 9);
        byte clientMinor = data.getByte(readerIndex + 10);

        String clientVersion = tlsVersionString(clientMajor, clientMinor);
        int clientOrdinal = tlsMinorToOrdinal(clientMinor);

        // ── Version enforcement ──
        if (config.enforceMinTls() && clientOrdinal < minTlsOrdinal) {
            log.warn("DPI: TLS version {} below minimum {}", clientVersion, config.minTlsVersion());
            return new InspectionResult(false, "tls_version_too_low", Severity.HIGH,
                "TLS version " + clientVersion + " below minimum " + config.minTlsVersion());
        }

        // ── Cipher suite analysis ──
        // Skip past: HandshakeType(1) + Length(3) + ClientVersion(2) + Random(32) = offset 44
        int offset = readerIndex + 5 + 1 + 3 + 2 + 32; // start of session ID length

        if (readable > offset - readerIndex) {
            // Session ID length (1 byte)
            int sessionIdLen = data.getByte(offset) & 0xFF;
            offset += 1 + sessionIdLen;

            if (readable > offset - readerIndex + 2) {
                // Cipher suites length (2 bytes)
                int cipherSuitesLen = ((data.getByte(offset) & 0xFF) << 8)
                                    | (data.getByte(offset + 1) & 0xFF);
                offset += 2;

                if (config.blockWeakCiphers() && readable >= offset - readerIndex + cipherSuitesLen) {
                    boolean allWeak = true;
                    for (int i = 0; i < cipherSuitesLen; i += 2) {
                        int suite = ((data.getByte(offset + i) & 0xFF) << 8)
                                  | (data.getByte(offset + i + 1) & 0xFF);
                        if (!isWeakCipherSuite(suite)) {
                            allWeak = false;
                            break;
                        }
                    }
                    if (allWeak && cipherSuitesLen > 0) {
                        log.warn("DPI: Client offers only weak cipher suites");
                        return new InspectionResult(false, "tls_only_weak_ciphers", Severity.HIGH,
                            "Client offers only weak/insecure cipher suites");
                    }
                }

                // Skip cipher suites to reach extensions for SNI
                offset += cipherSuitesLen;

                // Compression methods length (1 byte) + methods
                if (readable > offset - readerIndex) {
                    int compMethodsLen = data.getByte(offset) & 0xFF;
                    offset += 1 + compMethodsLen;

                    // Extensions
                    String sni = extractSni(data, offset, readable, readerIndex);
                    if (sni != null) {
                        log.debug("DPI: TLS SNI hostname: {}", sni);
                    }
                }
            }
        }

        return new InspectionResult(true, "tls_ok", Severity.INFO,
            "TLS " + clientVersion + " — inspection passed");
    }

    /**
     * Extract Server Name Indication (SNI) from TLS extensions.
     */
    private String extractSni(ByteBuf data, int offset, int readable, int readerIndex) {
        if (readable <= offset - readerIndex + 2) {
            return null;
        }

        int extensionsLength = ((data.getByte(offset) & 0xFF) << 8)
                             | (data.getByte(offset + 1) & 0xFF);
        offset += 2;
        int extensionsEnd = offset + extensionsLength;

        while (offset + 4 <= Math.min(extensionsEnd, readerIndex + readable)) {
            int extType = ((data.getByte(offset) & 0xFF) << 8)
                        | (data.getByte(offset + 1) & 0xFF);
            int extLen = ((data.getByte(offset + 2) & 0xFF) << 8)
                       | (data.getByte(offset + 3) & 0xFF);
            offset += 4;

            if (extType == 0x0000 && extLen > 5) { // SNI extension
                // SNI list length (2) + type (1) + name length (2) + name
                int nameLen = ((data.getByte(offset + 3) & 0xFF) << 8)
                            | (data.getByte(offset + 4) & 0xFF);
                if (offset + 5 + nameLen <= readerIndex + readable) {
                    byte[] nameBytes = new byte[nameLen];
                    data.getBytes(offset + 5, nameBytes);
                    return new String(nameBytes, StandardCharsets.US_ASCII);
                }
            }
            offset += extLen;
        }
        return null;
    }

    // ── SSH Inspection ────────────────────────────────────────────────

    /**
     * Inspect SSH version banner for protocol version 1 and known attack tools.
     * Supports per-connection KEX accumulation when a connectionId is provided:
     * if SSH_MSG_KEXINIT is not in the current packet, data is buffered and
     * KEX inspection is retried on the next call for the same connection.
     *
     * @param data         the bytes received on the connection
     * @param connectionId unique connection identifier for accumulation; may be null
     */
    private InspectionResult inspectSsh(ByteBuf data, String connectionId) {
        int readerIndex = data.readerIndex();
        int readable = data.readableBytes();
        int textLen = Math.min(readable, 256);

        byte[] textBytes = new byte[textLen];
        data.getBytes(readerIndex, textBytes);
        String banner = new String(textBytes, StandardCharsets.US_ASCII);

        // Extract version line (up to first \r or \n)
        String versionLine = banner;
        int crIndex = banner.indexOf('\r');
        int lfIndex = banner.indexOf('\n');
        if (crIndex >= 0) versionLine = banner.substring(0, crIndex);
        else if (lfIndex >= 0) versionLine = banner.substring(0, lfIndex);
        versionLine = versionLine.trim();

        // ── Block SSH-1.x ──
        if (config.blockSshV1() && versionLine.startsWith("SSH-1.")) {
            cleanupConnection(connectionId);
            log.warn("DPI: SSH protocol version 1 detected: {}", versionLine);
            return new InspectionResult(false, "ssh_v1_blocked", Severity.CRITICAL,
                "SSH protocol version 1 is insecure and blocked: " + versionLine);
        }

        // ── Flag known attack tools ──
        if (SSH_ATTACK_TOOL.matcher(versionLine).find()) {
            cleanupConnection(connectionId);
            log.warn("DPI: SSH attack tool detected in version string: {}", versionLine);
            return new InspectionResult(false, "ssh_attack_tool", Severity.CRITICAL,
                "Known SSH attack tool detected: " + versionLine);
        }

        // ── Check allowed key exchange algorithms ──
        if (config.allowedSshKex() != null && !config.allowedSshKex().isEmpty()) {
            InspectionResult kexResult = inspectSshKex(data, connectionId, versionLine);
            if (kexResult != null) {
                return kexResult;
            }
        }

        return new InspectionResult(true, "ssh_ok", Severity.INFO,
            "SSH inspection passed: " + versionLine);
    }

    /**
     * Inspect SSH KEX algorithms with per-connection accumulation support.
     *
     * <p>SSH_MSG_KEXINIT (type 20) may arrive in the same packet as the version
     * banner or in a subsequent packet. When a connectionId is provided, this method
     * accumulates up to {@value #SSH_KEX_MAX_PACKETS} packets (max
     * {@value #SSH_KEX_MAX_ACCUMULATION} bytes) to find the KEXINIT message.</p>
     *
     * @return an {@link InspectionResult} if KEX is found and disallowed, or null
     *         if KEX is acceptable or not yet found (pending more data)
     */
    private InspectionResult inspectSshKex(ByteBuf data, String connectionId,
                                           String versionLine) {
        // Determine the buffer to search for KEX_INIT
        ByteBuf searchBuf;
        SshAccumulator accumulator = null;

        if (connectionId != null) {
            accumulator = sshAccumulators.computeIfAbsent(connectionId, k -> new SshAccumulator());

            // If KEX was already inspected for this connection, skip
            if (accumulator.kexInspected) {
                return null;
            }

            // Append current packet data (retain a copy for the composite buffer)
            accumulator.packetCount++;
            ByteBuf copy = data.retainedSlice();
            accumulator.buffer.addComponent(true, copy);

            // Check limits — if exceeded, stop accumulating and allow
            if (accumulator.buffer.readableBytes() > SSH_KEX_MAX_ACCUMULATION
                    || accumulator.packetCount > SSH_KEX_MAX_PACKETS) {
                log.debug("DPI: SSH KEX accumulation limit reached for connection {}, "
                    + "packets={}, bytes={}", connectionId, accumulator.packetCount,
                    accumulator.buffer.readableBytes());
                accumulator.kexInspected = true;
                cleanupConnection(connectionId);
                return null; // allow — we cannot hold data indefinitely
            }

            searchBuf = accumulator.buffer;
        } else {
            // No connection tracking — inspect only the current packet (legacy behavior)
            searchBuf = data;
        }

        int searchReaderIndex = searchBuf.readerIndex();
        int searchReadable = searchBuf.readableBytes();
        int kexInitOffset = findSshKexInit(searchBuf, searchReaderIndex, searchReadable);

        if (kexInitOffset >= 0) {
            String kexAlgorithms = extractSshNameList(searchBuf, kexInitOffset,
                searchReadable, searchReaderIndex);
            if (kexAlgorithms != null) {
                boolean hasAllowed = false;
                for (String alg : kexAlgorithms.split(",")) {
                    if (config.allowedSshKex().contains(alg.trim())) {
                        hasAllowed = true;
                        break;
                    }
                }
                // Done with KEX inspection — clean up
                if (accumulator != null) {
                    accumulator.kexInspected = true;
                }
                cleanupConnection(connectionId);

                if (!hasAllowed) {
                    log.warn("DPI: SSH KEX algorithms not in allowed list: {}", kexAlgorithms);
                    return new InspectionResult(false, "ssh_kex_not_allowed", Severity.HIGH,
                        "No allowed key exchange algorithms offered: " + kexAlgorithms);
                }
            } else {
                // Name-list couldn't be extracted (truncated?) — mark done
                if (accumulator != null) {
                    accumulator.kexInspected = true;
                }
                cleanupConnection(connectionId);
            }
        }
        // KEX_INIT not found yet — if we have an accumulator, we'll try again on next packet
        // If no accumulator (connectionId null), this is the legacy single-packet behavior

        return null;
    }

    /**
     * Locate SSH_MSG_KEXINIT (message type 20) in the buffer.
     * Returns the offset of the first name-list (kex_algorithms) within the KEXINIT message,
     * or -1 if not found.
     */
    private int findSshKexInit(ByteBuf data, int readerIndex, int readable) {
        // After the version string there is a binary packet: length(4) + padding_length(1)
        // + payload starting with message type byte. We scan for message type 20.
        for (int i = readerIndex; i < readerIndex + readable - 5; i++) {
            // Check for newline followed by potential packet
            if (data.getByte(i) == '\n' && i + 6 < readerIndex + readable) {
                // Next 4 bytes = packet length
                int packetLen = ((data.getByte(i + 1) & 0xFF) << 24)
                              | ((data.getByte(i + 2) & 0xFF) << 16)
                              | ((data.getByte(i + 3) & 0xFF) << 8)
                              | (data.getByte(i + 4) & 0xFF);
                // padding_length
                int paddingLen = data.getByte(i + 5) & 0xFF;
                // message type
                if (i + 6 < readerIndex + readable) {
                    byte msgType = data.getByte(i + 6);
                    if (msgType == 20 && packetLen > 0 && packetLen < 65536) {
                        // KEXINIT: skip cookie (16 bytes) to reach first name-list
                        int nameListOffset = i + 7 + 16; // past msg_type + cookie
                        if (nameListOffset < readerIndex + readable) {
                            return nameListOffset;
                        }
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Extract the first SSH name-list (kex_algorithms) at the given offset.
     * Name-list format: length(4) + comma-separated ASCII string.
     */
    private String extractSshNameList(ByteBuf data, int offset, int readable, int readerIndex) {
        if (offset + 4 > readerIndex + readable) return null;

        int nameListLen = ((data.getByte(offset) & 0xFF) << 24)
                        | ((data.getByte(offset + 1) & 0xFF) << 16)
                        | ((data.getByte(offset + 2) & 0xFF) << 8)
                        | (data.getByte(offset + 3) & 0xFF);

        if (nameListLen <= 0 || nameListLen > 8192) return null;
        if (offset + 4 + nameListLen > readerIndex + readable) return null;

        byte[] nameBytes = new byte[nameListLen];
        data.getBytes(offset + 4, nameBytes);
        return new String(nameBytes, StandardCharsets.US_ASCII);
    }

    // ── HTTP Inspection ───────────────────────────────────────────────

    /**
     * Inspect HTTP request for injection attacks, path traversal, header abuse,
     * and suspicious User-Agents.
     */
    private InspectionResult inspectHttp(ByteBuf data) {
        int readerIndex = data.readerIndex();
        int readable = data.readableBytes();

        // Read up to maxHttpHeaderSize or available bytes
        int readLen = Math.min(readable, config.maxHttpHeaderSize() > 0 ? config.maxHttpHeaderSize() + 256 : 8448);
        byte[] httpBytes = new byte[readLen];
        data.getBytes(readerIndex, httpBytes);
        String httpText = new String(httpBytes, StandardCharsets.US_ASCII);

        // ── Header size validation ──
        if (config.validateHttpHeaders()) {
            int headerEnd = httpText.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                // No complete header block yet — check if buffer already exceeds limit
                if (readable > config.maxHttpHeaderSize()) {
                    log.warn("DPI: HTTP headers exceed max size {} bytes", config.maxHttpHeaderSize());
                    return new InspectionResult(false, "http_header_too_large", Severity.MEDIUM,
                        "HTTP headers exceed maximum size of " + config.maxHttpHeaderSize() + " bytes");
                }
            } else if (headerEnd > config.maxHttpHeaderSize()) {
                log.warn("DPI: HTTP headers size {} exceeds limit {}", headerEnd, config.maxHttpHeaderSize());
                return new InspectionResult(false, "http_header_too_large", Severity.MEDIUM,
                    "HTTP headers exceed maximum size of " + config.maxHttpHeaderSize() + " bytes");
            }
        }

        // ── Parse request line ──
        int firstLineEnd = httpText.indexOf("\r\n");
        String requestLine = firstLineEnd > 0 ? httpText.substring(0, firstLineEnd) : httpText;

        // ── Path traversal ──
        if (config.blockPathTraversal()) {
            for (Pattern pattern : PATH_TRAVERSAL_PATTERNS) {
                if (pattern.matcher(requestLine).find()) {
                    log.warn("DPI: Path traversal detected in request: {}", requestLine);
                    return new InspectionResult(false, "http_path_traversal", Severity.HIGH,
                        "Path traversal attempt detected: " + requestLine);
                }
            }
        }

        // ── SQL injection ──
        if (config.blockSqlInjection()) {
            for (Pattern pattern : SQL_INJECTION_PATTERNS) {
                if (pattern.matcher(httpText).find()) {
                    log.warn("DPI: SQL injection pattern detected");
                    return new InspectionResult(false, "http_sql_injection", Severity.CRITICAL,
                        "SQL injection pattern detected in HTTP request");
                }
            }
        }

        // ── Command injection ──
        if (config.blockCommandInjection()) {
            for (Pattern pattern : CMD_INJECTION_PATTERNS) {
                if (pattern.matcher(httpText).find()) {
                    log.warn("DPI: Command injection pattern detected");
                    return new InspectionResult(false, "http_command_injection", Severity.CRITICAL,
                        "Command injection pattern detected in HTTP request");
                }
            }
        }

        // ── Suspicious User-Agent ──
        if (config.validateHttpHeaders()) {
            String lowerText = httpText.toLowerCase();
            int uaIndex = lowerText.indexOf("user-agent:");
            if (uaIndex >= 0) {
                int uaEnd = httpText.indexOf("\r\n", uaIndex);
                String uaValue = uaEnd > uaIndex
                    ? httpText.substring(uaIndex + 11, uaEnd).trim()
                    : httpText.substring(uaIndex + 11).trim();
                if (SUSPICIOUS_USER_AGENT.matcher(uaValue).find()) {
                    log.warn("DPI: Suspicious User-Agent detected: {}", uaValue);
                    return new InspectionResult(false, "http_suspicious_ua", Severity.HIGH,
                        "Suspicious User-Agent: " + uaValue);
                }
            }
        }

        return new InspectionResult(true, "http_ok", Severity.INFO,
            "HTTP inspection passed");
    }

    // ── FTP Inspection ────────────────────────────────────────────────

    /**
     * Delegate FTP inspection to {@link FtpCommandFilter}.
     * Returns a pass-through result — callers should also use FtpCommandFilter
     * directly for per-command filtering on the data stream.
     */
    private InspectionResult inspectFtp(ByteBuf data) {
        // Basic initial-packet inspection: check for path traversal in the first line
        int readerIndex = data.readerIndex();
        int readable = data.readableBytes();
        int textLen = Math.min(readable, 512);

        byte[] textBytes = new byte[textLen];
        data.getBytes(readerIndex, textBytes);
        String text = new String(textBytes, StandardCharsets.US_ASCII);

        if (config.blockPathTraversal()) {
            for (Pattern pattern : PATH_TRAVERSAL_PATTERNS) {
                if (pattern.matcher(text).find()) {
                    log.warn("DPI: Path traversal detected in FTP command: {}", text.trim());
                    return new InspectionResult(false, "ftp_path_traversal", Severity.HIGH,
                        "Path traversal detected in FTP command");
                }
            }
        }

        return new InspectionResult(true, "ftp_delegate", Severity.INFO,
            "FTP initial inspection passed — use FtpCommandFilter for per-command filtering");
    }

    // ── TLS Helper Methods ────────────────────────────────────────────

    /**
     * Convert TLS version string to ordinal for comparison.
     */
    private static int tlsVersionToOrdinal(String version) {
        if (version == null) return TLS_ORD_TLS12; // default
        return switch (version) {
            case "SSLv3"   -> TLS_ORD_SSL3;
            case "TLSv1.0" -> TLS_ORD_TLS10;
            case "TLSv1.1" -> TLS_ORD_TLS11;
            case "TLSv1.2" -> TLS_ORD_TLS12;
            case "TLSv1.3" -> TLS_ORD_TLS13;
            default        -> TLS_ORD_TLS12;
        };
    }

    /**
     * Convert TLS minor version byte to ordinal.
     */
    private static int tlsMinorToOrdinal(byte minor) {
        return switch (minor) {
            case 0x00 -> TLS_ORD_SSL3;
            case 0x01 -> TLS_ORD_TLS10;
            case 0x02 -> TLS_ORD_TLS11;
            case 0x03 -> TLS_ORD_TLS12;
            case 0x04 -> TLS_ORD_TLS13;
            default   -> TLS_ORD_SSL3; // unknown = treat as lowest
        };
    }

    /**
     * Human-readable TLS version from major/minor bytes.
     */
    private static String tlsVersionString(byte major, byte minor) {
        if (major != 0x03) return "Unknown(" + major + "." + minor + ")";
        return switch (minor) {
            case 0x00 -> "SSLv3";
            case 0x01 -> "TLSv1.0";
            case 0x02 -> "TLSv1.1";
            case 0x03 -> "TLSv1.2";
            case 0x04 -> "TLSv1.3";
            default   -> "TLS_unknown(3." + minor + ")";
        };
    }

    /**
     * Determine if a cipher suite is weak (NULL, EXPORT, DES, RC4, anonymous).
     * Cipher suite values per IANA TLS Cipher Suite Registry.
     */
    private static boolean isWeakCipherSuite(int suite) {
        // NULL ciphers: 0x0000–0x0003
        if (suite <= 0x0003) return true;

        // EXPORT ciphers: various ranges in 0x0003–0x0028
        if (suite >= 0x0003 && suite <= 0x000C) return true;  // RSA/DH_EXPORT
        if (suite >= 0x0011 && suite <= 0x0015) return true;  // DHE_EXPORT
        if (suite == 0x0017 || suite == 0x0019) return true;  // DH_anon_EXPORT
        if (suite >= 0x0026 && suite <= 0x0028) return true;  // KRB5_EXPORT

        // DES (single): 0x0009, 0x000C, 0x0012, 0x0015, 0x001A
        if (suite == 0x0009 || suite == 0x000C || suite == 0x0012
            || suite == 0x0015 || suite == 0x001A) return true;

        // RC4 ciphers: 0x0004, 0x0005, 0x0017, 0x0018, 0x0024
        if (suite == 0x0004 || suite == 0x0005 || suite == 0x0017
            || suite == 0x0018 || suite == 0x0024) return true;

        // Anonymous DH (no auth): 0x0017–0x001B, 0x0034, 0x003A, 0x006C–0x006D, 0x00A6–0x00A7
        if (suite >= 0x0017 && suite <= 0x001B) return true;
        if (suite == 0x0034 || suite == 0x003A) return true;
        if (suite == 0x006C || suite == 0x006D) return true;
        if (suite == 0x00A6 || suite == 0x00A7) return true;

        // GREASE values (not weak, but reserved — allow them)
        // Anything else is considered acceptable
        return false;
    }
}
