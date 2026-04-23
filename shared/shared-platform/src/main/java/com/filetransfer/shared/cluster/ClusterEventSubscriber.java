package com.filetransfer.shared.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Subscribes to the RabbitMQ {@link ClusterEventsAmqpConfig#EXCHANGE} fanout
 * and reacts to service instance join/leave events in real time.
 *
 * <p><b>R134AI — RabbitMQ transport replaces the Redis pub/sub channel</b>
 * that R134AE had AOT-retrofitted. Queue topology (anonymous, auto-delete,
 * exclusive) is declared in {@link ClusterEventsAmqpConfig}; this bean
 * binds a single {@link RabbitListener} to that queue. When a service
 * dies the queue vanishes cleanly — no manual cleanup required.
 *
 * <p>The JSON payload shape is byte-identical to the pre-R134AI Redis
 * format so {@link #handleEvent(String)} and the in-memory registry
 * ({@link #knownInstances}) carry over unchanged.
 *
 * <p>Usage by other components: inject {@link ClusterEventSubscriber} and
 * call {@link #getKnownInstances(String)} for the last-seen instance URLs
 * for a service type. For a complete authoritative view (including
 * pre-existing replicas), query the PG {@code platform_pod_heartbeat}
 * table directly.
 *
 * <p>AOT-safe: activation is gated on {@code @ConditionalOnClass(RabbitTemplate)}
 * which the AOT processor evaluates at build time against the classpath.
 */
@Slf4j
@Component
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class ClusterEventSubscriber {

    /** Local in-memory view of live instances, updated from fanout messages. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> knownInstances =
            new ConcurrentHashMap<>();

    @RabbitListener(queues = "#{@clusterEventsQueue.name}")
    public void onClusterEvent(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        // The payload crosses two JSON encodings in the RabbitTemplate + Jackson
        // pipeline: {@link Jackson2JsonMessageConverter} serialises the String
        // as a quoted JSON literal, so the raw body may be wrapped in outer
        // quotes + escaped inner quotes. Strip the outer wrapper if present
        // so the same {@link #handleEvent} parser works for both forms.
        if (payload.length() >= 2 && payload.startsWith("\"") && payload.endsWith("\"")) {
            payload = payload.substring(1, payload.length() - 1).replace("\\\"", "\"");
        }
        handleEvent(payload);
    }

    private void handleEvent(String json) {
        String type        = extractField(json, "type");
        String serviceType = extractField(json, "serviceType");
        String instanceId  = extractField(json, "instanceId");
        String url         = extractField(json, "url");

        if ("JOINED".equalsIgnoreCase(type)) {
            knownInstances.computeIfAbsent(serviceType, k -> new CopyOnWriteArrayList<>())
                          .removeIf(u -> u.contains(instanceId)); // dedup
            knownInstances.get(serviceType).add(url);
            log.info("[R134AI][ClusterEvent] ✓ JOINED  {} @ {} (id={})",
                    serviceType, url, instanceId.substring(0, Math.min(8, instanceId.length())));

        } else if ("DEPARTED".equalsIgnoreCase(type)) {
            CopyOnWriteArrayList<String> list = knownInstances.get(serviceType);
            if (list != null) list.removeIf(u -> u.contains(instanceId) || u.equals(url));
            log.info("[R134AI][ClusterEvent] ✗ DEPARTED {} (id={})",
                    serviceType, instanceId.substring(0, Math.min(8, instanceId.length())));
        }
    }

    public java.util.List<String> getKnownInstances(String serviceType) {
        CopyOnWriteArrayList<String> list = knownInstances.get(serviceType);
        return list != null ? new java.util.ArrayList<>(list) : java.util.List.of();
    }

    private static String extractField(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = json.indexOf("\"", start);
        return end < 0 ? "" : json.substring(start, end);
    }
}
