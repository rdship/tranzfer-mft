package com.filetransfer.shared.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.fabric.EventFabricBridge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * <p>Dual-subscribes: RabbitMQ (account.*) + Fabric (events.account) when available.
 * Idempotent — duplicate delivery from both buses is safe (eviction is harmless).
 */
@Slf4j
@Component
@org.springframework.context.annotation.Lazy(false)  // Must be eager — registers RabbitMQ listener
@ConditionalOnBean(PartnerCache.class)
public class PartnerCacheEvictionListener {

    @Autowired
    private PartnerCache partnerCache;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @PostConstruct
    void subscribeFabricAccountEvents() {
        if (eventFabricBridge == null || objectMapper == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "cache-eviction");
        try {
            String groupId = com.filetransfer.shared.fabric.FabricGroupIds.fanout(
                    serviceName, "events.account");
            eventFabricBridge.subscribeAccountEvents(groupId, event -> {
                try {
                    Map<String, Object> payload = event.payloadAsMap(objectMapper);
                    if (payload != null) evictFromEvent(payload);
                } catch (Exception e) {
                    log.debug("Partner cache eviction from fabric event failed: {}", e.getMessage());
                }
            });
            log.info("PartnerCacheEvictionListener subscribed to Fabric account events");
        } catch (Exception e) {
            log.debug("Fabric subscription for partner cache eviction skipped: {}", e.getMessage());
        }
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue,  // anonymous, auto-delete — each pod gets all events
            exchange = @Exchange(value = "${rabbitmq.exchange:file-transfer.events}", type = "topic"),
            key = "account.*"
    ))
    public void onAccountEvent(Map<String, Object> event) {
        evictFromEvent(event);
    }

    private void evictFromEvent(Map<String, Object> event) {
        Object partnerIdObj = event.get("partnerId");
        if (partnerIdObj != null) {
            try {
                partnerCache.evict(UUID.fromString(partnerIdObj.toString()));
                log.debug("Partner cache evicted for partnerId={}", partnerIdObj);
            } catch (Exception ignored) {}
        }
    }
}
