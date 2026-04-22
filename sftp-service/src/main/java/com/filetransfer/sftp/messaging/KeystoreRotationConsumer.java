package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.sftp.server.SftpListenerRegistry;
import com.filetransfer.shared.dto.KeystoreKeyRotatedEvent;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hot-reload affected SFTP listeners when keystore-manager rotates an
 * SSH_HOST_KEY. Coarse refresh today: rebinds every dynamic listener owned by
 * this node so they pick up the new key from the provider chain.
 *
 * <p><b>R134X — Sprint 7 Phase B:</b> OUTBOX-ONLY. The legacy
 * {@code @RabbitListener}, Queue/Exchange/Binding {@code @Bean} declarations,
 * and dual-consume boot log were deleted. {@link UnifiedOutboxPoller} now
 * delivers {@code keystore.key.rotated} rows to {@link #applyRotation}
 * directly via the registered handler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeystoreRotationConsumer {

    private static final String PATTERN = "keystore.key.rotated";

    private final SftpListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @Nullable
    private UnifiedOutboxPoller outboxPoller;

    @PostConstruct
    void boot() {
        if (outboxPoller != null) {
            outboxPoller.registerHandler(PATTERN, row -> {
                log.info("[R134X][SFTP][keystore-rotation][outbox] row id={} routingKey={} aggregateId={}",
                        row.id(), row.routingKey(), row.aggregateId());
                KeystoreKeyRotatedEvent ev = row.as(KeystoreKeyRotatedEvent.class, objectMapper);
                applyRotation(ev);
            });
            log.info("[R134X][SFTP][keystore-rotation][boot] OUTBOX-ONLY active; @RabbitListener removed");
        } else {
            log.error("[R134X][SFTP][keystore-rotation][boot] UnifiedOutboxPoller missing — keystore rotation events will NOT be consumed");
        }
    }

    /**
     * Apply rotation to every SFTP listener this node owns. Idempotent —
     * rebind with the same underlying key is a no-op in effect.
     */
    private void applyRotation(KeystoreKeyRotatedEvent event) {
        if (!"SSH_HOST_KEY".equals(event.keyType())) {
            log.debug("[R134X][SFTP][keystore-rotation] ignoring keyType={}", event.keyType());
            return;
        }
        log.info("[R134X][SFTP][keystore-rotation] SSH host key rotated ({} → {}); refreshing dynamic SFTP listeners",
                event.oldAlias(), event.newAlias());
        List<ServerInstance> listeners = repository.findByProtocolAndActiveTrue(Protocol.SFTP);
        int refreshed = 0;
        for (ServerInstance si : listeners) {
            if (registry.snapshot().containsKey(si.getId())) {
                registry.rebind(si);
                refreshed++;
            }
        }
        log.info("[R134X][SFTP][keystore-rotation] complete — {} listeners rebound", refreshed);
    }
}
