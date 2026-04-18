package com.filetransfer.shared.entity.transfer;

import com.filetransfer.shared.entity.core.*;

import com.filetransfer.shared.entity.core.Auditable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.filetransfer.shared.enums.EncryptionOption;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "folder_mappings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FolderMapping extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id", nullable = false)
    private TransferAccount sourceAccount;

    /** Relative to user home, e.g. "/inbox" */
    @NotBlank
    @Column(nullable = false)
    private String sourcePath;

    /**
     * Internal destination account. Null when externalDestination is set.
     * Either destinationAccount OR externalDestination must be non-null.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private TransferAccount destinationAccount;

    /** Relative to destination user home, e.g. "/outbox" */
    private String destinationPath;

    /**
     * External destination (SFTP/FTP/Kafka outside our system).
     * When set, destinationAccount is ignored.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "external_destination_id")
    private ExternalDestination externalDestination;

    /** Java regex for filename matching; null = match all files */
    @Column
    private String filenamePattern;

    /** How to handle encryption when forwarding this file */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private EncryptionOption encryptionOption = EncryptionOption.NONE;

    /**
     * Encryption key to use. For ENCRYPT_BEFORE_FORWARD: destination's public key.
     * For DECRYPT_THEN_FORWARD: source's private key.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encryption_key_id")
    private EncryptionKey encryptionKey;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * R109 (F4): opt-in sender notification when the partner picks up a file
     * routed by this mapping. When TRUE, RoutingEngine fires a
     * {@code PARTNER_PICKUP} lifecycle event on pickup; notification-service
     * consumes it and emails / webhooks the source account owner. Default
     * FALSE preserves pre-R109 behaviour — admins opt in per relationship.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean notifyOnPickup = false;

}
