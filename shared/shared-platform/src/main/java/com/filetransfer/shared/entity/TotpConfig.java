package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "totp_configs") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TotpConfig {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(unique = true, nullable = false) private String username;
    /** Base32-encoded TOTP secret */
    @Column(nullable = false) private String secret;
    /** Is 2FA currently active for this user? */
    @Builder.Default private boolean enabled = false;
    /** Has user completed enrollment (scanned QR)? */
    @Builder.Default private boolean enrolled = false;
    /** Backup codes (comma-separated, hashed) */
    @Column(columnDefinition = "TEXT") private String backupCodes;
    /** Method: TOTP_APP, EMAIL, SMS */
    @Builder.Default private String method = "TOTP_APP";
    /** Email for OTP delivery */
    private String otpEmail;
    private Instant enrolledAt;
    private Instant lastUsedAt;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}
