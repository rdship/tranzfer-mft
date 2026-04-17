package com.filetransfer.shared.routing;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Unique identifier for THIS pod/process, assigned at bean-creation time.
 *
 * <p>Used by {@link RoutingEngine} to stamp the {@code FileUploadedEvent}
 * it publishes to RabbitMQ, and by {@link FileUploadEventConsumer} to skip
 * events that originated on this same pod (preventing duplicate local
 * processing when the fanout message comes back to the publishing pod).
 *
 * <p>ID stability: process lifetime only. On restart, the pod gets a new
 * ID. Any in-flight events will be processed by the restarted pod since
 * the original process is gone — correct behavior. Other pods still
 * recognize the message as "not mine" and process it if they also have
 * the consumer enabled.</p>
 */
@Slf4j
@Component
@Getter
public class OriginPodId {
    private final String id = UUID.randomUUID().toString();

    public OriginPodId() {
        log.info("Origin pod ID assigned: {}", id);
    }

    /** Stable header name used on RabbitMQ messages that carry this id. */
    public static final String HEADER = "X-Origin-Pod";
}
