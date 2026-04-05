package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A scheduled task — runs a flow, transfer, or script on a cron/interval schedule.
 */
@Entity @Table(name = "scheduled_tasks") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ScheduledTask extends Auditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(unique = true, nullable = false) private String name;
    private String description;
    /** CRON expression (e.g. "0 0 2 * * *" = every day at 2am) */
    @Column(nullable = false) private String cronExpression;
    /** Timezone for cron (default UTC) */
    @Builder.Default private String timezone = "UTC";
    /** What to execute: RUN_FLOW, PULL_FILES, PUSH_FILES, EXECUTE_SCRIPT, CLEANUP */
    @Column(nullable = false, length = 20) private String taskType;
    /** Reference ID (flow ID, account ID, etc.) */
    private String referenceId;
    /** Task-specific config */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Map<String, String> config;
    @Builder.Default private boolean enabled = true;
    private Instant lastRun;
    private Instant nextRun;
    private String lastStatus; // SUCCESS, FAILED, RUNNING
    private String lastError;
    private int totalRuns;
    private int failedRuns;
}
