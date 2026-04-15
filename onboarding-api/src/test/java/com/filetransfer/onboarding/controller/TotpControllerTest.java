package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.security.TotpConfig;
import com.filetransfer.shared.repository.security.TotpConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TotpControllerTest {

    @Mock
    private TotpConfigRepository totpRepo;

    private TotpController controller;

    @BeforeEach
    void setUp() {
        controller = new TotpController(totpRepo);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- Enable TOTP: generates Base32 secret, backup codes, returns provisioning URI ---

    @Test
    void enable_generatesBase32SecretAndProvisioningUri() {
        setAuthenticatedUser("alice");
        when(totpRepo.findByUsername("alice")).thenReturn(Optional.empty());
        when(totpRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.enable(Map.of("method", "TOTP_APP"));

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        // Secret should be Base32 encoded (only A-Z and 2-7)
        String secret = (String) body.get("secret");
        assertNotNull(secret);
        assertTrue(secret.length() >= 20, "Secret should be at least 20 chars");
        assertTrue(secret.matches("[A-Z2-7]+"), "Secret must be valid Base32 characters");

        // Provisioning URI should be otpauth format
        String uri = (String) body.get("provisioningUri");
        assertNotNull(uri);
        assertTrue(uri.startsWith("otpauth://totp/TranzFer:alice"));
        assertTrue(uri.contains("secret=" + secret));
        assertTrue(uri.contains("issuer=TranzFer"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));

        // Backup codes should be returned
        @SuppressWarnings("unchecked")
        List<String> backupCodes = (List<String>) body.get("backupCodes");
        assertNotNull(backupCodes);
        assertEquals(10, backupCodes.size());
    }

    // --- Backup code generation: 12 chars, only uses allowed characters ---

    @Test
    void enable_backupCodesAre12CharsWithAllowedCharsOnly() {
        setAuthenticatedUser("bob");
        when(totpRepo.findByUsername("bob")).thenReturn(Optional.empty());
        when(totpRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.enable(Map.of());

        @SuppressWarnings("unchecked")
        List<String> backupCodes = (List<String>) response.getBody().get("backupCodes");
        String allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

        for (String code : backupCodes) {
            assertEquals(12, code.length(), "Backup code must be exactly 12 characters");
            for (char c : code.toCharArray()) {
                assertTrue(allowedChars.indexOf(c) >= 0,
                        "Character '" + c + "' is not in the allowed set (no I/O/0/1)");
            }
        }
    }

    @Test
    void enable_backupCodesExcludeAmbiguousCharacters() {
        setAuthenticatedUser("charlie");
        when(totpRepo.findByUsername("charlie")).thenReturn(Optional.empty());
        when(totpRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Generate many codes to statistically verify no ambiguous chars appear
        List<String> allCodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ResponseEntity<Map<String, Object>> response = controller.enable(Map.of());
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) response.getBody().get("backupCodes");
            allCodes.addAll(codes);
        }

        String allChars = String.join("", allCodes);
        assertFalse(allChars.contains("I"), "Backup codes must not contain 'I'");
        assertFalse(allChars.contains("O"), "Backup codes must not contain 'O'");
        assertFalse(allChars.contains("0"), "Backup codes must not contain '0'");
        assertFalse(allChars.contains("1"), "Backup codes must not contain '1'");
    }

    // --- Backup code hashing: SHA-256, consistent hash for same input ---

    @Test
    void enable_backupCodesAreStoredHashed() {
        setAuthenticatedUser("dave");
        when(totpRepo.findByUsername("dave")).thenReturn(Optional.empty());
        ArgumentCaptor<TotpConfig> captor = ArgumentCaptor.forClass(TotpConfig.class);
        when(totpRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.enable(Map.of());

        @SuppressWarnings("unchecked")
        List<String> plaintextCodes = (List<String>) response.getBody().get("backupCodes");
        TotpConfig savedConfig = captor.getValue();
        String[] storedHashes = savedConfig.getBackupCodes().split(",");

        assertEquals(10, storedHashes.length);

        // Verify each stored hash is the SHA-256 of the plaintext code
        for (int i = 0; i < plaintextCodes.size(); i++) {
            String expectedHash = sha256Hex(plaintextCodes.get(i));
            assertEquals(expectedHash, storedHashes[i],
                    "Stored hash should be SHA-256 of plaintext backup code");
        }
    }

    @Test
    void backupCodeHash_isConsistentForSameInput() {
        // SHA-256 determinism: same input always produces the same hash
        String hash1 = sha256Hex("TESTCODE1234");
        String hash2 = sha256Hex("TESTCODE1234");
        assertEquals(hash1, hash2);

        // Different input produces different hash
        String hash3 = sha256Hex("DIFFERENTCODE");
        assertNotEquals(hash1, hash3);
    }

    // --- Verify endpoint: accepts valid TOTP code ---

    @Test
    void verify_acceptsValidBackupCode() {
        setAuthenticatedUser("eve");
        String backupCode = "ABCDEFGHJKLM";
        String hashedCode = sha256Hex(backupCode);

        TotpConfig config = TotpConfig.builder()
                .username("eve")
                .secret("JBSWY3DPEHPK3PXP")
                .enabled(true)
                .enrolled(false)
                .backupCodes(hashedCode + ",anotherhash")
                .build();

        when(totpRepo.findByUsername("eve")).thenReturn(Optional.of(config));
        when(totpRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.verify(Map.of("code", backupCode));

        assertEquals(200, response.getStatusCode().value());
        assertTrue((Boolean) response.getBody().get("valid"));
    }

    @Test
    void verify_rejectsBadCode() {
        setAuthenticatedUser("frank");
        TotpConfig config = TotpConfig.builder()
                .username("frank")
                .secret("JBSWY3DPEHPK3PXP")
                .enabled(true)
                .enrolled(true)
                .backupCodes("somehash1,somehash2")
                .build();

        when(totpRepo.findByUsername("frank")).thenReturn(Optional.of(config));

        ResponseEntity<Map<String, Object>> response = controller.verify(Map.of("code", "000000"));

        assertEquals(200, response.getStatusCode().value());
        assertFalse((Boolean) response.getBody().get("valid"));
    }

    @Test
    void verify_returns2faNotEnabledWhenConfigMissing() {
        setAuthenticatedUser("ghost");
        when(totpRepo.findByUsername("ghost")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.verify(Map.of("code", "123456"));

        assertFalse((Boolean) response.getBody().get("valid"));
        assertEquals("2FA not enabled for this user", response.getBody().get("error"));
    }

    @Test
    void verify_returns2faNotEnabledWhenDisabled() {
        setAuthenticatedUser("henry");
        TotpConfig config = TotpConfig.builder()
                .username("henry")
                .secret("JBSWY3DPEHPK3PXP")
                .enabled(false)
                .build();
        when(totpRepo.findByUsername("henry")).thenReturn(Optional.of(config));

        ResponseEntity<Map<String, Object>> response = controller.verify(Map.of("code", "123456"));

        assertFalse((Boolean) response.getBody().get("valid"));
    }

    // --- SecurityContext enforcement ---

    @Test
    void getAuthenticatedUsername_returnsAuthenticatedPrincipal() {
        setAuthenticatedUser("admin-user");
        // Provide a valid config so verify() reaches the code path that returns "username"
        TotpConfig config = TotpConfig.builder()
                .username("admin-user")
                .secret("JBSWY3DPEHPK3PXP")
                .enabled(true)
                .enrolled(true)
                .backupCodes("somehash")
                .build();
        when(totpRepo.findByUsername("admin-user")).thenReturn(Optional.of(config));

        ResponseEntity<Map<String, Object>> response = controller.verify(Map.of("code", "123456"));

        assertEquals("admin-user", response.getBody().get("username"));
    }

    @Test
    void getAuthenticatedUsername_throwsSecurityExceptionWhenMissing() {
        // Clear SecurityContext — no authentication
        SecurityContextHolder.clearContext();

        assertThrows(SecurityException.class,
                () -> controller.verify(Map.of("code", "123456")));
    }

    @Test
    void getAuthenticatedUsername_throwsSecurityExceptionWhenAuthNull() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(null);
        SecurityContextHolder.setContext(context);

        assertThrows(SecurityException.class,
                () -> controller.enable(Map.of()));
    }

    // --- Status endpoint: denies access when authenticated user != requested username (403) ---

    @Test
    void status_deniesAccessWhenUserMismatch() {
        setAuthenticatedUser("alice");

        ResponseEntity<Map<String, Object>> response = controller.status("bob");

        assertEquals(403, response.getStatusCode().value());
        assertEquals("Access denied", response.getBody().get("error"));
    }

    @Test
    void status_allowsAccessWhenUserMatches() {
        setAuthenticatedUser("alice");
        TotpConfig config = TotpConfig.builder()
                .username("alice")
                .secret("SECRET")
                .enabled(true)
                .enrolled(true)
                .method("TOTP_APP")
                .build();
        when(totpRepo.findByUsername("alice")).thenReturn(Optional.of(config));

        ResponseEntity<Map<String, Object>> response = controller.status("alice");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("enabled"));
        assertEquals("alice", response.getBody().get("username"));
    }

    @Test
    void status_returnsDisabledWhenNoConfig() {
        setAuthenticatedUser("newuser");
        when(totpRepo.findByUsername("newuser")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.status("newuser");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("enabled"));
    }

    // --- Disable endpoint: uses authenticated username, not request body ---

    @Test
    void disable_usesAuthenticatedUsernameNotRequestBody() {
        setAuthenticatedUser("alice");
        TotpConfig config = TotpConfig.builder()
                .username("alice")
                .secret("SECRET")
                .enabled(true)
                .build();
        when(totpRepo.findByUsername("alice")).thenReturn(Optional.of(config));
        when(totpRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Request body contains a DIFFERENT username — should be ignored
        ResponseEntity<Map<String, String>> response = controller.disable(
                Map.of("username", "evil-attacker"));

        assertEquals(200, response.getStatusCode().value());
        // The response username should be the authenticated user, not the request body
        assertEquals("alice", response.getBody().get("username"));

        // Verify the repo was queried with the authenticated user
        verify(totpRepo).findByUsername("alice");
        verify(totpRepo, never()).findByUsername("evil-attacker");
    }

    @Test
    void disable_setsEnabledToFalse() {
        setAuthenticatedUser("bob");
        TotpConfig config = TotpConfig.builder()
                .username("bob")
                .secret("SECRET")
                .enabled(true)
                .build();
        when(totpRepo.findByUsername("bob")).thenReturn(Optional.of(config));
        ArgumentCaptor<TotpConfig> captor = ArgumentCaptor.forClass(TotpConfig.class);
        when(totpRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        controller.disable(Map.of());

        assertFalse(captor.getValue().isEnabled());
    }

    // === Helpers ===

    private void setAuthenticatedUser(String username) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    private String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
