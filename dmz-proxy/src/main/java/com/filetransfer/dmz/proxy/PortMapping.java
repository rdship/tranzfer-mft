package com.filetransfer.dmz.proxy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single port mapping: external port → internal host:port.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortMapping {
    private String name;
    private int listenPort;
    private String targetHost;
    private int targetPort;
    private boolean active;
}
