package com.filetransfer.dmz.inspection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FtpCommandFilterTest {

    private FtpCommandFilter.FtpFilterConfig defaultConfig;
    private FtpCommandFilter filter;

    @BeforeEach
    void setUp() {
        defaultConfig = new FtpCommandFilter.FtpFilterConfig(
            true, true, true, true, false,
            List.of(), List.of(), 512, false
        );
        filter = new FtpCommandFilter(defaultConfig);
    }

    // ── 1. Disabled allows everything ────────────────────────────────

    @Test
    void disabled_allowsEverything() {
        var config = new FtpCommandFilter.FtpFilterConfig(
            false, true, true, true, false,
            List.of(), List.of(), 512, false
        );
        var disabledFilter = new FtpCommandFilter(config);

        var result = disabledFilter.checkCommand("DELE /something/dangerous");
        assertTrue(result.allowed());
        assertEquals("filter_disabled", result.reason());
    }

    // ── 2. Standard command allowed ──────────────────────────────────

    @Test
    void standardCommand_allowed() {
        var result = filter.checkCommand("RETR /path/to/file.txt");
        assertTrue(result.allowed());
        assertEquals("RETR", result.command());
        assertEquals("/path/to/file.txt", result.argument());
    }

    // ── 3. Unknown command blocked ───────────────────────────────────

    @Test
    void unknownCommand_blocked() {
        var result = filter.checkCommand("XYZW something");
        assertFalse(result.allowed());
        assertEquals("XYZW", result.command());
        assertEquals("command_not_allowed", result.reason());
    }

    // ── 4. Blacklisted command blocked ───────────────────────────────

    @Test
    void blacklistedCommand_blocked() {
        var config = new FtpCommandFilter.FtpFilterConfig(
            true, true, true, true, false,
            List.of(), List.of("DELE"), 512, false
        );
        var blockDeleFilter = new FtpCommandFilter(config);

        var result = blockDeleFilter.checkCommand("DELE /file.txt");
        assertFalse(result.allowed());
        assertEquals("DELE", result.command());
        assertEquals("command_blacklisted", result.reason());
    }

    // ── 5. PORT command blocked ──────────────────────────────────────

    @Test
    void portCommand_blocked() {
        // Include PORT in the allowed list so it reaches the blockPortCommand check
        var config = new FtpCommandFilter.FtpFilterConfig(
            true, true, true, true, false,
            List.of("PORT", "RETR", "STOR", "PASV", "USER", "PASS", "QUIT", "LIST", "CWD"),
            List.of(), 512, false
        );
        var portFilter = new FtpCommandFilter(config);

        var result = portFilter.checkCommand("PORT 10,0,0,1,4,1");
        assertFalse(result.allowed());
        assertEquals("PORT", result.command());
        assertEquals("port_command_blocked", result.reason());
    }

    // ── 6. PORT bounce attack detected ───────────────────────────────

    @Test
    void portBounceAttack_detected() {
        // blockPortCommand=false, include PORT in allowed so we reach IP validation
        var config = new FtpCommandFilter.FtpFilterConfig(
            true, false, true, true, false,
            List.of("PORT", "EPRT", "RETR", "STOR", "PASV", "USER", "PASS", "QUIT", "LIST", "CWD"),
            List.of(), 512, false
        );
        var bounceFilter = new FtpCommandFilter(config);

        var result = bounceFilter.checkCommand("PORT 192,168,1,1,4,1", "10.0.0.1");
        assertFalse(result.allowed());
        assertEquals("PORT", result.command());
        assertTrue(result.reason().contains("bounce_attack_detected"));
    }

    // ── 7. PORT command with valid client IP allowed ─────────────────

    @Test
    void portCommand_validClientIp() {
        var config = new FtpCommandFilter.FtpFilterConfig(
            true, false, true, true, false,
            List.of("PORT", "EPRT", "RETR", "STOR", "PASV", "USER", "PASS", "QUIT", "LIST", "CWD"),
            List.of(), 512, false
        );
        var bounceFilter = new FtpCommandFilter(config);

        var result = bounceFilter.checkCommand("PORT 10,0,0,1,4,1", "10.0.0.1");
        assertTrue(result.allowed());
        assertEquals("PORT", result.command());
    }

    // ── 8. EPRT bounce attack detected ───────────────────────────────

    @Test
    void eprtBounceAttack_detected() {
        var config = new FtpCommandFilter.FtpFilterConfig(
            true, false, true, true, false,
            List.of("PORT", "EPRT", "RETR", "STOR", "PASV", "USER", "PASS", "QUIT", "LIST", "CWD"),
            List.of(), 512, false
        );
        var bounceFilter = new FtpCommandFilter(config);

        var result = bounceFilter.checkCommand("EPRT |1|192.168.1.1|5000|", "10.0.0.1");
        assertFalse(result.allowed());
        assertEquals("EPRT", result.command());
        assertTrue(result.reason().contains("bounce_attack_detected"));
    }

    // ── 9. SITE command blocked ──────────────────────────────────────

    @Test
    void siteCommand_blocked() {
        // Include SITE in allowed list so it reaches the blockSiteCommand check
        var config = new FtpCommandFilter.FtpFilterConfig(
            true, true, true, true, false,
            List.of("SITE", "RETR", "STOR", "PASV", "USER", "PASS", "QUIT", "LIST", "CWD"),
            List.of(), 512, false
        );
        var siteFilter = new FtpCommandFilter(config);

        var result = siteFilter.checkCommand("SITE EXEC something");
        assertFalse(result.allowed());
        assertEquals("SITE", result.command());
        assertEquals("site_command_blocked", result.reason());
    }

    // ── 10. Path traversal blocked ───────────────────────────────────

    @Test
    void pathTraversal_blocked() {
        var result = filter.checkCommand("CWD ../../etc");
        assertFalse(result.allowed());
        assertEquals("CWD", result.command());
        assertEquals("path_traversal_detected", result.reason());
    }

    // ── 11. Null byte injection blocked ──────────────────────────────

    @Test
    void nullByteInjection_blocked() {
        var result = filter.checkCommand("RETR /path/file\0.txt");
        assertFalse(result.allowed());
        assertEquals("RETR", result.command());
        assertEquals("null_byte_injection", result.reason());
    }

    // ── 12. Command too long blocked ─────────────────────────────────

    @Test
    void commandTooLong_blocked() {
        String longCommand = "RETR /" + "A".repeat(600);
        var result = filter.checkCommand(longCommand);
        assertFalse(result.allowed());
        assertEquals("command_too_long", result.reason());
    }

    // ── 13. Empty command blocked ────────────────────────────────────

    @Test
    void emptyCommand_blocked() {
        var resultEmpty = filter.checkCommand("");
        assertFalse(resultEmpty.allowed());
        assertEquals("empty_command", resultEmpty.reason());

        var resultNull = filter.checkCommand((String) null);
        assertFalse(resultNull.allowed());
        assertEquals("empty_command", resultNull.reason());
    }

    // ── 14. Passive mode required blocks PORT ────────────────────────

    @Test
    void passiveModeRequired_blocksPort() {
        // Include PORT in allowed list so it reaches the requirePassiveMode check
        var config = new FtpCommandFilter.FtpFilterConfig(
            true, false, true, true, true,
            List.of("PORT", "EPRT", "RETR", "STOR", "PASV", "USER", "PASS", "QUIT", "LIST", "CWD"),
            List.of(), 512, false
        );
        var passiveFilter = new FtpCommandFilter(config);

        var result = passiveFilter.checkCommand("PORT 10,0,0,1,4,1");
        assertFalse(result.allowed());
        assertEquals("passive_mode_required", result.reason());
    }

    // ── 15. PASV command always allowed ──────────────────────────────

    @Test
    void pasvCommand_allowed() {
        var result = filter.checkCommand("PASV");
        assertTrue(result.allowed());
        assertEquals("PASV", result.command());
        assertEquals("allowed", result.reason());
    }
}
