package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "tenants") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tenant extends Auditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @NotBlank @Column(unique = true, nullable = false) private String slug;
    @NotBlank @Column(nullable = false) private String companyName;
    @Email private String contactEmail;
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
}
