package com.filetransfer.onboarding.service;

import com.filetransfer.onboarding.dto.request.CreateServerInstanceRequest;
import com.filetransfer.onboarding.dto.request.UpdateServerInstanceRequest;
import com.filetransfer.onboarding.dto.response.ServerInstanceResponse;
import com.filetransfer.shared.entity.SftpServerInstance;
import com.filetransfer.shared.repository.SftpServerInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServerInstanceService {

    private final SftpServerInstanceRepository repository;

    public List<ServerInstanceResponse> listAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    public List<ServerInstanceResponse> listActive() {
        return repository.findByActiveTrue().stream().map(this::toResponse).toList();
    }

    public ServerInstanceResponse getById(UUID id) {
        return toResponse(findById(id));
    }

    public ServerInstanceResponse getByInstanceId(String instanceId) {
        return toResponse(repository.findByInstanceId(instanceId)
                .orElseThrow(() -> new NoSuchElementException("Server instance not found: " + instanceId)));
    }

    @Transactional
    public ServerInstanceResponse create(CreateServerInstanceRequest request) {
        if (repository.existsByInstanceId(request.getInstanceId())) {
            throw new IllegalArgumentException("Instance ID already exists: " + request.getInstanceId());
        }

        SftpServerInstance instance = SftpServerInstance.builder()
                .instanceId(request.getInstanceId())
                .name(request.getName())
                .description(request.getDescription())
                .internalHost(request.getInternalHost())
                .internalPort(request.getInternalPort())
                .externalHost(request.getExternalHost())
                .externalPort(request.getExternalPort())
                .useProxy(request.isUseProxy())
                .proxyHost(request.getProxyHost())
                .proxyPort(request.getProxyPort())
                .maxConnections(request.getMaxConnections() != null ? request.getMaxConnections() : 500)
                .build();

        repository.save(instance);
        return toResponse(instance);
    }

    @Transactional
    public ServerInstanceResponse update(UUID id, UpdateServerInstanceRequest request) {
        SftpServerInstance instance = findById(id);

        if (request.getName() != null) instance.setName(request.getName());
        if (request.getDescription() != null) instance.setDescription(request.getDescription());
        if (request.getInternalHost() != null) instance.setInternalHost(request.getInternalHost());
        if (request.getInternalPort() != null) instance.setInternalPort(request.getInternalPort());
        if (request.getExternalHost() != null) instance.setExternalHost(request.getExternalHost());
        if (request.getExternalPort() != null) instance.setExternalPort(request.getExternalPort());
        if (request.getUseProxy() != null) instance.setUseProxy(request.getUseProxy());
        if (request.getProxyHost() != null) instance.setProxyHost(request.getProxyHost());
        if (request.getProxyPort() != null) instance.setProxyPort(request.getProxyPort());
        if (request.getMaxConnections() != null) instance.setMaxConnections(request.getMaxConnections());
        if (request.getActive() != null) instance.setActive(request.getActive());

        repository.save(instance);
        return toResponse(instance);
    }

    @Transactional
    public void delete(UUID id) {
        SftpServerInstance instance = findById(id);
        instance.setActive(false);
        repository.save(instance);
    }

    private SftpServerInstance findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Server instance not found: " + id));
    }

    private ServerInstanceResponse toResponse(SftpServerInstance i) {
        return ServerInstanceResponse.builder()
                .id(i.getId())
                .instanceId(i.getInstanceId())
                .name(i.getName())
                .description(i.getDescription())
                .internalHost(i.getInternalHost())
                .internalPort(i.getInternalPort())
                .externalHost(i.getExternalHost())
                .externalPort(i.getExternalPort())
                .useProxy(i.isUseProxy())
                .proxyHost(i.getProxyHost())
                .proxyPort(i.getProxyPort())
                .maxConnections(i.getMaxConnections())
                .active(i.isActive())
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .clientHost(i.getClientConnectionHost())
                .clientPort(i.getClientConnectionPort())
                .build();
    }
}
