package com.filetransfer.shared.dto;

import com.filetransfer.shared.enums.Protocol;

import java.io.Serializable;
import java.util.UUID;

/**
 * Event published when a ServerInstance is created, updated, activated,
 * deactivated, or deleted. Consumed by protocol services (SFTP/FTP/AS2) to
 * bind/unbind/reconfigure listeners at runtime without restart.
 *
 * <p>The {@code internalPort} and {@code internalHost} fields are included
 * so consumers can decide whether they own this listener without a round-trip
 * to the DB.</p>
 */
public record ServerInstanceChangeEvent(
        UUID id,
        String instanceId,
        Protocol protocol,
        String internalHost,
        int internalPort,
        boolean active,
        ChangeType changeType
) implements Serializable {

    public enum ChangeType {
        CREATED,
        UPDATED,
        ACTIVATED,
        DEACTIVATED,
        DELETED
    }

    public String routingKey() {
        return "server.instance." + changeType.name().toLowerCase();
    }
}
