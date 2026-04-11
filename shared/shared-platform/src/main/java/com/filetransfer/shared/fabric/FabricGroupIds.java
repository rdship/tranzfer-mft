package com.filetransfer.shared.fabric;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Centralized naming for Kafka consumer groups used by fabric subscribers.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Shared</b> — one group per service. All pods in that service join the same group,
 *       Kafka distributes partitions across them. Use for competing-consumer / load-balance
 *       semantics (e.g., flow.intake, events.account, events.notification).</li>
 *   <li><b>Fanout</b> — one group per pod. Each pod gets its own group so every pod receives
 *       every message. Use for broadcast semantics where every replica must react in lockstep
 *       (e.g., flow-rule hot-reload, config invalidation).</li>
 * </ul>
 *
 * <p>Names are stable across restarts in shared mode, and deterministic-per-pod in fanout mode
 * (uses {@code HOSTNAME} env var, which Docker/Kubernetes set to the container/pod name). If
 * {@code HOSTNAME} is not set, falls back to the machine hostname, then to a one-shot random
 * suffix that persists for the lifetime of the JVM.
 *
 * <p><b>Naming convention:</b>
 * <pre>
 *   Shared: fabric.{service}.{topic}                 e.g. fabric.sftp-service.events.account
 *   Fanout: fabric.{service}.{topic}.{instance}      e.g. fabric.config-service.events.flow-rule.config-service-7b8d9
 * </pre>
 */
public final class FabricGroupIds {

    private static final String INSTANCE_ID = resolveInstanceIdOnce();

    private FabricGroupIds() {}

    /**
     * Consumer group for load-balanced (competing consumer) subscriptions.
     * All pods of the same service share one group.
     */
    public static String shared(String service, String topic) {
        return "fabric." + service + "." + topic;
    }

    /**
     * Consumer group for broadcast (fanout) subscriptions.
     * Each pod gets its own group so every message is delivered to every replica.
     */
    public static String fanout(String service, String topic) {
        return "fabric." + service + "." + topic + "." + INSTANCE_ID;
    }

    /** Stable instance identifier for this JVM — resolved once at class load. */
    public static String instanceId() {
        return INSTANCE_ID;
    }

    private static String resolveInstanceIdOnce() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "instance-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
