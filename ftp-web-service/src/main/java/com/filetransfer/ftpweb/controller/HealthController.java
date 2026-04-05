package com.filetransfer.ftpweb.controller;

import com.filetransfer.shared.cluster.ClusterContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class HealthController {

    private final ClusterContext clusterContext;

    @Value("${ftpweb.instance-id:#{null}}")
    private String instanceId;

    @GetMapping("/health")
    public Map<String, Object> health() {
        var map = new LinkedHashMap<String, Object>();
        map.put("status", "UP");
        map.put("serviceInstanceId", clusterContext.getServiceInstanceId());
        map.put("clusterId", clusterContext.getClusterId());
        map.put("serviceType", "FTP_WEB");
        if (instanceId != null) map.put("instanceId", instanceId);
        return map;
    }
}
