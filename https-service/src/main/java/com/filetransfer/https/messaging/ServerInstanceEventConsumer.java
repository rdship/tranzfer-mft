package com.filetransfer.https.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.https.server.HttpsListenerRegistry;
import com.filetransfer.shared.dto.ServerInstanceChangeEvent;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Reacts to {@link ServerInstanceChangeEvent} and drives the
 * {@link HttpsListenerRegistry} to bind / rebind / unbind HTTPS listeners
 * without a service restart.
 *
 * <p><b>R134X — Sprint 7 Phase B:</b> OUTBOX-ONLY.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerInstanceEventConsumer {

    private final HttpsListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null) {
            log.error("[R134X][HTTPS][server-instance][boot] UnifiedOutboxPoller missing — server.instance.* events will NOT be consumed");
            return;
        }
        outboxPoller.registerHandler("server.instance.", row -> {
            log.info("[R134X][HTTPS][server-instance][outbox] row id={} routingKey={} aggregateId={}",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, objectMapper);
            onChange(payload);
        });
        log.info("[R134X][HTTPS][server-instance][boot] OUTBOX-ONLY active; @RabbitListener removed");
    }

    public void onChange(Map<String, Object> payload) {
        try {
            ServerInstanceChangeEvent event = objectMapper.convertValue(
                    payload, ServerInstanceChangeEvent.class);
            if (event.protocol() != Protocol.HTTPS) return;

            switch (event.changeType()) {
                case CREATED, ACTIVATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) {
                        log.warn("[R134X][HTTPS][server-instance] ServerInstance {} not found in DB for {}",
                                event.id(), event.changeType());
                        return;
                    }
                    if (!si.isActive()) return;
                    registry.bind(si);
                }
                case UPDATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) return;
                    if (!si.isActive()) { registry.unbind(event.id()); return; }
                    registry.rebind(si);
                }
                case DEACTIVATED, DELETED -> registry.unbind(event.id());
            }
        } catch (Exception e) {
            log.error("[R134X][HTTPS][server-instance] failed to handle change event: {}", e.getMessage(), e);
            throw e;
        }
    }
}
