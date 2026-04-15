package com.filetransfer.as2.controller;

import com.filetransfer.shared.repository.integration.As2PartnershipRepository;
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

    private final As2PartnershipRepository partnershipRepository;

    @Value("${as2.instance-id:#{null}}")
    private String instanceId;

    @GetMapping("/health")
    public Map<String, Object> health() {
        var map = new LinkedHashMap<String, Object>();
        map.put("status", "UP");
        map.put("service", "as2-service");
        map.put("activePartnerships", partnershipRepository.findByActiveTrue().size());
        map.put("as2Partnerships", partnershipRepository.findByProtocolAndActiveTrue("AS2").size());
        map.put("as4Partnerships", partnershipRepository.findByProtocolAndActiveTrue("AS4").size());
        if (instanceId != null) map.put("instanceId", instanceId);
        return map;
    }
}
