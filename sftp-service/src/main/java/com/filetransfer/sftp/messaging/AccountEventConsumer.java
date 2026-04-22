package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.cache.PartnerCache;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
import com.filetransfer.sftp.service.CredentialService;
import com.filetransfer.sftp.session.ConnectionManager;
import com.filetransfer.sftp.throttle.BandwidthThrottleManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * <p><b>R134X — Sprint 7 Phase B:</b> OUTBOX-ONLY. The legacy
 * {@code @RabbitListener}, Queue/Exchange/Binding {@code @Bean}
 * declarations, and the dual-consume boot log were deleted.
 * {@link UnifiedOutboxPoller} delivers {@code account.*} rows
 * directly; {@link com.filetransfer.shared.cache.PartnerCacheEvictionListener}
 * co-registers on the same prefix via R134V's multi-handler cap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final CredentialService credentialService;
    private final BandwidthThrottleManager bandwidthThrottleManager;
    private final ConnectionManager connectionManager;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private ObjectMapper injectedObjectMapper;

    /** Phase 1: evict partner cache on account events (partner may have changed). */
    @Autowired(required = false)
    private PartnerCache partnerCache;

    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @jakarta.annotation.PostConstruct
    void subscribeFabricEvents() {
        if (eventFabricBridge == null || injectedObjectMapper == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "sftp-service");
        try {
            // Shared group — load-balance account events across sftp replicas
            String groupId = com.filetransfer.shared.fabric.FabricGroupIds.shared(serviceName, "events.account");
            eventFabricBridge.subscribeAccountEvents(groupId, event -> {
                try {
                    Map<String, Object> payload = event.payloadAsMap(injectedObjectMapper);
                    if (payload != null) {
                        handleEvent(payload);
                    }
                } catch (Exception e) {
                    log.error("Failed to process fabric account event: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.warn("[SFTP] Failed to subscribe to fabric account events: {}", e.getMessage());
        }
    }

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null || injectedObjectMapper == null) {
            log.error("[R134X][SFTP][account][boot] UnifiedOutboxPoller or ObjectMapper missing — account.* events will NOT be consumed");
            return;
        }
        outboxPoller.registerHandler("account.", row -> {
            log.info("[R134X][SFTP][account][outbox] row id={} routingKey={} aggregateId={}",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, injectedObjectMapper);
            handleEvent(payload);
        });
        log.info("[R134X][SFTP][account][boot] OUTBOX-ONLY active; @RabbitListener removed");
    }

    /**
     * Idempotent event handler. Duplicate delivery across transports is
     * safe: cache eviction, QoS updates, and directory creation all
     * converge on the same observable outcome.
     */
    private void handleEvent(Map<String, Object> event) {
        String username = (String) event.get("username");
        String homeDir = (String) event.get("homeDir");

        if (username == null) return;
        String eventType = (String) event.get("eventType");
        log.info("[R134X][SFTP][account] received type={} username={} homeDir={}", eventType, username, homeDir);

        // Evict cache so next auth picks up fresh DB data
        credentialService.evictFromCache(username);

        // Phase 1: evict partner cache on account events (partner linkage may have changed)
        Object partnerIdObj = event.get("partnerId");
        if (partnerCache != null && partnerIdObj != null) {
            try {
                partnerCache.evict(java.util.UUID.fromString(partnerIdObj.toString()));
            } catch (Exception ignored) {}
        }

        // Live QoS update: re-register bandwidth and session limits for active sessions
        if ("account.updated".equals(eventType) && bandwidthThrottleManager.hasUserLimits(username)) {
            credentialService.findAccount(username).ifPresent(account -> {
                bandwidthThrottleManager.registerUserLimits(username,
                    account.getQosUploadBytesPerSecond(),
                    account.getQosDownloadBytesPerSecond(),
                    account.getQosBurstAllowancePercent());
                connectionManager.registerQosSessionLimit(username,
                    account.getQosMaxConcurrentSessions());
                log.info("[R134X][SFTP][account] live QoS updated for active user={}", username);
            });
        }

        // Create home directories from template (carried in event) or default
        // VIRTUAL accounts: folders are provisioned in VFS by onboarding-api — skip physical dirs
        if ("account.created".equals(eventType) && homeDir != null) {
            String storageMode = (String) event.getOrDefault("storageMode", "PHYSICAL");
            if ("VIRTUAL".equalsIgnoreCase(storageMode)) {
                log.info("[R134X][SFTP][account] VIRTUAL account {} — physical dir creation skipped (VFS-managed)", username);
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                java.util.List<String> folderPaths = (java.util.List<String>) event.get("folderPaths");
                if (folderPaths == null || folderPaths.isEmpty()) {
                    folderPaths = java.util.List.of("inbox", "outbox", "sent");
                }
                for (String folder : folderPaths) {
                    Files.createDirectories(Paths.get(homeDir, folder));
                }
                log.info("[R134X][SFTP][account] created {} directories for {}: {}", folderPaths.size(), username, homeDir);
            } catch (Exception e) {
                log.error("[R134X][SFTP][account] FAILED to create home directories for {} at {}: {}", username, homeDir, e.getMessage());
            }
        }
    }
}
