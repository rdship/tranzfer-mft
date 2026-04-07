package com.filetransfer.onboarding.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Single-call onboarding request that provisions a complete user setup:
 * user + accounts + (optional) partner + (optional) flows + (optional) folder mappings
 * + (optional) external destinations.
 *
 * <p>Replaces the 5-6 sequential API calls previously required for full onboarding.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedOnboardRequest {

    // ── Required ────────────────────────────────────────────────────────

    @NotNull
    @Valid
    private UserSetup user;

    @NotEmpty
    @Valid
    private List<AccountSetup> accounts;

    // ── Optional ────────────────────────────────────────────────────────

    @Valid
    private PartnerSetup partner;

    @Valid
    private List<FlowSetup> flows;

    @Valid
    private List<FolderMappingSetup> folderMappings;

    @Valid
    private List<ExternalDestinationSetup> externalDestinations;

    /** Assign all accounts to a specific server instance (e.g. "sftp-1") */
    private String serverInstanceId;

    // ── Nested DTOs ─────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSetup {
        @NotBlank
        @Email
        private String email;

        @NotBlank
        @Size(min = 8)
        private String password;

        /** Defaults to USER if not specified */
        private String role;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountSetup {
        /** SFTP, FTP, FTP_WEB, HTTPS, AS2, AS4 */
        @NotNull
        private String protocol;

        @NotBlank
        @Size(min = 3, max = 64)
        private String username;

        @NotBlank
        @Size(min = 8)
        private String password;

        /** SSH public key for SFTP key-based auth */
        private String publicKey;

        /** e.g. {"read": true, "write": true, "delete": false} */
        private Map<String, Boolean> permissions;

        /** Override home directory (null = auto-resolved based on protocol) */
        private String homeDir;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartnerSetup {
        @NotBlank
        @Size(max = 255)
        private String companyName;

        @Size(max = 255)
        private String displayName;

        @Size(max = 100)
        private String industry;

        /** INTERNAL, EXTERNAL, VENDOR, CLIENT */
        private String partnerType;

        private List<String> protocolsEnabled;

        /** STANDARD, PREMIUM, ENTERPRISE */
        private String slaTier;

        @Valid
        private List<ContactSetup> contacts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContactSetup {
        @NotBlank
        private String name;

        private String email;
        private String phone;
        private String role;
        private boolean primary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowSetup {
        @NotBlank
        private String name;

        private String description;
        private String filenamePattern;
        private String sourcePath;
        private int priority;

        @NotEmpty
        @Valid
        private List<StepSetup> steps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepSetup {
        /** ENCRYPT_PGP, DECRYPT_PGP, COMPRESS_GZIP, DECOMPRESS_GZIP, RENAME, ROUTE, etc. */
        @NotBlank
        private String type;

        private Map<String, String> config;
        private int order;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FolderMappingSetup {
        /** Username of the source account (must match one of the accounts in this request) */
        private String sourceAccountUsername;

        @NotBlank
        private String sourcePath;

        /** Username of the destination account (must match one of the accounts in this request) */
        private String destinationAccountUsername;

        @NotBlank
        private String destinationPath;

        /** Java regex for filename matching; null = match all */
        private String pattern;

        private boolean encryptionEnabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExternalDestinationSetup {
        @NotBlank
        private String name;

        /** SFTP, FTP, KAFKA */
        @NotBlank
        private String type;

        @NotBlank
        private String host;

        private int port;
        private String username;
        private String password;
        private String remotePath;
        private boolean proxyEnabled;
    }
}
