package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.sftp.server.SftpListenerRegistry;
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
 * Reacts to ServerInstance CRUD events and drives the
 * {@link SftpListenerRegistry} to bind / unbind / rebind live listeners
 * without a service restart.
 *
 * <p>Events are published by onboarding-api's {@code ServerInstanceService}
 * to {@code event_outbox} on routing keys {@code server.instance.created|
 * updated|activated|deactivated|deleted}.</p>
 *
 * <p><b>R134X — Sprint 7 Phase B:</b> OUTBOX-ONLY. Legacy
 * {@code @RabbitListener} + Queue/Exchange/Binding {@code @Bean}
 * declarations removed. Handler runs solely via
 * {@link UnifiedOutboxPoller}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerInstanceEventConsumer {

    private final SftpListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null) {
            log.error("[R134X][SFTP][server-instance][boot] UnifiedOutboxPoller missing — server.instance.* events will NOT be consumed");
            return;
        }
        outboxPoller.registerHandler("server.instance.", row -> {
            log.info("[R134X][SFTP][server-instance][outbox] row id={} routingKey={} aggregateId={}",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, objectMapper);
            onChange(payload);
        });
        log.info("[R134X][SFTP][server-instance][boot] OUTBOX-ONLY active; @RabbitListener removed");
    }

    /** Idempotent listener driver; safe under duplicate delivery. */
    public void onChange(Map<String, Object> payload) {
        try {
            ServerInstanceChangeEvent event = objectMapper.convertValue(payload, ServerInstanceChangeEvent.class);
            if (event.protocol() != Protocol.SFTP) return; // other protocol services handle their own

            switch (event.changeType()) {
                case CREATED, ACTIVATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) { log.warn("[R134X][SFTP][server-instance] ServerInstance {} not found in DB for {}", event.id(), event.changeType()); return; }
                    if (!si.isActive()) return;
                    if (registry.isPrimary(si)) {
                        log.info("[R134X][SFTP][server-instance] skipping bind for primary listener '{}' — managed by env-var bean",
                                si.getInstanceId());
                        return;
                    }
                    registry.bind(si);
                }
                case UPDATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) return;
                    if (!si.isActive()) { registry.unbind(event.id()); return; }
                    // Primary is bound by the env-var-driven bean at boot — a runtime
                    // rebind via the registry would try to bind its port a second
                    // time (BindException → BIND_FAILED in DB). Admin must restart
                    // the container to pick up primary config changes.
                    if (registry.isPrimary(si)) {
                        log.info("[R134X][SFTP][server-instance] skipping rebind for primary listener '{}' — restart the container to apply primary config changes",
                                si.getInstanceId());
                        return;
                    }
                    // Port or key/algorithm settings may have changed — safest is rebind.
                    registry.rebind(si);
                }
                case DEACTIVATED, DELETED -> registry.unbind(event.id());
            }
        } catch (Exception e) {
            log.error("[R134X][SFTP][server-instance] failed to handle change event: {}", e.getMessage(), e);
            throw e; // poller retries with backoff per R134t contract
        }
    }
}
