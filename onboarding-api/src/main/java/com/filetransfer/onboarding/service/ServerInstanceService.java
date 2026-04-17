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
                .ftpPassivePortFrom(request.getFtpPassivePortFrom())
                .ftpPassivePortTo(request.getFtpPassivePortTo())
                .ftpTlsCertAlias(request.getFtpTlsCertAlias())
                .ftpProtRequired(normalizeProt(request.getFtpProtRequired()))
                .ftpBannerMessage(request.getFtpBannerMessage())
                .ftpImplicitTls(request.getFtpImplicitTls())
                .active(request.getActive() == null || request.getActive())
                .build();

        validateFtpFields(instance);

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

        // FTP advanced (V87) — null means "leave unchanged"
        if (request.getFtpPassivePortFrom()      != null) instance.setFtpPassivePortFrom(request.getFtpPassivePortFrom());
        if (request.getFtpPassivePortTo()        != null) instance.setFtpPassivePortTo(request.getFtpPassivePortTo());
        if (request.getFtpTlsCertAlias()         != null) instance.setFtpTlsCertAlias(request.getFtpTlsCertAlias());
        if (request.getFtpProtRequired()         != null) instance.setFtpProtRequired(normalizeProt(request.getFtpProtRequired()));
        if (request.getFtpBannerMessage()        != null) instance.setFtpBannerMessage(request.getFtpBannerMessage());
        if (request.getFtpImplicitTls()          != null) instance.setFtpImplicitTls(request.getFtpImplicitTls());

        validateFtpFields(instance);

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
        // Tear down DMZ proxy mapping too — otherwise mapping leaks and DMZ keeps
        // routing traffic to a port nobody is listening on.
        syncProxyMapping(instance);
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
        return suggestAlternativePorts(host, requestedPort, count, null);
    }

    /**
     * Protocol-aware variant: when {@code protocol} is non-null, well-known
     * ports of OTHER protocols are suppressed from the suggestions so an admin
     * doesn't accidentally pick 2222 for an FTP listener or 21 for an SFTP one.
     */
    public List<Integer> suggestAlternativePorts(String host, int requestedPort, int count, Protocol protocol) {
        int low = Math.max(1024, requestedPort - 5);
        int high = Math.min(65535, requestedPort + 20);
        Set<Integer> used = new HashSet<>(repository.findUsedPortsInRange(host, low, high));
        Set<Integer> blocked = wellKnownPortsOfOtherProtocols(protocol);
        List<Integer> suggestions = new ArrayList<>();
        // Prefer ports AFTER the requested one (more intuitive for admins).
        for (int p = requestedPort + 1; p <= high && suggestions.size() < count; p++) {
            if (!used.contains(p) && !blocked.contains(p)) suggestions.add(p);
        }
        for (int p = requestedPort - 1; p >= low && suggestions.size() < count; p--) {
            if (!used.contains(p) && !blocked.contains(p)) suggestions.add(p);
        }
        return suggestions;
    }

    /**
     * Well-known ports reserved for protocols OTHER than {@code protocol}.
     * Null/unknown → empty set (no filtering).
     */
    private static Set<Integer> wellKnownPortsOfOtherProtocols(Protocol protocol) {
        if (protocol == null) return Set.of();
        Set<Integer> sftp  = Set.of(22, 2222);
        Set<Integer> ftp   = Set.of(21, 990);
        Set<Integer> https = Set.of(443, 8443);
        Set<Integer> as2   = Set.of(10080, 10443);
        return switch (protocol) {
            case SFTP    -> union(ftp, https, as2);
            case FTP     -> union(sftp, https, as2);
            case FTP_WEB -> union(sftp, ftp, as2);
            case HTTPS   -> union(sftp, ftp, as2);
            case AS2, AS4 -> union(sftp, ftp, https);
        };
    }

    @SafeVarargs
    private static Set<Integer> union(Set<Integer>... sets) {
        Set<Integer> out = new HashSet<>();
        for (Set<Integer> s : sets) out.addAll(s);
        return out;
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
                // FTP advanced (V87)
                .ftpPassivePortFrom(i.getFtpPassivePortFrom())
                .ftpPassivePortTo(i.getFtpPassivePortTo())
                .ftpTlsCertAlias(i.getFtpTlsCertAlias())
                .ftpProtRequired(i.getFtpProtRequired())
                .ftpBannerMessage(i.getFtpBannerMessage())
                .ftpImplicitTls(i.getFtpImplicitTls())
                .build();
    }

    /** Upper-cases PROT to canonical form; accepts null/blank. */
    private static String normalizeProt(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        return t.toUpperCase();
    }

    /**
     * Enforce FTP advanced field invariants at the service layer so validation
     * surfaces a clean 400 instead of a DB constraint violation. Mirrors the
     * CHECK constraints added by V87.
     */
    private static void validateFtpFields(ServerInstance si) {
        Integer from = si.getFtpPassivePortFrom();
        Integer to   = si.getFtpPassivePortTo();
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException(
                "ftpPassivePortFrom and ftpPassivePortTo must both be set or both null");
        }
        if (from != null) {
            if (from < 1024 || to > 65535 || from > to) {
                throw new IllegalArgumentException(
                    "FTP passive port range must satisfy 1024 <= from <= to <= 65535");
            }
        }
        String prot = si.getFtpProtRequired();
        if (prot != null && !prot.equals("NONE") && !prot.equals("C") && !prot.equals("P")) {
            throw new IllegalArgumentException("ftpProtRequired must be one of NONE, C, P");
        }
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
     * Reconcile the DMZ proxy mapping with the desired state of a ServerInstance.
     * Full lifecycle — not just create:
     *   useProxy=true  + active=true  → createMapping (idempotent; ignore "already exists")
     *   useProxy=false OR active=false → deleteMapping (idempotent; ignore "not found")
     *
     * <p>DMZ proxy is deliberately isolated from the rest of the platform (no
     * shared module, no AMQP, no DB). REST sync from here is the ONLY channel
     * that keeps the proxy's runtime mappings aligned with ServerInstance state,
     * so we retry with exponential backoff before surfacing the failure.</p>
     */
    private void syncProxyMapping(ServerInstance instance) {
        boolean proxyDesired = instance.isUseProxy()
                && instance.isActive()
                && instance.getProxyHost() != null
                && instance.getProxyPort() != null;

        if (proxyDesired) {
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

            callWithRetry("createMapping", () -> dmzProxyClient.createMapping(mapping),
                    "Proxy mapping CREATE synced for " + instance.getInstanceId()
                            + " listenPort=" + instance.getProxyPort());
        } else {
            // Listener no longer uses proxy (or was deleted/deactivated) — tear down any existing mapping.
            callWithRetry("deleteMapping", () -> { dmzProxyClient.deleteMapping(instance.getInstanceId()); return null; },
                    "Proxy mapping DELETE synced for " + instance.getInstanceId());
        }
    }

    /**
     * Three attempts with 500ms → 1s → 2s backoff. Idempotent-exception
     * patterns ("already exists" on create, "not found" on delete) are
     * tolerated on first try and NOT retried. Terminal failure logs ERROR.
     */
    private <T> void callWithRetry(String op, java.util.function.Supplier<T> call, String successMsg) {
        Exception last = null;
        long backoffMs = 500L;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                call.get();
                log.info(successMsg + " (attempt=" + attempt + ")");
                return;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (msg.contains("already exists") || msg.contains("not found") || msg.contains("404")) {
                    log.debug("Proxy {} idempotent no-op: {}", op, e.getMessage());
                    return;
                }
                last = e;
                log.warn("Proxy {} attempt {} failed: {}", op, attempt, e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    backoffMs *= 2;
                }
            }
        }
        log.error("Proxy {} FAILED after 3 attempts — DMZ proxy is out of sync with ServerInstance state. Last error: {}",
                op, last != null ? last.getMessage() : "unknown");
    }

    private FolderTemplate resolveTemplate(UUID templateId) {
        if (templateId == null) return null;
        return folderTemplateRepository.findById(templateId)
                .orElseThrow(() -> new NoSuchElementException("Folder template not found: " + templateId));
    }
}
