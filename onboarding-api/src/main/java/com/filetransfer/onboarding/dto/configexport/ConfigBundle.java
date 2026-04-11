package com.filetransfer.onboarding.dto.configexport;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Portable, versioned representation of platform configuration, produced by
 * {@code ConfigBundleBuilder} and consumed by the config-import side (Phase 2).
 *
 * <p>Phase 1 is read-only: we serialize a selected set of entity types into a
 * single JSON payload that an operator can promote from one environment to
 * another. Secrets (password hashes, private key material, anything that is
 * environment-specific or @JsonIgnore) are stripped out and, where relevant,
 * recorded as a {@link Redaction} so the import side can ask the operator to
 * re-supply them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonPropertyOrder({
        "schemaVersion",
        "exportedAt",
        "sourceEnvironment",
        "sourcePlatformVersion",
        "sourceCluster",
        "scope",
        "entities",
        "redactions",
        "checksum"
})
public class ConfigBundle {

    /** Schema version of the bundle format. Bump when the on-wire shape changes. */
    @Builder.Default
    private String schemaVersion = "1.0.0";

    /** When the bundle was produced (server clock, UTC). */
    private Instant exportedAt;

    /** Source environment tag — e.g. "test", "stage", "prod". From {@code PLATFORM_ENVIRONMENT}. */
    private String sourceEnvironment;

    /** Platform version (from pom). Used by import to check compatibility. */
    private String sourcePlatformVersion;

    /** Cluster id of the source deployment. From {@code CLUSTER_ID}. */
    private String sourceCluster;

    /** Which entity type keys are included in this bundle (e.g. "partners", "flows"). */
    private List<String> scope;

    /**
     * Entity type name -> list of serialized DTO records.
     * Kept as {@code List<?>} because each entity type has its own DTO shape.
     */
    private Map<String, List<?>> entities;

    /** Secret fields that were stripped during export, for operator awareness on import. */
    private List<Redaction> redactions;

    /** SHA-256 hex digest over the canonical JSON of entities + redactions. */
    private String checksum;

    /**
     * Record of a secret that was stripped from an entity before export.
     * On import the operator is expected to re-populate this field from the
     * target environment's secret store.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonPropertyOrder({"entityType", "entityId", "field", "reason"})
    public static class Redaction {
        private String entityType;
        private String entityId;
        private String field;
        private String reason;
    }
}
