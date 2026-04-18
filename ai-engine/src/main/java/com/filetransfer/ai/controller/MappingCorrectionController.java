package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.edi.MappingCorrectionService;
import com.filetransfer.ai.service.edi.MappingCorrectionService.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Natural Language Mapping Correction REST API.
 *
 * Partners can iteratively correct EDI field mappings through natural language:
 *   1. POST /sessions              — Start a correction session with sample EDI input
 *   2. POST /sessions/{id}/correct — Describe what needs to change in plain English
 *   3. POST /sessions/{id}/test    — Re-run test with current mappings
 *   4. POST /sessions/{id}/approve — Persist corrected map and update file flow
 *   5. POST /sessions/{id}/reject  — Discard corrections
 */
@RestController
@RequestMapping("/api/v1/edi/correction")
@RequiredArgsConstructor
@Slf4j
public class MappingCorrectionController {

    private final MappingCorrectionService correctionService;

    // ===================================================================
    // Session Lifecycle
    // ===================================================================

    /** Start a new correction session */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse startSession(@RequestBody StartRequest request) {
        validate(request);
        return correctionService.startSession(request);
    }

    /** Get session details */
    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(@PathVariable UUID sessionId,
                                       @RequestParam String partnerId) {
        return correctionService.getSession(sessionId, partnerId);
    }

    /**
     * List correction sessions. When a {@code partnerId} is supplied, scopes
     * to that partner. Admin-side callers (Activity Monitor operational view)
     * can omit the param to list every session across partners.
     */
    @GetMapping("/sessions")
    public List<SessionSummary> listSessions(@RequestParam(required = false) String partnerId) {
        return correctionService.listSessions(partnerId);
    }

    // ===================================================================
    // Corrections
    // ===================================================================

    /** Submit a natural language correction */
    @PostMapping("/sessions/{sessionId}/correct")
    public CorrectionResult submitCorrection(@PathVariable UUID sessionId,
                                              @RequestBody SubmitCorrectionRequest request) {
        if (request.getPartnerId() == null || request.getPartnerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerId is required");
        }
        if (request.getInstruction() == null || request.getInstruction().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instruction is required");
        }
        return correctionService.submitCorrection(sessionId, request.getPartnerId(), request.getInstruction());
    }

    /** Re-run test with current mappings */
    @PostMapping("/sessions/{sessionId}/test")
    public TestResult runTest(@PathVariable UUID sessionId,
                               @RequestParam String partnerId) {
        return correctionService.runTest(sessionId, partnerId);
    }

    // ===================================================================
    // Approval / Rejection
    // ===================================================================

    /** Approve the corrected mapping — persists as new ConversionMap and updates file flow */
    @PostMapping("/sessions/{sessionId}/approve")
    public ApprovalResult approve(@PathVariable UUID sessionId,
                                   @RequestParam String partnerId) {
        return correctionService.approve(sessionId, partnerId);
    }

    /** Reject and discard corrections */
    @PostMapping("/sessions/{sessionId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID sessionId,
                        @RequestParam String partnerId) {
        correctionService.reject(sessionId, partnerId);
    }

    // ===================================================================
    // History
    // ===================================================================

    /** Get correction history for a session */
    @GetMapping("/sessions/{sessionId}/history")
    public ResponseEntity<String> getCorrectionHistory(@PathVariable UUID sessionId,
                                                        @RequestParam String partnerId) {
        String history = correctionService.getCorrectionHistory(sessionId, partnerId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(history);
    }

    // ===================================================================
    // Health
    // ===================================================================

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "mapping-correction",
                "capabilities", List.of(
                        "natural-language-correction",
                        "claude-ai-interpretation",
                        "keyword-fallback",
                        "sample-testing",
                        "before-after-comparison",
                        "auto-flow-update",
                        "session-expiry"));
    }

    // ===================================================================
    // Validation & DTOs
    // ===================================================================

    private void validate(StartRequest request) {
        if (request.getPartnerId() == null || request.getPartnerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerId is required");
        }
        if (request.getSourceFormat() == null || request.getSourceFormat().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceFormat is required");
        }
        if (request.getTargetFormat() == null || request.getTargetFormat().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetFormat is required");
        }
        if (request.getSampleInputContent() == null || request.getSampleInputContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sampleInputContent is required");
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SubmitCorrectionRequest {
        private String partnerId;
        private String instruction;
    }
}
