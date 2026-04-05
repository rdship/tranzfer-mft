package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "tenants") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tenant {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(unique = true, nullable = false) private String slug;
    @Column(nullable = false) private String companyName;
    private String contactEmail;
    /** TRIAL, STANDARD, PROFESSIONAL, ENTERPRISE */
    @Builder.Default private String plan = "TRIAL";
    private Instant trialEndsAt;
    @Builder.Default private long transfersUsed = 0;
    private Long transferLimit;
    /** Custom branding */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private Map<String, String> branding;
    /** Custom domain (acme.tranzfer.io) */
    private String customDomain;
    @Builder.Default private boolean active = true;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}
