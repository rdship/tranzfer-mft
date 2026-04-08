package com.filetransfer.shared.enums;

/**
 * Determines how services discover and communicate with each other across clusters.
 *
 * WITHIN_CLUSTER — services only route to other services in the same cluster.
 *                   Use for isolated deployments (separate regions, tenants, or environments).
 *
 * CROSS_CLUSTER  — services can discover and route to services in any cluster.
 *                   Use for federated deployments where clusters cooperate.
 */
public enum ClusterCommunicationMode {
    WITHIN_CLUSTER,
    CROSS_CLUSTER
}
