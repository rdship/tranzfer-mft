package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.UpdateClusterRequest;
import com.filetransfer.onboarding.dto.response.ClusterInfoResponse;
import com.filetransfer.onboarding.dto.response.ServiceRegistrationResponse;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.cluster.RedisServiceRegistry;
import com.filetransfer.shared.cluster.ServiceInstance;
import com.filetransfer.shared.entity.core.ClusterNode;
import com.filetransfer.shared.entity.core.ServiceRegistration;
import com.filetransfer.shared.enums.ClusterCommunicationMode;
import com.filetransfer.shared.repository.core.ClusterNodeRepository;
import com.filetransfer.shared.repository.core.ServiceRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cluster management API — allows admins to view cluster topology,
 * configure communication mode, and manage cluster nodes.
 *
 * GET  /api/clusters                         — list all known clusters with service counts
 * GET  /api/clusters/{clusterId}             — cluster details + services
 * PUT  /api/clusters/{clusterId}             — update cluster settings (name, mode, region)
 * GET  /api/clusters/communication-mode      — get current communication mode
 * PUT  /api/clusters/communication-mode      — change communication mode for a cluster
 * GET  /api/clusters/topology                — full topology: all clusters + all services
 */
@RestController
@RequestMapping("/api/clusters")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class ClusterController {

    private final ClusterService clusterService;
    private final ClusterNodeRepository clusterNodeRepository;
    private final ServiceRegistrationRepository serviceRegistrationRepository;

    /** Redis-backed real-time registry — null if Redis is not on the classpath. */
    @Autowired(required = false)
    @Nullable
    private RedisServiceRegistry redisServiceRegistry;

    @GetMapping
    public List<ClusterInfoResponse> listClusters() {
        List<ClusterNode> nodes = clusterNodeRepository.findByActiveTrue();
        Map<String, Long> counts = clusterService.getClusterServiceCounts();

        return nodes.stream().map(node -> ClusterInfoResponse.builder()
                .id(node.getId())
                .clusterId(node.getClusterId())
                .displayName(node.getDisplayName())
                .description(node.getDescription())
                .communicationMode(node.getCommunicationMode())
                .region(node.getRegion())
                .environment(node.getEnvironment())
                .apiEndpoint(node.getApiEndpoint())
                .active(node.isActive())
                .registeredAt(node.getRegisteredAt())
                .updatedAt(node.getUpdatedAt())
                .serviceCount(counts.getOrDefault(node.getClusterId(), 0L))
                .build()
        ).collect(Collectors.toList());
    }

    @GetMapping("/{clusterId}")
    public ResponseEntity<ClusterInfoResponse> getCluster(@PathVariable String clusterId) {
        return clusterNodeRepository.findByClusterId(clusterId)
                .map(node -> {
                    List<ServiceRegistration> services =
                            serviceRegistrationRepository.findByClusterIdAndActiveTrue(clusterId);
                    return ResponseEntity.ok(ClusterInfoResponse.builder()
                            .id(node.getId())
                            .clusterId(node.getClusterId())
                            .displayName(node.getDisplayName())
                            .description(node.getDescription())
                            .communicationMode(node.getCommunicationMode())
                            .region(node.getRegion())
                            .environment(node.getEnvironment())
                            .apiEndpoint(node.getApiEndpoint())
                            .active(node.isActive())
                            .registeredAt(node.getRegisteredAt())
                            .updatedAt(node.getUpdatedAt())
                            .serviceCount(services.size())
                            .services(services.stream().map(this::toServiceResponse).collect(Collectors.toList()))
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{clusterId}")
    public ResponseEntity<ClusterInfoResponse> updateCluster(@PathVariable String clusterId,
                                                              @RequestBody UpdateClusterRequest request) {
        return clusterNodeRepository.findByClusterId(clusterId)
                .map(node -> {
                    if (request.getDisplayName() != null) node.setDisplayName(request.getDisplayName());
                    if (request.getDescription() != null) node.setDescription(request.getDescription());
                    if (request.getRegion() != null) node.setRegion(request.getRegion());
                    if (request.getApiEndpoint() != null) node.setApiEndpoint(request.getApiEndpoint());
                    if (request.getCommunicationMode() != null) {
                        ClusterCommunicationMode mode = ClusterCommunicationMode.valueOf(
                                request.getCommunicationMode().toUpperCase());
                        node.setCommunicationMode(mode);
                        // If updating this instance's cluster, apply immediately
                        if (clusterId.equals(clusterService.getClusterId())) {
                            clusterService.setCommunicationMode(mode);
                        }
                    }
                    clusterNodeRepository.save(node);
                    long count = serviceRegistrationRepository.findByClusterIdAndActiveTrue(clusterId).size();
                    return ResponseEntity.ok(toClusterResponse(node, count));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/communication-mode")
    public ResponseEntity<Map<String, String>> getCommunicationMode() {
        return ResponseEntity.ok(Map.of(
                "clusterId", clusterService.getClusterId(),
                "communicationMode", clusterService.getCommunicationMode().name()
        ));
    }

    @PutMapping("/communication-mode")
    public ResponseEntity<Map<String, String>> setCommunicationMode(@RequestBody Map<String, String> body) {
        String modeStr = body.get("communicationMode");
        if (modeStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "communicationMode is required"));
        }
        ClusterCommunicationMode mode;
        try {
            mode = ClusterCommunicationMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid mode. Use WITHIN_CLUSTER or CROSS_CLUSTER"));
        }

        // Update in-memory for this instance
        clusterService.setCommunicationMode(mode);

        // Persist to cluster_nodes table
        clusterNodeRepository.findByClusterId(clusterService.getClusterId())
                .ifPresent(node -> {
                    node.setCommunicationMode(mode);
                    clusterNodeRepository.save(node);
                });

        log.info("Communication mode set to {} for cluster {}", mode, clusterService.getClusterId());
        return ResponseEntity.ok(Map.of(
                "clusterId", clusterService.getClusterId(),
                "communicationMode", mode.name()
        ));
    }

    @GetMapping("/topology")
    public Map<String, Object> getTopology() {
        Map<String, List<ServiceRegistration>> byCluster = clusterService.getServicesByCluster();

        List<Map<String, Object>> clusters = byCluster.entrySet().stream().map(entry -> {
            String cid = entry.getKey();
            List<ServiceRegistration> services = entry.getValue();
            ClusterNode node = clusterNodeRepository.findByClusterId(cid).orElse(null);

            Map<String, Object> cluster = new java.util.LinkedHashMap<>();
            cluster.put("clusterId", cid);
            cluster.put("displayName", node != null ? node.getDisplayName() : cid);
            cluster.put("communicationMode", node != null ? node.getCommunicationMode().name() : "UNKNOWN");
            cluster.put("region", node != null ? node.getRegion() : null);
            cluster.put("serviceCount", services.size());
            cluster.put("services", services.stream().map(s -> Map.of(
                    "serviceType", s.getServiceType().name(),
                    "host", s.getHost(),
                    "port", s.getControlPort(),
                    "active", s.isActive(),
                    "instanceId", s.getServiceInstanceId()
            )).collect(Collectors.toList()));
            return cluster;
        }).collect(Collectors.toList());

        return Map.of(
                "thisCluster", clusterService.getClusterId(),
                "thisInstance", clusterService.getServiceInstanceId(),
                "communicationMode", clusterService.getCommunicationMode().name(),
                "clusters", clusters
        );
    }

    private ClusterInfoResponse toClusterResponse(ClusterNode node, long serviceCount) {
        return ClusterInfoResponse.builder()
                .id(node.getId())
                .clusterId(node.getClusterId())
                .displayName(node.getDisplayName())
                .description(node.getDescription())
                .communicationMode(node.getCommunicationMode())
                .region(node.getRegion())
                .environment(node.getEnvironment())
                .apiEndpoint(node.getApiEndpoint())
                .active(node.isActive())
                .registeredAt(node.getRegisteredAt())
                .updatedAt(node.getUpdatedAt())
                .serviceCount(serviceCount)
                .build();
    }

    /**
     * Live cluster view — reads from Redis presence keys (TTL-based, real-time).
     * Unlike GET /api/clusters which reads from PostgreSQL (up to 30s stale),
     * this endpoint reflects the actual live state: replicas appear within seconds
     * of starting and disappear within 30s of crashing.
     */
    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> getLiveRegistry() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (redisServiceRegistry == null) {
            result.put("available", false);
            result.put("message", "Redis service registry not active on this instance");
            return ResponseEntity.ok(result);
        }
        java.util.List<ServiceInstance> all = redisServiceRegistry.getAllInstances();
        Map<String, java.util.List<ServiceInstance>> byType = new java.util.LinkedHashMap<>();
        for (ServiceInstance si : all) {
            byType.computeIfAbsent(si.getServiceType(), k -> new java.util.ArrayList<>()).add(si);
        }
        result.put("available",      true);
        result.put("totalInstances", all.size());
        result.put("byServiceType",  byType);
        result.put("generatedAt",    java.time.Instant.now().toString());
        return ResponseEntity.ok(result);
    }

    private ServiceRegistrationResponse toServiceResponse(ServiceRegistration r) {
        return ServiceRegistrationResponse.builder()
                .id(r.getId())
                .serviceInstanceId(r.getServiceInstanceId())
                .clusterId(r.getClusterId())
                .serviceType(r.getServiceType())
                .host(r.getHost())
                .controlPort(r.getControlPort())
                .active(r.isActive())
                .lastHeartbeat(r.getLastHeartbeat())
                .registeredAt(r.getRegisteredAt())
                .build();
    }
}
