package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.response.ServiceRegistrationResponse;
import com.filetransfer.shared.entity.core.ServiceRegistration;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.ServiceRegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only view of the service registry (admin only).
 *
 * GET /api/service-registry              — list all active registrations
 * GET /api/service-registry?type=SFTP   — filter by service type
 */
@RestController
@RequestMapping("/api/service-registry")
@RequiredArgsConstructor
public class ServiceRegistryController {

    private final ServiceRegistrationRepository registrationRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<ServiceRegistrationResponse> list(@RequestParam(required = false) ServiceType type) {
        List<ServiceRegistration> registrations = type != null
                ? registrationRepository.findByServiceTypeAndActiveTrue(type)
                : registrationRepository.findAll();
        return registrations.stream().map(this::toResponse).collect(Collectors.toList());
    }

    private ServiceRegistrationResponse toResponse(ServiceRegistration r) {
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
