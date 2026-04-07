package com.filetransfer.onboarding.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Response from the unified onboarding endpoint.
 * Contains IDs/summaries of all resources created, plus a JWT for the new user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedOnboardResponse {

    private UUID userId;
    private String userEmail;
    private String accessToken;

    private List<AccountResult> accounts;
    private PartnerResult partner;                          // null if not requested
    private List<FlowResult> flows;                         // null if not requested
    private List<FolderMappingResult> folderMappings;       // null if not requested
    private List<ExternalDestinationResult> externalDestinations; // null if not requested

    private List<String> warnings;                          // partial-success warnings
    private String message;

    // ── Nested result DTOs ──────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountResult {
        private UUID id;
        private String protocol;
        private String username;
        private String homeDir;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartnerResult {
        private UUID id;
        private String companyName;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowResult {
        private UUID id;
        private String name;
        private int stepCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FolderMappingResult {
        private UUID id;
        private String sourceAccount;
        private String destinationPath;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExternalDestinationResult {
        private UUID id;
        private String name;
        private String type;
    }
}
