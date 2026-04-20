package com.filetransfer.as2.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.dto.ServerInstanceChangeEvent;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.listener.BindStateWriter;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Map;

/**
 * R134o (closes R134l Bronze "AS2 auto-seed partial" finding) — acknowledges
 * {@code ServerInstance} rows with {@code protocol=AS2 or AS4} and writes
 * their {@code bind_state} so the UI + Platform Sentinel see the listener as
 * BOUND rather than UNKNOWN.
 *
 * <p>Unlike the SFTP/FTP/HTTPS pattern (dynamic per-listener port binding via
 * a listener registry), as2-service currently runs a single embedded-Tomcat
 * listener on the service port (8094) — all AS2 POSTs land there and
 * {@link com.filetransfer.as2.controller.As2InboundController} routes them.
 * A proper per-listener dynamic port registry is a future extension; for
 * now this consumer honestly writes bind_state so the admin UI no longer
 * shows UNKNOWN for every AS2 / AS4 row.
 *
 * <p>Per-partnership settings (MDN required, signing algo, encryption cert,
 * AS2 From/To IDs) live on {@code AS2Partnership} rows — not on this
 * listener row. That's the correct separation of concerns.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ServerInstanceEventConsumer {

    private static final String QUEUE    = "as2-server-instance-events";
    private static final String EXCHANGE = "file-transfer.events";
    private static final String PATTERN  = "server.instance.*";

    private final ServerInstanceRepository repository;
    private final BindStateWriter bindStateWriter;
    private final ObjectMapper objectMapper;

    @Value("${as2.instance-id:#{null}}")
    private String primaryInstanceId;

    @Bean
    public Queue as2ServerInstanceQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .build();
    }

    @Bean
    public TopicExchange as2ServerInstanceExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Binding as2ServerInstanceBinding(Queue as2ServerInstanceQueue,
                                              TopicExchange as2ServerInstanceExchange) {
        return BindingBuilder.bind(as2ServerInstanceQueue)
                .to(as2ServerInstanceExchange).with(PATTERN);
    }

    /**
     * On startup, scan for existing active AS2 / AS4 rows and mark them
     * BOUND. Fires after {@code ApplicationReadyEvent} so the embedded
     * Tomcat listener is already accepting connections when we write
     * bind_state — avoids UI flicker between BOUND → UNBOUND on boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        List<ServerInstance> as2Rows = repository.findByProtocolAndActiveTrue(Protocol.AS2);
        List<ServerInstance> as4Rows = repository.findByProtocolAndActiveTrue(Protocol.AS4);
        for (ServerInstance si : as2Rows) markBound(si);
        for (ServerInstance si : as4Rows) markBound(si);
        int total = as2Rows.size() + as4Rows.size();
        if (total > 0) log.info("[AS2] Acknowledged {} existing AS2/AS4 listener row(s)", total);
    }

    @RabbitListener(queues = QUEUE)
    public void onChange(Map<String, Object> payload) {
        try {
            ServerInstanceChangeEvent event = objectMapper.convertValue(
                    payload, ServerInstanceChangeEvent.class);
            if (event.protocol() != Protocol.AS2 && event.protocol() != Protocol.AS4) return;

            switch (event.changeType()) {
                case CREATED, ACTIVATED, UPDATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) {
                        log.warn("AS2/AS4 ServerInstance {} not found for {}",
                                event.id(), event.changeType());
                        return;
                    }
                    if (si.isActive()) markBound(si);
                    else bindStateWriter.markUnbound(si.getId());
                }
                case DEACTIVATED, DELETED -> bindStateWriter.markUnbound(event.id());
            }
        } catch (Exception e) {
            log.error("AS2 ServerInstance change event handling failed: {}", e.getMessage(), e);
            throw e; // DLQ
        }
    }

    @PreDestroy
    public void shutdown() {
        // On graceful shutdown, mark every AS2/AS4 row as UNBOUND so the UI
        // doesn't show stale BOUND after this service exits.
        List<ServerInstance> as2 = repository.findByProtocolAndActiveTrue(Protocol.AS2);
        List<ServerInstance> as4 = repository.findByProtocolAndActiveTrue(Protocol.AS4);
        for (ServerInstance si : as2) bindStateWriter.markUnbound(si.getId());
        for (ServerInstance si : as4) bindStateWriter.markUnbound(si.getId());
    }

    private void markBound(ServerInstance si) {
        try {
            bindStateWriter.markBound(si.getId());
            log.info("[AS2] Bound '{}' (protocol={}, port={}) via single-Tomcat listener",
                    si.getInstanceId(), si.getProtocol(), si.getInternalPort());
        } catch (Exception e) {
            log.warn("[AS2] Failed to mark bound for '{}': {}", si.getInstanceId(), e.getMessage());
        }
    }
}
