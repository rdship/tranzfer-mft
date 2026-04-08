package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.TotpConfig;
import com.filetransfer.shared.repository.TotpConfigRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * TOTP Two-Factor Authentication (RFC 6238).
 * Generates secrets, validates 6-digit codes, manages backup codes.
 */
@RestController @RequestMapping("/api/2fa") @RequiredArgsConstructor @Slf4j
@PreAuthorize(Roles.ANY_AUTHENTICATED)
public class TotpController {

    private final TotpConfigRepository totpRepo;
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String BACKUP_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I/O/0/1 ambiguity
    private static final int CODE_DIGITS = 6;
    private static final int TIME_STEP = 30;
    private static final int BACKUP_CODE_LENGTH = 12;

    /** Extract authenticated username from SecurityContext — prevents cross-user 2FA manipulation */
    private String getAuthenticatedUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new SecurityException("Not authenticated");
        }
        return auth.getName();
    }

    /** Hash a backup code with SHA-256 for safe storage */
    private String hashBackupCode(String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Generate cryptographically strong alphanumeric backup code */
    private String generateBackupCode() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(BACKUP_CODE_LENGTH);
        for (int i = 0; i < BACKUP_CODE_LENGTH; i++) {
            sb.append(BACKUP_CODE_CHARS.charAt(rng.nextInt(BACKUP_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** Enable 2FA for the authenticated user — returns secret + QR provisioning URI */
    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enable(@RequestBody Map<String, String> body) {
        String username = getAuthenticatedUsername();
        String method = body.getOrDefault("method", "TOTP_APP");

        TotpConfig config = totpRepo.findByUsername(username)
                .orElse(TotpConfig.builder().username(username).build());

        String secret = generateBase32Secret(20);
        config.setSecret(secret);
        config.setEnabled(true);
        config.setEnrolled(false);
        config.setMethod(method);
        if (body.containsKey("email")) config.setOtpEmail(body.get("email"));

        // Generate 10 strong backup codes (12-char alphanumeric); store hashed
        List<String> backupCodes = IntStream.range(0, 10)
                .mapToObj(i -> generateBackupCode())
                .collect(Collectors.toList());
        List<String> hashedCodes = backupCodes.stream()
                .map(this::hashBackupCode)
                .collect(Collectors.toList());
        config.setBackupCodes(String.join(",", hashedCodes));
        totpRepo.save(config);

        String issuer = "TranzFer";
        String provisioningUri = String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&digits=%d&period=%d",
                issuer, username, secret, issuer, CODE_DIGITS, TIME_STEP);

        log.info("2FA enabled for {}", username);
        return ResponseEntity.ok(Map.of(
                "username", username, "secret", secret, "method", method,
                "provisioningUri", provisioningUri, "backupCodes", backupCodes,
                "qrContent", provisioningUri,
                "instructions", "Scan the QR code with Google Authenticator, Authy, or Microsoft Authenticator. Then verify with a code."
        ));
    }

    /** Verify a TOTP code (completes enrollment on first success) */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body) {
        String username = getAuthenticatedUsername();
        String code = body.get("code");

        TotpConfig config = totpRepo.findByUsername(username).orElse(null);
        if (config == null || !config.isEnabled()) {
            return ResponseEntity.ok(Map.of("valid", false, "error", "2FA not enabled for this user"));
        }

        // Check TOTP code (current window + 1 before/after for clock skew)
        boolean valid = false;
        long currentInterval = Instant.now().getEpochSecond() / TIME_STEP;
        for (long i = currentInterval - 1; i <= currentInterval + 1; i++) {
            if (generateTotp(config.getSecret(), i).equals(code)) {
                valid = true;
                break;
            }
        }

        // Check backup codes (stored as hashes) if TOTP didn't match
        if (!valid && config.getBackupCodes() != null) {
            String codeHash = hashBackupCode(code);
            List<String> backups = new ArrayList<>(Arrays.asList(config.getBackupCodes().split(",")));
            if (backups.remove(codeHash)) {
                valid = true;
                config.setBackupCodes(String.join(",", backups));
                log.warn("Backup code used for {}", username);
            }
        }

        if (valid) {
            if (!config.isEnrolled()) {
                config.setEnrolled(true);
                config.setEnrolledAt(Instant.now());
            }
            config.setLastUsedAt(Instant.now());
            totpRepo.save(config);
        }

        return ResponseEntity.ok(Map.of("valid", valid, "username", username,
                "enrolled", config.isEnrolled()));
    }

    /** Check if the authenticated user has 2FA enabled */
    @GetMapping("/status/{username}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String username) {
        String authenticatedUser = getAuthenticatedUsername();
        if (!authenticatedUser.equals(username)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        TotpConfig config = totpRepo.findByUsername(username).orElse(null);
        if (config == null) return ResponseEntity.ok(Map.of("enabled", false, "username", username));
        return ResponseEntity.ok(Map.of("enabled", config.isEnabled(), "enrolled", config.isEnrolled(),
                "method", config.getMethod(), "username", username,
                "lastUsed", config.getLastUsedAt() != null ? config.getLastUsedAt().toString() : "never"));
    }

    /** Disable 2FA for the authenticated user */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, String>> disable(@RequestBody Map<String, String> body) {
        String username = getAuthenticatedUsername();
        totpRepo.findByUsername(username).ifPresent(c -> { c.setEnabled(false); totpRepo.save(c); });
        return ResponseEntity.ok(Map.of("status", "DISABLED", "username", username));
    }

    // === TOTP Generation (RFC 6238) ===

    private String generateTotp(String base32Secret, long timeInterval) {
        try {
            byte[] key = base32Decode(base32Secret);
            byte[] data = ByteBuffer.allocate(8).putLong(timeInterval).array();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (Exception e) {
            return "";
        }
    }

    private String generateBase32Secret(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        StringBuilder sb = new StringBuilder();
        for (byte b : random) sb.append(BASE32_CHARS.charAt((b & 0xFF) % 32));
        return sb.toString();
    }

    private byte[] base32Decode(String base32) {
        List<Integer> bits = new ArrayList<>();
        for (char c : base32.toUpperCase().toCharArray()) {
            int val = BASE32_CHARS.indexOf(c);
            if (val < 0) continue;
            for (int i = 4; i >= 0; i--) bits.add((val >> i) & 1);
        }
        byte[] result = new byte[bits.size() / 8];
        for (int i = 0; i < result.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) b = (b << 1) | bits.get(i * 8 + j);
            result[i] = (byte) b;
        }
        return result;
    }
}
