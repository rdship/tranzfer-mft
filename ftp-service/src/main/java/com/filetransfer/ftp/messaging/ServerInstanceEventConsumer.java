package com.filetransfer.ftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ftp.server.FtpListenerRegistry;
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
 * Reacts to ServerInstance CRUD events and drives {@link FtpListenerRegistry}
 * to bind / unbind / rebind FTP listeners at runtime.
 *
 * <p><b>R134X — Sprint 7 Phase B:</b> OUTBOX-ONLY.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerInstanceEventConsumer {

    private final FtpListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null) {
            log.error("[R134X][FTP][server-instance][boot] UnifiedOutboxPoller missing — server.instance.* events will NOT be consumed");
            return;
        }
        outboxPoller.registerHandler("server.instance.", row -> {
            log.info("[R134X][FTP][server-instance][outbox] row id={} routingKey={} aggregateId={}",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, objectMapper);
            onChange(payload);
        });
        log.info("[R134X][FTP][server-instance][boot] OUTBOX-ONLY active; @RabbitListener removed");
    }

    public void onChange(Map<String, Object> payload) {
        try {
            ServerInstanceChangeEvent event = objectMapper.convertValue(payload, ServerInstanceChangeEvent.class);
            if (event.protocol() != Protocol.FTP) return;
            switch (event.changeType()) {
                case CREATED, ACTIVATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null || !si.isActive()) return;
                    if (registry.isPrimary(si)) {
                        log.info("[R134X][FTP][server-instance] skipping bind for primary FTP listener '{}' — managed by env-var bean",
                                si.getInstanceId());
                        return;
                    }
                    registry.bind(si);
                }
                case UPDATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) return;
                    if (!si.isActive()) { registry.unbind(event.id()); return; }
                    if (registry.isPrimary(si)) {
                        log.info("[R134X][FTP][server-instance] skipping rebind for primary FTP listener '{}' — restart the container to apply primary config changes",
                                si.getInstanceId());
                        return;
                    }
                    registry.rebind(si);
                }
                case DEACTIVATED, DELETED -> registry.unbind(event.id());
            }
        } catch (Exception e) {
            log.error("[R134X][FTP][server-instance] failed to handle change event: {}", e.getMessage(), e);
            throw e;
        }
    }
}
