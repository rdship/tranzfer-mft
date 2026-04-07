package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.UnifiedOnboardRequest;
import com.filetransfer.onboarding.dto.response.UnifiedOnboardResponse;
import com.filetransfer.onboarding.service.UnifiedOnboardService;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified onboarding endpoint.
 * Creates a complete user setup (user + accounts + partner + flows + folder mappings
 * + external destinations) in a single API call.
 *
 * <p>Restricted to ADMIN role. Local DB operations are atomic; remote service calls
 * (flows, folder mappings, external destinations) are best-effort with warnings on failure.
 */
@RestController
@RequestMapping("/api/v1/onboard")
@RequiredArgsConstructor
@Slf4j
public class UnifiedOnboardController {

    private final UnifiedOnboardService service;

    @PostMapping
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<UnifiedOnboardResponse> onboard(
            @Valid @RequestBody UnifiedOnboardRequest request) {
        log.info("Unified onboarding request for: {}", request.getUser().getEmail());
        UnifiedOnboardResponse response = service.onboard(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
