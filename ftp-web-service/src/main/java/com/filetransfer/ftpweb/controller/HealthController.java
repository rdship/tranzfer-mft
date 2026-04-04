package com.filetransfer.ftpweb.controller;

import com.filetransfer.shared.cluster.ClusterContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class HealthController {

    private final ClusterContext clusterContext;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "serviceInstanceId", clusterContext.getServiceInstanceId(),
                "clusterId", clusterContext.getClusterId(),
                "serviceType", "FTP_WEB"
        );
    }
}
