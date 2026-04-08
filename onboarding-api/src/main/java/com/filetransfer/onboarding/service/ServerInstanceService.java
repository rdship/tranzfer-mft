package com.filetransfer.onboarding.service;

import com.filetransfer.onboarding.dto.request.CreateServerInstanceRequest;
import com.filetransfer.onboarding.dto.request.UpdateServerInstanceRequest;
import com.filetransfer.onboarding.dto.response.ServerInstanceResponse;
import com.filetransfer.shared.client.DmzProxyClient;
import com.filetransfer.shared.entity.FolderTemplate;
import com.filetransfer.shared.entity.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.FolderTemplateRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerInstanceService {

    private final ServerInstanceRepository repository;
    private final FolderTemplateRepository folderTemplateRepository;
    private final DmzProxyClient dmzProxyClient;

    public List<ServerInstanceResponse> listAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    public List<ServerInstanceResponse> listActive() {
        return repository.findByActiveTrue().stream().map(this::toResponse).toList();
    }

    public List<ServerInstanceResponse> listByProtocol(Protocol protocol, boolean activeOnly) {
        if (activeOnly) {
            return repository.findByProtocolAndActiveTrue(protocol).stream().map(this::toResponse).toList();
        }
        return repository.findByProtocol(protocol).stream().map(this::toResponse).toList();
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

        ServerInstance instance = ServerInstance.builder()
                .instanceId(request.getInstanceId())
                .protocol(request.getProtocol())
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
                .folderTemplate(resolveTemplate(request.getFolderTemplateId()))
                .defaultStorageMode(request.getDefaultStorageMode() != null ? request.getDefaultStorageMode() : "PHYSICAL")
                .build();

        // Persist proxy QoS policy
        applyProxyQoS(instance, request.getProxyQos());

        repository.save(instance);

        // Auto-create proxy mapping when proxy is configured
        syncProxyMapping(instance);

        return toResponse(instance);
    }

    @Transactional
    public ServerInstanceResponse update(UUID id, UpdateServerInstanceRequest request) {
        ServerInstance instance = findById(id);

        if (request.getProtocol() != null) instance.setProtocol(request.getProtocol());
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
        if (request.isClearFolderTemplate()) {
            instance.setFolderTemplate(null);
        } else if (request.getFolderTemplateId() != null) {
            instance.setFolderTemplate(resolveTemplate(request.getFolderTemplateId()));
        }
        if (request.getDefaultStorageMode() != null) instance.setDefaultStorageMode(request.getDefaultStorageMode());

        // Update proxy QoS policy
        if (request.getProxyQos() != null) {
            applyProxyQoS(instance, request.getProxyQos());
        }

        repository.save(instance);

        // Sync proxy mapping if proxy config changed
        syncProxyMapping(instance);

        return toResponse(instance);
    }

    @Transactional
    public void delete(UUID id) {
        ServerInstance instance = findById(id);
        instance.setActive(false);
        repository.save(instance);
    }

    private ServerInstance findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Server instance not found: " + id));
    }

    private ServerInstanceResponse toResponse(ServerInstance i) {
        FolderTemplate ft = i.getFolderTemplate();
        return ServerInstanceResponse.builder()
                .id(i.getId())
                .instanceId(i.getInstanceId())
                .protocol(i.getProtocol())
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
                .folderTemplateId(ft != null ? ft.getId() : null)
                .folderTemplateName(ft != null ? ft.getName() : null)
                .defaultStorageMode(i.getDefaultStorageMode())
                .active(i.isActive())
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .clientHost(i.getClientConnectionHost())
                .clientPort(i.getClientConnectionPort())
                .proxyQosEnabled(i.isProxyQosEnabled())
                .proxyQosMaxBytesPerSecond(i.getProxyQosMaxBytesPerSecond())
                .proxyQosPerConnectionMaxBytesPerSecond(i.getProxyQosPerConnectionMaxBytesPerSecond())
                .proxyQosPriority(i.getProxyQosPriority())
                .proxyQosBurstAllowancePercent(i.getProxyQosBurstAllowancePercent())
                .build();
    }

    /**
     * Apply proxy QoS config from request to entity.
     */
    private void applyProxyQoS(ServerInstance instance, CreateServerInstanceRequest.ProxyQoSConfig qos) {
        if (qos == null) return;
        instance.setProxyQosEnabled(qos.isEnabled());
        if (qos.getMaxBytesPerSecond() != null) {
            instance.setProxyQosMaxBytesPerSecond(qos.getMaxBytesPerSecond());
        }
        if (qos.getPerConnectionMaxBytesPerSecond() != null) {
            instance.setProxyQosPerConnectionMaxBytesPerSecond(qos.getPerConnectionMaxBytesPerSecond());
        }
        if (qos.getPriority() != null) {
            instance.setProxyQosPriority(qos.getPriority());
        }
        if (qos.getBurstAllowancePercent() != null) {
            instance.setProxyQosBurstAllowancePercent(qos.getBurstAllowancePercent());
        }
    }

    /**
     * Auto-create/update the DMZ proxy mapping when a server instance uses the proxy.
     * Best-effort: logs warning on failure but does not block instance creation.
     */
    private void syncProxyMapping(ServerInstance instance) {
        if (!instance.isUseProxy() || instance.getProxyHost() == null || instance.getProxyPort() == null) {
            return;
        }
        try {
            Map<String, Object> qosPolicy = new LinkedHashMap<>();
            qosPolicy.put("enabled", instance.isProxyQosEnabled());
            if (instance.getProxyQosMaxBytesPerSecond() != null) {
                qosPolicy.put("maxBytesPerSecond", instance.getProxyQosMaxBytesPerSecond());
            }
            if (instance.getProxyQosPerConnectionMaxBytesPerSecond() != null) {
                qosPolicy.put("perConnectionMaxBytesPerSecond", instance.getProxyQosPerConnectionMaxBytesPerSecond());
            }
            qosPolicy.put("priority", instance.getProxyQosPriority() != null ? instance.getProxyQosPriority() : 5);
            qosPolicy.put("burstAllowancePercent", instance.getProxyQosBurstAllowancePercent() != null ? instance.getProxyQosBurstAllowancePercent() : 20);

            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("name", instance.getInstanceId());
            mapping.put("listenPort", instance.getProxyPort());
            mapping.put("targetHost", instance.getInternalHost());
            mapping.put("targetPort", instance.getInternalPort());
            mapping.put("active", true);
            mapping.put("qosPolicy", qosPolicy);

            dmzProxyClient.createMapping(mapping);
            log.info("Proxy mapping synced for instance={} listenPort={} qosEnabled={}",
                    instance.getInstanceId(), instance.getProxyPort(), instance.isProxyQosEnabled());
        } catch (Exception e) {
            log.warn("Failed to sync proxy mapping for instance={}: {}",
                    instance.getInstanceId(), e.getMessage());
        }
    }

    private FolderTemplate resolveTemplate(UUID templateId) {
        if (templateId == null) return null;
        return folderTemplateRepository.findById(templateId)
                .orElseThrow(() -> new NoSuchElementException("Folder template not found: " + templateId));
    }
}
