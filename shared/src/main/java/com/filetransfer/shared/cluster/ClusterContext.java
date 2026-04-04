package com.filetransfer.shared.cluster;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

/**
 * Holds the identity of the current running service instance.
 * Populated at startup by ClusterRegistrationService in each service module.
 */
@Component
@Getter
@Setter
public class ClusterContext {
    private String serviceInstanceId;
    private String clusterId;
}
