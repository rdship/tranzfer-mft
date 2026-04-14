package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;

@Entity @Table(name = "auto_onboard_sessions") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AutoOnboardSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    /** IP of the connecting client */
    @Column(nullable = false) private String sourceIp;
    /** SSH client version string (e.g. "OpenSSH_8.9p1 Ubuntu-3") */
    private String clientVersion;
    /** Detected protocol capabilities */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Map<String, Object> capabilities;
    /** Auto-generated username */
    private String generatedUsername;
    /** Auto-generated temp password */
    private String tempPassword;
    /** Session phase: DETECTED, ACCOUNT_CREATED, LEARNING, FLOW_CREATED, COMPLETE */
    @Builder.Default private String phase = "DETECTED";
    /** Files observed during learning phase */
    @Builder.Default private int filesObserved = 0;
    /** Detected file patterns */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private List<String> detectedPatterns;
    /** Auto-created flow ID */
    private String autoFlowId;
    /** Auto-assigned security profile */
    private String securityProfileId;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
    private Instant completedAt;
}
