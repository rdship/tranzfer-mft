package com.filetransfer.shared.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.UUID;

/**
 * Shared listener that evicts PartnerCache entries when account events arrive.
 * Auto-activates in ANY service that has PartnerCache on the classpath.
 *
 * <p>This closes the gap where AS2-service and Gateway-service had PartnerCache
 * (for fast partner slug lookup in RoutingEngine) but no AccountEventConsumer
 * to trigger cache eviction on partner changes.
 *
 * <p><b>R134X — Sprint 7 Phase B:</b> migrated off RabbitMQ. Now subscribes to:
 * <ul>
 *   <li>PG outbox via {@link UnifiedOutboxPoller#registerHandler} on prefix
 *       {@code "account."} — primary transport since Sprint 7 Phase B.
 *       Uses the R134V multi-handler cap so this listener co-exists with
 *       per-service {@code AccountEventConsumer} on the same prefix in
 *       services that have both (sftp / ftp / ftp-web).</li>
 *   <li>Fabric {@code events.account} topic — feature-flagged, additive.</li>
 * </ul>
 * The {@code @RabbitListener} on this class was removed in R134X (see
 * Phase B commit). {@link #evictFromEvent} is idempotent — duplicate
 * delivery from either transport is harmless.
 */
@Slf4j
@Component
public class PartnerCacheEvictionListener {

    @Autowired(required = false)
    private PartnerCache partnerCache;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /** R134X Sprint 7 Phase B — primary transport since RabbitMQ path removed. */
    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @PostConstruct
    void subscribeFabricAccountEvents() {
        if (partnerCache == null || eventFabricBridge == null || objectMapper == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "cache-eviction");
        try {
            String groupId = com.filetransfer.shared.fabric.FabricGroupIds.fanout(
                    serviceName, "events.account");
            eventFabricBridge.subscribeAccountEvents(groupId, event -> {
                try {
                    Map<String, Object> payload = event.payloadAsMap(objectMapper);
                    if (payload != null) evictFromEvent(payload, "fabric");
                } catch (Exception e) {
                    log.debug("Partner cache eviction from fabric event failed: {}", e.getMessage());
                }
            });
            log.info("[R134X][PCE][fabric] subscribed to Fabric account events — partnerCache eviction ready on fabric path");
        } catch (Exception e) {
            log.debug("Fabric subscription for partner cache eviction skipped: {}", e.getMessage());
        }
    }

    @PostConstruct
    void subscribeOutboxAccountEvents() {
        if (partnerCache == null) {
            log.info("[R134X][PCE][boot] PartnerCache not on classpath — no-op listener (expected for services that don't resolve partners)");
            return;
        }
        if (outboxPoller == null || objectMapper == null) {
            log.warn("[R134X][PCE][boot] UnifiedOutboxPoller or ObjectMapper missing — PartnerCache will NOT evict on account events");
            return;
        }
        outboxPoller.registerHandler("account.", row -> {
            log.info("[R134X][PCE][outbox] row id={} routingKey={} aggregateId={} — evicting partner cache",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, objectMapper);
            if (payload != null) evictFromEvent(payload, "outbox");
        });
        log.info("[R134X][PCE][boot] outbox handler registered on 'account.' prefix — PartnerCache eviction now OUTBOX-ONLY (legacy @RabbitListener deleted)");
    }

    private void evictFromEvent(Map<String, Object> event, String source) {
        Object partnerIdObj = event.get("partnerId");
        if (partnerIdObj != null) {
            try {
                partnerCache.evict(UUID.fromString(partnerIdObj.toString()));
                log.debug("[R134X][PCE][{}] partner cache evicted partnerId={}", source, partnerIdObj);
            } catch (Exception ignored) {}
        }
    }
}
