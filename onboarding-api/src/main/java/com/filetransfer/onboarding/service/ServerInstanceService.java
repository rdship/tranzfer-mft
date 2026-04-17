package com.filetransfer.onboarding.service;

import com.filetransfer.onboarding.dto.request.CreateServerInstanceRequest;
import com.filetransfer.onboarding.dto.request.UpdateServerInstanceRequest;
import com.filetransfer.onboarding.dto.response.ServerInstanceResponse;
import com.filetransfer.shared.client.DmzProxyClient;
import com.filetransfer.shared.dto.ServerInstanceChangeEvent;
import com.filetransfer.shared.entity.core.FolderTemplate;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.outbox.OutboxWriter;
import com.filetransfer.shared.repository.core.FolderTemplateRepository;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
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
    private final OutboxWriter outboxWriter;

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
        // Pre-check port collision so we can return clean 409 + suggestions instead
        // of relying on the DB constraint → DataIntegrityViolationException.
        repository.findByInternalHostAndInternalPortAndActiveTrue(request.getInternalHost(), request.getInternalPort())
                .ifPresent(conflict -> {
                    throw new com.filetransfer.onboarding.exception.PortConflictException(
                            request.getInternalHost(),
                            request.getInternalPort(),
                            suggestAlternativePorts(request.getInternalHost(), request.getInternalPort(), 5));
                });

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
                .defaultStorageMode(request.getDefaultStorageMode() != null ? request.getDefaultStorageMode() : "VIRTUAL")
                .active(request.getActive() == null || request.getActive())
                .build();

        // Persist proxy QoS policy
        applyProxyQoS(instance, request.getProxyQos());

        repository.save(instance);
        publishChange(instance, ServerInstanceChangeEvent.ChangeType.CREATED);

        // Auto-create proxy mapping when proxy is configured
        syncProxyMapping(instance);

        return toResponse(instance);
    }

    @Transactional
    public ServerInstanceResponse update(UUID id, UpdateServerInstanceRequest request) {
        ServerInstance instance = findById(id);

        // Port conflict pre-check on update (skip if unchanged).
        if (request.getInternalPort() != null || request.getInternalHost() != null) {
            String newHost = request.getInternalHost() != null ? request.getInternalHost() : instance.getInternalHost();
            int newPort = request.getInternalPort() != null ? request.getInternalPort() : instance.getInternalPort();
            boolean moved = !newHost.equals(instance.getInternalHost()) || newPort != instance.getInternalPort();
            if (moved) {
                repository.findByInternalHostAndInternalPortAndActiveTrue(newHost, newPort)
                        .filter(conflict -> !conflict.getId().equals(id))
                        .ifPresent(conflict -> {
                            throw new com.filetransfer.onboarding.exception.PortConflictException(
                                    newHost, newPort,
                                    suggestAlternativePorts(newHost, newPort, 5));
                        });
            }
        }

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

        // Advanced per-server config (V44)
        if (request.getProxyGroupName()          != null) instance.setProxyGroupName(request.getProxyGroupName());
        if (request.getSecurityTier()            != null) instance.setSecurityTier(request.getSecurityTier());
        if (request.getSshBannerMessage()        != null) instance.setSshBannerMessage(request.getSshBannerMessage());
        if (request.getMaxAuthAttempts()         != null) instance.setMaxAuthAttempts(request.getMaxAuthAttempts());
        if (request.getIdleTimeoutSeconds()      != null) instance.setIdleTimeoutSeconds(request.getIdleTimeoutSeconds());
        if (request.getSessionMaxDurationSeconds()!= null)instance.setSessionMaxDurationSeconds(request.getSessionMaxDurationSeconds());
        if (request.getAllowedCiphers()          != null) instance.setAllowedCiphers(request.getAllowedCiphers());
        if (request.getAllowedMacs()             != null) instance.setAllowedMacs(request.getAllowedMacs());
        if (request.getAllowedKex()              != null) instance.setAllowedKex(request.getAllowedKex());
        if (request.getMaintenanceMode()         != null) instance.setMaintenanceMode(request.getMaintenanceMode());
        if (request.getMaintenanceMessage()      != null) instance.setMaintenanceMessage(request.getMaintenanceMessage());

        boolean activeChanged = request.getActive() != null && request.getActive() != instance.isActive();
        repository.save(instance);

        // Choose event type: ACTIVATED/DEACTIVATED is more specific than UPDATED for bind/unbind intent.
        ServerInstanceChangeEvent.ChangeType type;
        if (activeChanged) {
            type = instance.isActive()
                    ? ServerInstanceChangeEvent.ChangeType.ACTIVATED
                    : ServerInstanceChangeEvent.ChangeType.DEACTIVATED;
        } else {
            type = ServerInstanceChangeEvent.ChangeType.UPDATED;
        }
        publishChange(instance, type);

        // Sync proxy mapping if proxy config changed
        syncProxyMapping(instance);

        return toResponse(instance);
    }

    @Transactional
    public void delete(UUID id) {
        ServerInstance instance = findById(id);
        instance.setActive(false);
        repository.save(instance);
        publishChange(instance, ServerInstanceChangeEvent.ChangeType.DELETED);
    }

    /**
     * Admin-triggered rebind — republishes an UPDATED event so the owning
     * protocol service will unbind + bind. Used to recover from BIND_FAILED
     * without toggling active off/on. Idempotent on BOUND listeners.
     */
    @Transactional
    public ServerInstanceResponse requestRebind(UUID id) {
        ServerInstance instance = findById(id);
        publishChange(instance, ServerInstanceChangeEvent.ChangeType.UPDATED);
        log.info("Rebind requested for server instance {} ({})", instance.getInstanceId(), id);
        return toResponse(instance);
    }

    private void publishChange(ServerInstance instance, ServerInstanceChangeEvent.ChangeType type) {
        ServerInstanceChangeEvent event = new ServerInstanceChangeEvent(
                instance.getId(),
                instance.getInstanceId(),
                instance.getProtocol(),
                instance.getInternalHost(),
                instance.getInternalPort(),
                instance.isActive(),
                type);
        outboxWriter.write(
                "server_instance",
                instance.getId().toString(),
                type.name(),
                event.routingKey(),
                event);
    }

    private ServerInstance findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Server instance not found: " + id));
    }

    /**
     * Return up to {@code count} free ports near the requested port (searching
     * requested+1..requested+20 then requested-1..requested-5). Keeps results
     * inside the 1024-65535 unprivileged range.
     */
    public List<Integer> suggestAlternativePorts(String host, int requestedPort, int count) {
        int low = Math.max(1024, requestedPort - 5);
        int high = Math.min(65535, requestedPort + 20);
        Set<Integer> used = new HashSet<>(repository.findUsedPortsInRange(host, low, high));
        List<Integer> suggestions = new ArrayList<>();
        // Prefer ports AFTER the requested one (more intuitive for admins).
        for (int p = requestedPort + 1; p <= high && suggestions.size() < count; p++) {
            if (!used.contains(p)) suggestions.add(p);
        }
        for (int p = requestedPort - 1; p >= low && suggestions.size() < count; p--) {
            if (!used.contains(p)) suggestions.add(p);
        }
        return suggestions;
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
                // Advanced per-server config (V44)
                .proxyGroupName(i.getProxyGroupName())
                .securityTier(i.getSecurityTier())
                .sshBannerMessage(i.getSshBannerMessage())
                .maxAuthAttempts(i.getMaxAuthAttempts())
                .idleTimeoutSeconds(i.getIdleTimeoutSeconds())
                .sessionMaxDurationSeconds(i.getSessionMaxDurationSeconds())
                .allowedCiphers(i.getAllowedCiphers())
                .allowedMacs(i.getAllowedMacs())
                .allowedKex(i.getAllowedKex())
                .maintenanceMode(i.isMaintenanceMode())
                .maintenanceMessage(i.getMaintenanceMessage())
                .assignedAccountCount(0L)  // populated by controller when needed
                // Runtime bind state (V64)
                .bindState(i.getBindState())
                .bindError(i.getBindError())
                .lastBindAttemptAt(i.getLastBindAttemptAt())
                .boundNode(i.getBoundNode())
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
