package com.filetransfer.onboarding.exception;

import lombok.Getter;

import java.util.List;

/**
 * Thrown when a listener create/update requests a host:port already claimed
 * by another active ServerInstance. Carries a list of nearby free ports so
 * the API / UI can guide the admin to a working choice.
 */
@Getter
public class PortConflictException extends RuntimeException {

    private final String host;
    private final int requestedPort;
    private final List<Integer> suggestedPorts;

    public PortConflictException(String host, int requestedPort, List<Integer> suggestedPorts) {
        super("Port " + requestedPort + " already in use on host " + host);
        this.host = host;
        this.requestedPort = requestedPort;
        this.suggestedPorts = suggestedPorts;
    }
}
