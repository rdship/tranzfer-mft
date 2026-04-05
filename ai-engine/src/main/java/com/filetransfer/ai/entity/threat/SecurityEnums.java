package com.filetransfer.ai.entity.threat;

/**
 * Consolidated enumeration types for the SecurityAI security event schema.
 *
 * <p>Keeping all enums in a single file avoids class-per-enum sprawl while
 * maintaining clear separation of concerns through inner-enum naming.</p>
 */
public final class SecurityEnums {

    private SecurityEnums() {
        // utility class — no instantiation
    }

    // ── Event Source Types ─────────────────────────────────────────────

    /**
     * Classification of the originating telemetry source for a {@link SecurityEvent}.
     */
    public enum SourceType {
        /** Network flow / packet capture telemetry. */
        NETWORK,
        /** Host-based endpoint telemetry (EDR, process events). */
        ENDPOINT,
        /** Authentication / identity provider events. */
        AUTH,
        /** DNS query and response telemetry. */
        DNS,
        /** HTTP/HTTPS request telemetry (WAF, proxy logs). */
        HTTP,
        /** Cloud control-plane / data-plane audit logs. */
        CLOUD,
        /** File-system or object-storage events. */
        FILE,
        /** External threat intelligence feed events. */
        THREAT_INTEL,
        /** Catch-all for vendor-specific or uncategorized sources. */
        CUSTOM
    }

    // ── Indicator of Compromise Types ─────────────────────────────────

    /**
     * Type of indicator stored in a {@link ThreatIndicator}.
     */
    public enum IndicatorType {
        IP,
        DOMAIN,
        URL,
        HASH_MD5,
        HASH_SHA1,
        HASH_SHA256,
        EMAIL,
        CVE,
        JA3,
        JA3S,
        CIDR,
        MUTEX,
        REGISTRY_KEY,
        USER_AGENT,
        FILE_NAME,
        CERTIFICATE_SHA256
    }

    // ── Threat Levels ─────────────────────────────────────────────────

    /**
     * Threat severity classification for indicators of compromise.
     */
    public enum ThreatLevel {
        UNKNOWN,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    // ── Alert Severity ────────────────────────────────────────────────

    /**
     * Severity classification for security alerts.
     */
    public enum AlertSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    // ── Alert Lifecycle Status ────────────────────────────────────────

    /**
     * Workflow status of a {@link SecurityAlert}.
     */
    public enum AlertStatus {
        /** Newly generated, not yet triaged. */
        NEW,
        /** Analyst is actively investigating. */
        INVESTIGATING,
        /** Alert resolved — threat confirmed and handled. */
        RESOLVED,
        /** Alert determined to be a false positive. */
        FALSE_POSITIVE,
        /** Alert escalated to a higher tier / incident response. */
        ESCALATED
    }
}
