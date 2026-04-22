package com.filetransfer.shared.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Subscribes to {@code platform:cluster:events} Redis channel and reacts to
 * service instance join/leave events in real time.
 *
 * <p><b>R134AE — AOT-safety retrofit.</b> Was previously
 * {@code @ConditionalOnBean(RedisConnectionFactory.class)} which Spring AOT
 * evaluated at build time and excluded the bean from the frozen graph
 * (R134AD runtime report §2 confirmed pub/sub was never wired). The class
 * is now unconditional; listener registration is runtime-gated on
 * {@code cluster.events.transport} (default {@code redis-pubsub}) and on
 * {@link RedisConnectionFactory} availability via {@link ObjectProvider}.
 * Same pattern documented in {@code docs/AOT-SAFETY.md}.
 *
 * <p>When a new replica of any service starts or stops, every other service
 * receives a Pub/Sub message within milliseconds — no polling, no 30-second lag.
 *
 * <p>Usage by other components: inject {@link ClusterEventSubscriber} and call
 * {@link #getKnownInstances(String)} to get the last-seen instance URLs for a
 * service type (augments the PG {@code platform_pod_heartbeat} reader).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ClusterEventSubscriber {

    /** Local in-memory view of live instances, updated via Pub/Sub. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> knownInstances =
            new ConcurrentHashMap<>();

    private final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;

    @Value("${cluster.events.transport:redis-pubsub}")
    private String eventsTransport;

    @Bean
    public RedisMessageListenerContainer redisClusterEventContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        RedisConnectionFactory cf = connectionFactoryProvider.getIfAvailable();
        if (cf == null) {
            log.info("[R134AE][ClusterEventSubscriber] no RedisConnectionFactory available — container returned without listener (transport={})",
                    eventsTransport);
            return container;
        }
        container.setConnectionFactory(cf);
        if (!"redis-pubsub".equalsIgnoreCase(eventsTransport)) {
            log.info("[R134AE][ClusterEventSubscriber] transport={} — connection factory wired but NOT subscribing to {} (R134AG will migrate this to RabbitMQ fanout)",
                    eventsTransport, RedisServiceRegistry.EVENTS_CHANNEL);
            return container;
        }
        container.addMessageListener(clusterEventListener(),
                new PatternTopic(RedisServiceRegistry.EVENTS_CHANNEL));
        log.info("[R134AE][ClusterEventSubscriber] subscribed to {} (transport=redis-pubsub)",
                RedisServiceRegistry.EVENTS_CHANNEL);
        return container;
    }

    @Bean
    public MessageListener clusterEventListener() {
        return (Message message, byte[] pattern) -> {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            handleEvent(payload);
        };
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
            log.info("[ClusterEvent] ✓ JOINED  {} @ {} (id={})",
                    serviceType, url, instanceId.substring(0, Math.min(8, instanceId.length())));

        } else if ("DEPARTED".equalsIgnoreCase(type)) {
            CopyOnWriteArrayList<String> list = knownInstances.get(serviceType);
            if (list != null) list.removeIf(u -> u.contains(instanceId) || u.equals(url));
            log.info("[ClusterEvent] ✗ DEPARTED {} (id={})",
                    serviceType, instanceId.substring(0, Math.min(8, instanceId.length())));
        }
    }

    /**
     * Returns the URLs of all known-live instances of the given service type,
     * as learned from Pub/Sub events since this instance started.
     *
     * <p>For a complete view (including pre-existing replicas), query the
     * authoritative PG {@code platform_pod_heartbeat} table directly.
     */
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
