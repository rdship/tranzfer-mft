package com.filetransfer.license.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "crypto_key_pairs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CryptoKeyPair {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "key_name", unique = true, nullable = false)
    private String keyName;

    @Column(name = "public_key", columnDefinition = "TEXT", nullable = false)
    private String publicKey;  // Base64 encoded

    @Column(name = "private_key", columnDefinition = "TEXT", nullable = false)
    private String privateKey;  // Base64 encoded
}
