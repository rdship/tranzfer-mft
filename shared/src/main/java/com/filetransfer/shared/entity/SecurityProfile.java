package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "security_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SecurityProfile extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** SSH or TLS */
    @Column(nullable = false)
    @Builder.Default
    private String type = "SSH";

    // SSH-specific
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "ssh_ciphers")
    private List<String> sshCiphers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "ssh_macs")
    private List<String> sshMacs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "kex_algorithms")
    private List<String> kexAlgorithms;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "host_key_algorithms")
    private List<String> hostKeyAlgorithms;

    // TLS-specific
    private String tlsMinVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "tls_ciphers")
    private List<String> tlsCiphers;

    @Builder.Default
    private boolean clientAuthRequired = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

}
