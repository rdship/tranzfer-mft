package com.filetransfer.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-configurable LLM data sharing preferences.
 *
 * <p>Each data category can be independently enabled/disabled.
 * When enabled, that category's data is included in the LLM prompt
 * for richer, more accurate AI responses. When disabled, the LLM
 * operates without that data — less accurate but more private.
 *
 * <p>The admin sees the risk and value of each category before deciding.
 * No data is shared without explicit opt-in. Defaults are conservative.
 *
 * <p>Configured via API: PUT /api/v1/ai/data-sharing
 * Or via application.yml: ai.data-sharing.*
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.data-sharing")
public class LlmDataSharingConfig {

    /** Platform health metrics (transfer counts, error rates, DLQ depth) */
    private Category platformMetrics = new Category(true,
            "Platform Health Metrics",
            "Transfer counts, error rates, queue depths, service health scores",
            "LLM can answer: 'how's the platform doing?', 'what's the error rate?', 'any DLQ buildup?'",
            "Aggregate numbers only. No customer-specific data. Minimal risk.");

    /** Transfer record details (status, filename, timestamps — no file content) */
    private Category transferRecords = new Category(false,
            "Transfer Record Details",
            "Transfer status, original filename, upload time, error messages, track IDs",
            "LLM can answer: 'why did TRZ-X7K9M2 fail?', 'what files did acme upload today?'",
            "Filenames may reveal business context (invoice_acme_2026.edi). Partner names visible in track context.");

    /** Flow step snapshots (per-step timing, storage keys, step errors) */
    private Category stepSnapshots = new Category(false,
            "Flow Step Snapshots",
            "Per-step execution time, input/output storage keys (SHA-256 hashes), step error messages",
            "LLM can answer: 'which step failed?', 'how long did encryption take?', 'what was the step-by-step timeline?'",
            "SHA-256 hashes are not reversible. Error messages may contain partner/file context.");

    /** Audit log entries (login events, file routes, screening results) */
    private Category auditLogs = new Category(false,
            "Audit Log Entries",
            "Login attempts (username, IP, success/fail), file routing events, screening decisions",
            "LLM can answer: 'who logged in from this IP?', 'was this file screened?', 'show login failures'",
            "Contains usernames, IP addresses, and action history. PII-adjacent data.");

    /** Encryption key metadata (key names, algorithms, expiry — never key material) */
    private Category keyMetadata = new Category(false,
            "Encryption Key Metadata",
            "Key names/aliases, algorithm type, creation date, expiry date (NEVER key material/bytes)",
            "LLM can answer: 'which keys are expiring?', 'what encryption does acme use?'",
            "Key names may reveal partner relationships. Key material is NEVER shared regardless of this setting.");

    /** Partner/account details (partner names, protocols, SLA status) */
    private Category partnerDetails = new Category(false,
            "Partner & Account Details",
            "Partner company names, account usernames, protocols, SLA breach status",
            "LLM can answer: 'which partners are breaching SLA?', 'list acme's accounts'",
            "Contains business-sensitive partner identities and relationship data.");

    /** DLQ message details (error messages, queue names, retry counts) */
    private Category dlqDetails = new Category(false,
            "Dead Letter Queue Details",
            "Failed message error text, original queue name, retry count, failure timestamp",
            "LLM can answer: 'what's in the DLQ?', 'why are messages failing?'",
            "Error messages may contain file/partner context from failed operations.");

    /**
     * Returns all categories as a map for API/UI consumption.
     * Each entry: key → {enabled, name, dataIncluded, valueProvided, riskDescription}
     */
    public Map<String, Category> getAllCategories() {
        Map<String, Category> all = new LinkedHashMap<>();
        all.put("platformMetrics", platformMetrics);
        all.put("transferRecords", transferRecords);
        all.put("stepSnapshots", stepSnapshots);
        all.put("auditLogs", auditLogs);
        all.put("keyMetadata", keyMetadata);
        all.put("partnerDetails", partnerDetails);
        all.put("dlqDetails", dlqDetails);
        return all;
    }

    @Data
    public static class Category {
        private boolean enabled;
        private String name;
        private String dataIncluded;
        private String valueProvided;
        private String riskDescription;

        public Category() {}

        public Category(boolean enabled, String name, String dataIncluded,
                        String valueProvided, String riskDescription) {
            this.enabled = enabled;
            this.name = name;
            this.dataIncluded = dataIncluded;
            this.valueProvided = valueProvided;
            this.riskDescription = riskDescription;
        }
    }
}
