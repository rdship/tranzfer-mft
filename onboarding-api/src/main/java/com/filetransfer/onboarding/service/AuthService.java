package com.filetransfer.onboarding.service;

import com.filetransfer.onboarding.dto.request.LoginRequest;
import com.filetransfer.onboarding.dto.request.RegisterRequest;
import com.filetransfer.onboarding.dto.response.AuthResponse;
import com.filetransfer.onboarding.entity.RefreshToken;
import com.filetransfer.onboarding.repository.RefreshTokenRepository;
import com.filetransfer.onboarding.security.BruteForceProtection;
import com.filetransfer.onboarding.security.PasswordPolicy;
import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.entity.core.User;
import com.filetransfer.shared.enums.UserRole;
import com.filetransfer.shared.repository.core.UserRepository;
import com.filetransfer.shared.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final PasswordPolicy passwordPolicy;
    private final BruteForceProtection bruteForceProtection;
    private final AuditService auditService;
    private final RefreshTokenRepository refreshTokenRepository;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_LENGTH = 48; // 48 bytes = 64 chars base64

    @Value("${platform.security.refresh-token-ttl-days:7}")
    private int refreshTokenTtlDays;

    @Value("${platform.security.jwt-expiration-ms:900000}")
    private long accessTokenExpirationMs;

    /**
     * H9 fix: register is now idempotent under concurrent requests.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        return register(request, null, null);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, String clientIp, String userAgent) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Email already registered: " + request.getEmail());
        }

        passwordPolicy.validate(request.getPassword(), request.getEmail());

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.USER)
                .build();
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Email already registered: " + request.getEmail());
        }

        auditService.logLogin(request.getEmail(), clientIp, true, "registration");
        log.info("User registered: {}", request.getEmail());

        return buildAuthResponse(user.getEmail(), user.getRole().name(), clientIp, userAgent);
    }

    public AuthResponse login(LoginRequest request) {
        return login(request, null, null);
    }

    public AuthResponse login(LoginRequest request, String clientIp, String userAgent) {
        if (bruteForceProtection.isLocked(request.getEmail())) {
            auditService.logLogin(request.getEmail(), clientIp, false, "account locked");
            throw new LockedException("Account locked due to too many failed attempts. Try again in 30 minutes.");
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            bruteForceProtection.recordFailure(request.getEmail());
            int remaining = bruteForceProtection.getRemainingAttempts(request.getEmail());
            auditService.logLogin(request.getEmail(), clientIp, false,
                    "invalid credentials (" + remaining + " attempts remaining)");
            log.warn("Login failed for {} ({} attempts remaining)", request.getEmail(), remaining);
            throw new BadCredentialsException("Invalid credentials. " + remaining + " attempts remaining.");
        }

        bruteForceProtection.recordSuccess(request.getEmail());
        auditService.logLogin(request.getEmail(), clientIp, true, null);
        log.info("Login successful: {}", request.getEmail());

        return buildAuthResponse(user.getEmail(), user.getRole().name(), clientIp, userAgent);
    }

    /**
     * Refresh an access token using a valid refresh token.
     * Implements token rotation — the old refresh token is revoked and a new one issued.
     */
    @Transactional
    public AuthResponse refresh(String refreshTokenStr, String clientIp, String userAgent) {
        RefreshToken existing = refreshTokenRepository.findByTokenAndRevokedFalse(refreshTokenStr)
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired refresh token"));

        if (existing.isExpired()) {
            existing.setRevoked(true);
            refreshTokenRepository.save(existing);
            throw new BadCredentialsException("Refresh token expired. Please login again.");
        }

        // Revoke the used refresh token (rotation)
        existing.setRevoked(true);
        refreshTokenRepository.save(existing);

        log.info("Token refreshed for user: {}", existing.getUserEmail());
        return buildAuthResponse(existing.getUserEmail(), existing.getUserRole(), clientIp, userAgent);
    }

    /**
     * Revoke all refresh tokens for a user (logout from all devices).
     */
    @Transactional
    public void revokeAllSessions(String email) {
        int revoked = refreshTokenRepository.revokeAllByUserEmail(email);
        log.info("Revoked {} refresh tokens for user: {}", revoked, email);
    }

    /**
     * Clean up expired refresh tokens daily.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired refresh tokens", deleted);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private AuthResponse buildAuthResponse(String email, String role, String clientIp, String userAgent) {
        String accessToken = jwtUtil.generateToken(email, role);
        RefreshToken refreshToken = createRefreshToken(email, role, clientIp, userAgent);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
                .tokenType("Bearer")
                .expiresIn(accessTokenExpirationMs / 1000)
                .build();
    }

    private RefreshToken createRefreshToken(String email, String role, String clientIp, String userAgent) {
        byte[] randomBytes = new byte[REFRESH_TOKEN_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        RefreshToken refreshToken = RefreshToken.builder()
                .token(token)
                .userEmail(email)
                .userRole(role)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(refreshTokenTtlDays)))
                .revoked(false)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .build();

        return refreshTokenRepository.save(refreshToken);
    }
}
