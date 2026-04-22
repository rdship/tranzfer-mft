package com.filetransfer.ftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ftp.server.FtpListenerRegistry;
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
 * Hot-reload affected FTPS listeners when keystore-manager rotates a TLS cert.
 * Rebinds every dynamic FTP listener owned by this node so they pick up the
 * new cert on next accept. Connected sessions are NOT dropped — new connections
 * get the new cert on their own TLS handshake.
 *
 * <p><b>R134X — Sprint 7 Phase B:</b> OUTBOX-ONLY. The legacy
 * {@code @RabbitListener}, Queue/Exchange/Binding {@code @Bean} declarations,
 * and dual-consume boot log were deleted. {@link UnifiedOutboxPoller} now
 * delivers {@code keystore.key.rotated} rows directly to the handler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeystoreRotationConsumer {

    private static final String PATTERN = "keystore.key.rotated";

    private final FtpListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @Nullable
    private UnifiedOutboxPoller outboxPoller;

    @PostConstruct
    void boot() {
        if (outboxPoller != null) {
            outboxPoller.registerHandler(PATTERN, row -> {
                log.info("[R134X][FTP][keystore-rotation][outbox] row id={} routingKey={} aggregateId={}",
                        row.id(), row.routingKey(), row.aggregateId());
                KeystoreKeyRotatedEvent ev = row.as(KeystoreKeyRotatedEvent.class, objectMapper);
                applyRotation(ev);
            });
            log.info("[R134X][FTP][keystore-rotation][boot] OUTBOX-ONLY active; @RabbitListener removed");
        } else {
            log.error("[R134X][FTP][keystore-rotation][boot] UnifiedOutboxPoller missing — TLS cert rotation events will NOT be consumed");
        }
    }

    private void applyRotation(KeystoreKeyRotatedEvent event) {
        if (!"TLS_CERT".equals(event.keyType())) {
            log.debug("[R134X][FTP][keystore-rotation] ignoring keyType={} — not TLS", event.keyType());
            return;
        }
        log.info("[R134X][FTP][keystore-rotation] TLS cert rotated ({} → {}); refreshing dynamic FTP listeners",
                event.oldAlias(), event.newAlias());
        List<ServerInstance> listeners = repository.findByProtocolAndActiveTrue(Protocol.FTP);
        int refreshed = 0;
        for (ServerInstance si : listeners) {
            if (registry.snapshot().containsKey(si.getId())) {
                registry.rebind(si);
                refreshed++;
            }
        }
        log.info("[R134X][FTP][keystore-rotation] complete — {} FTP listeners rebound", refreshed);
    }
}
