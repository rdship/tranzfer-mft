package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.Protocol;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "transfer_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferAccount extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Protocol protocol;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    // OpenSSH authorized_keys format (SFTP only)
    @Column(columnDefinition = "TEXT")
    private String publicKey;

    @Column(nullable = false)
    private String homeDir;

    // e.g. {"read": true, "write": true, "delete": false}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Boolean> permissions = Map.of("read", true, "write", true, "delete", false);

    // Server instance this account is assigned to (null = any instance, works for all protocols)
    @Column(length = 64)
    private String serverInstance;

    // Partner this account belongs to (optional)
    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<AuditLog> auditLogs;
}
