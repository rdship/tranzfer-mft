package com.filetransfer.onboarding.service;

import com.filetransfer.onboarding.dto.request.LoginRequest;
import com.filetransfer.onboarding.dto.request.RegisterRequest;
import com.filetransfer.onboarding.dto.response.AuthResponse;
import com.filetransfer.onboarding.security.BruteForceProtection;
import com.filetransfer.onboarding.security.PasswordPolicy;
import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.entity.User;
import com.filetransfer.shared.enums.UserRole;
import com.filetransfer.shared.repository.UserRepository;
import com.filetransfer.shared.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered: " + request.getEmail());
        }

        // PCI DSS 8.2 — enforce password policy
        passwordPolicy.validate(request.getPassword(), request.getEmail());

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.USER)
                .build();
        userRepository.save(user);

        auditService.logLogin(request.getEmail(), null, true, "registration");
        log.info("User registered: {}", request.getEmail());

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(900)
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        // PCI DSS 8.1.6 — check lockout
        if (bruteForceProtection.isLocked(request.getEmail())) {
            auditService.logLogin(request.getEmail(), null, false, "account locked");
            throw new LockedException("Account locked due to too many failed attempts. Try again in 30 minutes.");
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            bruteForceProtection.recordFailure(request.getEmail());
            int remaining = bruteForceProtection.getRemainingAttempts(request.getEmail());
            auditService.logLogin(request.getEmail(), null, false,
                    "invalid credentials (" + remaining + " attempts remaining)");
            log.warn("Login failed for {} ({} attempts remaining)", request.getEmail(), remaining);
            throw new BadCredentialsException("Invalid credentials. " + remaining + " attempts remaining.");
        }

        bruteForceProtection.recordSuccess(request.getEmail());
        auditService.logLogin(request.getEmail(), null, true, null);
        log.info("Login successful: {}", request.getEmail());

        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());
        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(900)
                .build();
    }
}
