package com.filetransfer.ftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ftp.service.CredentialService;
import com.filetransfer.shared.cache.PartnerCache;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * <p><b>R134X — Sprint 7 Phase B:</b> OUTBOX-ONLY. Legacy
 * {@code @RabbitListener} + Queue/Exchange/Binding {@code @Bean}
 * declarations + dual-consume log removed. {@link UnifiedOutboxPoller}
 * is the sole inter-service transport.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final CredentialService credentialService;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private PartnerCache partnerCache;

    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @jakarta.annotation.PostConstruct
    void subscribeFabricEvents() {
        if (eventFabricBridge == null || objectMapper == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "ftp-service");
        try {
            eventFabricBridge.subscribeAccountEvents(
                com.filetransfer.shared.fabric.FabricGroupIds.shared(serviceName, "events.account"),
                event -> {
                try {
                    Map<String, Object> payload = event.payloadAsMap(objectMapper);
                    if (payload != null) {
                        handleEvent(payload);
                    }
                } catch (Exception e) {
                    log.error("Failed to process fabric account event: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.warn("[FTP] Failed to subscribe to fabric account events: {}", e.getMessage());
        }
    }

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null || objectMapper == null) {
            log.error("[R134X][FTP][account][boot] UnifiedOutboxPoller or ObjectMapper missing — account.* events will NOT be consumed");
            return;
        }
        outboxPoller.registerHandler("account.", row -> {
            log.info("[R134X][FTP][account][outbox] row id={} routingKey={} aggregateId={}",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, objectMapper);
            handleEvent(payload);
        });
        log.info("[R134X][FTP][account][boot] OUTBOX-ONLY active; @RabbitListener removed");
    }

    /**
     * Idempotent event handler. Cache eviction + directory creation
     * converge on the same outcome across duplicate deliveries.
     */
    private void handleEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        String username = (String) event.get("username");
        String homeDir = (String) event.get("homeDir");

        if (username == null) return;

        log.info("[R134X][FTP][account] received type={} username={}", eventType, username);

        if ("account.updated".equals(eventType) || "account.created".equals(eventType)) {
            credentialService.evictFromCache(username);
        }

        // Phase 1: evict partner cache on account events
        Object partnerIdObj = event.get("partnerId");
        if (partnerCache != null && partnerIdObj != null) {
            try {
                partnerCache.evict(java.util.UUID.fromString(partnerIdObj.toString()));
            } catch (Exception ignored) {}
        }

        // Create home directories from template (carried in event) or defaults
        if ("account.created".equals(eventType) && homeDir != null) {
            String storageMode = (String) event.getOrDefault("storageMode", "PHYSICAL");
            if ("VIRTUAL".equalsIgnoreCase(storageMode)) {
                log.info("[R134X][FTP][account] VIRTUAL account {} — physical dir creation skipped (VFS-managed)", username);
                return;
            }
            try {
                // Always ensure home dir exists (N36 fix)
                Files.createDirectories(Paths.get(homeDir));
                @SuppressWarnings("unchecked")
                List<String> folderPaths = (List<String>) event.get("folderPaths");
                if (folderPaths == null || folderPaths.isEmpty()) {
                    folderPaths = java.util.List.of("inbox", "outbox", "sent");
                }
                for (String folder : folderPaths) {
                    Files.createDirectories(Paths.get(homeDir, folder));
                }
                log.info("[R134X][FTP][account] created {} directories for {}: {}", folderPaths.size(), username, homeDir);
            } catch (Exception e) {
                log.warn("[R134X][FTP][account] Could not create directories for {}: {}", username, e.getMessage());
            }
        }
    }
}
