package com.filetransfer.shared.dto;

import java.io.Serializable;
import java.time.Instant;

/**
 * Published by keystore-manager when a key is rotated. Consumed by protocol
 * services (SFTP/FTP/AS2) to trigger hot reload of affected listeners without
 * a service restart.
 *
 * <p>{@code keyType} is one of: SSH_HOST_KEY, TLS_CERT, AES_SYMMETRIC,
 * HMAC_SECRET, PGP_KEYPAIR.</p>
 */
public record KeystoreKeyRotatedEvent(
        String oldAlias,
        String newAlias,
        String keyType,
        String ownerService,
        Instant rotatedAt
) implements Serializable {

    public static String ROUTING_KEY = "keystore.key.rotated";
}
