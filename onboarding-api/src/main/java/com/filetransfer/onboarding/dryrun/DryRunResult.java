package com.filetransfer.onboarding.dryrun;

import lombok.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of a flow dry run — step-by-step static validation without touching storage or partners.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DryRunResult {

    private String flowId;
    private String flowName;
    private String testFilename;

    /** Per-step validation outcomes, in execution order. */
    private List<StepValidation> steps;

    /** Sum of per-step estimatedMs. */
    private long totalEstimatedMs;

    /** True iff every step is WOULD_SUCCEED or CANNOT_VERIFY (service down is not a blocker). */
    private boolean wouldSucceed;

    /** Steps that are WOULD_FAIL — actionable issues to fix before running live. */
    private List<String> issues;

    private Instant generatedAt;

    // ── Per-step validation ───────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StepValidation {
        private int    stepIndex;
        private String stepType;
        private String label;

        /**
         * Outcome:
         * <ul>
         *   <li>WOULD_SUCCEED   — config is valid and all required resources exist</li>
         *   <li>WOULD_FAIL      — config is missing or a required resource doesn't exist</li>
         *   <li>CANNOT_VERIFY   — dependent service unreachable; step may or may not succeed</li>
         * </ul>
         */
        private String status;
        private String message;

        /** Historical P50 latency from FlowStepSnapshot data, or hardcoded fallback. */
        private long estimatedMs;

        /** Sanitised config (no secrets). */
        private Map<String, String> config;
    }
}
