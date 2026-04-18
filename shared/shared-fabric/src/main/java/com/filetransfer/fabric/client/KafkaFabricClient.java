package com.filetransfer.fabric.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.fabric.FabricClient;
import com.filetransfer.fabric.FabricEvent;
import com.filetransfer.fabric.config.FabricProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Real Kafka/Redpanda-backed FabricClient.
 * Uses plain Kafka client (not Spring Kafka) for simpler lifecycle control.
 */
@Slf4j
public class KafkaFabricClient implements FabricClient {

    private final FabricProperties props;
    private final ObjectMapper mapper;
    private final KafkaProducer<String, String> producer;
    private final List<Thread> consumerThreads = new ArrayList<>();
    private final Set<String> ensuredTopics = ConcurrentHashMap.newKeySet();
    /** Per-record delivery attempt counter, keyed by "topic:partition:offset". */
    private final Map<String, Integer> deliveryAttempts = new ConcurrentHashMap<>();
    /**
     * Long-lived shared AdminClient used by {@link #ensureTopic(String)} and
     * {@link #ensureTopics(Collection)}. R94 boot-time optimization:
     * historically each {@code ensureTopic()} call opened + closed its own
     * AdminClient, each paying ~500 ms on cluster-metadata fetch. With 20
     * flow.step topics per service this added ~10 s of sequential overhead
     * to every service's boot. Sharing the instance amortizes the metadata
     * cost to one open; the client is thread-safe.
     */
    private volatile AdminClient adminClient;
    private final Object adminClientLock = new Object();
    private volatile boolean running = true;
    private volatile boolean healthy;

    public KafkaFabricClient(FabricProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBrokerUrl());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, props.getProducer().getAcks());
        producerProps.put(ProducerConfig.RETRIES_CONFIG, props.getProducer().getRetries());
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, props.getProducer().getLingerMs());
        producerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, props.getProducer().getBatchSize());
        producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, props.getProducer().getCompressionType());
        producerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        producerProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);

        this.producer = new KafkaProducer<>(producerProps);
        this.healthy = checkBrokerReachable();
    }

    private boolean checkBrokerReachable() {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBrokerUrl());
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) props.getHealthCheckTimeoutMs());
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) props.getHealthCheckTimeoutMs());

        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.listTopics().names().get(props.getHealthCheckTimeoutMs(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warn("[Fabric/Kafka] Broker health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void publish(String topic, String key, Object value) {
        if (!healthy) {
            log.warn("[Fabric/Kafka] Publish skipped for {} (broker unhealthy)", topic);
            return;
        }
        ensureTopic(topic);
        try {
            String jsonValue = mapper.writeValueAsString(value);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, jsonValue);
            RecordMetadata md = producer.send(record).get(5, TimeUnit.SECONDS);
            log.debug("[Fabric/Kafka] Published to {} partition={} offset={} key={}",
                topic, md.partition(), md.offset(), key);
        } catch (Exception e) {
            log.error("[Fabric/Kafka] Failed to publish to {}: {}", topic, e.getMessage());
            // On publish failure, mark unhealthy so next publish is skipped
            if (e instanceof ExecutionException || e.getMessage().contains("timeout")) {
                healthy = false;
            }
        }
    }

    @Override
    public void subscribe(String topic, String groupId, MessageHandler handler) {
        if (!healthy) {
            log.warn("[Fabric/Kafka] Subscribe skipped for {} (broker unhealthy)", topic);
            return;
        }
        ensureTopic(topic);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBrokerUrl());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.getConsumer().getAutoOffsetReset());
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, props.getConsumer().isEnableAutoCommit());
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.getConsumer().getMaxPollRecords());
        consumerProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, props.getConsumer().getSessionTimeoutMs());
        consumerProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, props.getConsumer().getHeartbeatIntervalMs());

        Thread consumerThread = new Thread(() -> runConsumer(topic, groupId, handler, consumerProps),
            "fabric-consumer-" + topic + "-" + groupId);
        consumerThread.setDaemon(true);
        consumerThread.start();
        consumerThreads.add(consumerThread);
        log.info("[Fabric/Kafka] Subscribed to {} (group: {})", topic, groupId);
    }

    private void runConsumer(String topic, String groupId, MessageHandler handler, Properties consumerProps) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singleton(topic));
            int maxAttempts = Math.max(0, props.getConsumer().getMaxDeliveryAttempts());

            while (running) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    boolean batchHadPoison = false;
                    for (ConsumerRecord<String, String> record : records) {
                        String recordKey = record.topic() + ":" + record.partition() + ":" + record.offset();
                        try {
                            Map<String, String> headers = new HashMap<>();
                            for (Header h : record.headers()) {
                                headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
                            }
                            FabricEvent event = FabricEvent.builder()
                                .topic(record.topic())
                                .key(record.key())
                                .partition(record.partition())
                                .offset(record.offset())
                                .timestamp(Instant.ofEpochMilli(record.timestamp()))
                                .headers(headers)
                                .rawValue(record.value())
                                .build();

                            handler.handle(event);
                            deliveryAttempts.remove(recordKey);
                        } catch (Exception e) {
                            int attempts = deliveryAttempts.merge(recordKey, 1, Integer::sum);
                            log.error("[Fabric/Kafka] Handler failed for topic={} offset={} attempt={}/{}: {}",
                                record.topic(), record.offset(), attempts, maxAttempts, e.getMessage());

                            if (maxAttempts > 0 && attempts >= maxAttempts) {
                                if (sendToDlq(record, e, attempts)) {
                                    deliveryAttempts.remove(recordKey);
                                    // DLQ succeeded — fall through to commit so we advance past this record
                                    continue;
                                }
                            }
                            // Either DLQ disabled, or DLQ publish failed — halt the batch so Kafka redelivers
                            batchHadPoison = true;
                            break;
                        }
                    }
                    // Commit after batch (unless a poison message is blocking us)
                    if (!records.isEmpty() && !batchHadPoison) {
                        consumer.commitSync();
                    }
                } catch (Exception e) {
                    log.warn("[Fabric/Kafka] Consumer loop error: {}", e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
        }
    }

    /**
     * Publish a poison message to {@code <topic>.dlq} with failure metadata in headers.
     * Returns true if the DLQ publish succeeded (so the offset can be advanced).
     */
    private boolean sendToDlq(ConsumerRecord<String, String> record, Exception failure, int attempts) {
        String dlqTopic = record.topic() + ".dlq";
        try {
            ensureTopic(dlqTopic);
            ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, record.key(), record.value());
            // Preserve original headers
            for (Header h : record.headers()) {
                dlqRecord.headers().add(h.key(), h.value());
            }
            // Add failure metadata
            dlqRecord.headers().add("x-fabric-dlq-source-topic", record.topic().getBytes(StandardCharsets.UTF_8));
            dlqRecord.headers().add("x-fabric-dlq-source-partition",
                String.valueOf(record.partition()).getBytes(StandardCharsets.UTF_8));
            dlqRecord.headers().add("x-fabric-dlq-source-offset",
                String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8));
            dlqRecord.headers().add("x-fabric-dlq-attempts",
                String.valueOf(attempts).getBytes(StandardCharsets.UTF_8));
            dlqRecord.headers().add("x-fabric-dlq-failure-class",
                failure.getClass().getName().getBytes(StandardCharsets.UTF_8));
            String msg = failure.getMessage() != null ? failure.getMessage() : "";
            dlqRecord.headers().add("x-fabric-dlq-failure-message",
                msg.getBytes(StandardCharsets.UTF_8));
            dlqRecord.headers().add("x-fabric-dlq-timestamp",
                Instant.now().toString().getBytes(StandardCharsets.UTF_8));

            producer.send(dlqRecord).get(5, TimeUnit.SECONDS);
            log.warn("[Fabric/Kafka] Poison message routed to DLQ: topic={} offset={} → {} ({})",
                record.topic(), record.offset(), dlqTopic, failure.getClass().getSimpleName());
            return true;
        } catch (Exception dlqEx) {
            log.error("[Fabric/Kafka] DLQ publish failed for topic={} offset={}: {}",
                record.topic(), record.offset(), dlqEx.getMessage());
            return false;
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public boolean isDistributed() {
        return healthy;
    }

    /**
     * Idempotently ensure a topic exists with the configured partition count.
     * Called lazily on first publish/subscribe. Deduped in-process via {@code ensuredTopics}.
     * Safe to call repeatedly across processes — AdminClient.createTopics rejects duplicates
     * with TopicExistsException which we swallow.
     *
     * <p>Uses a shared {@link AdminClient} (R94) instead of opening a fresh one
     * per call. Older implementations paid ~500 ms for cluster metadata on
     * every invocation; sharing the instance keeps that cost at one open.
     */
    private void ensureTopic(String topic) {
        if (!ensuredTopics.add(topic)) return;
        ensureTopicsInternal(Collections.singleton(topic));
    }

    /**
     * Batch variant — preferred when the caller knows a set of topics up-front
     * (e.g. {@code FlowFabricConsumer} pre-subscribing to 20 step-type topics).
     * Issues a single {@code createTopics(Collection)} call instead of 20
     * sequential ones, saving ~400 ms per additional topic on cluster metadata.
     */
    public void ensureTopics(Collection<String> topics) {
        List<String> fresh = new ArrayList<>(topics.size());
        for (String t : topics) {
            if (ensuredTopics.add(t)) fresh.add(t);
        }
        if (fresh.isEmpty()) return;
        ensureTopicsInternal(fresh);
    }

    private void ensureTopicsInternal(Collection<String> freshTopics) {
        int partitions = Math.max(1, props.getFlow().getPartitionCount());
        short replicationFactor = 1; // Redpanda single-broker; cluster deployments override via broker defaults

        List<NewTopic> newTopics = new ArrayList<>(freshTopics.size());
        for (String t : freshTopics) newTopics.add(new NewTopic(t, partitions, replicationFactor));

        try {
            AdminClient admin = sharedAdminClient();
            admin.createTopics(newTopics).all()
                .get(props.getHealthCheckTimeoutMs(), TimeUnit.MILLISECONDS);
            log.info("[Fabric/Kafka] Created topic(s) {} ({} partitions)", freshTopics, partitions);
        } catch (Exception e) {
            // TopicExistsException is expected on restart when ANY of the batch
            // already existed. We get it wrapped as ExecutionException → cause.
            // Log at debug; the rest of the batch still got created on broker side.
            String msg = e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName();
            if (msg.contains("TopicExists")) {
                log.debug("[Fabric/Kafka] Topic(s) {} already exist (or partial overlap)", freshTopics);
            } else {
                log.warn("[Fabric/Kafka] Topic ensure failed for {}: {} (relying on auto-create)",
                    freshTopics, e.getMessage());
                // Let caller proceed — Redpanda auto-create will handle it if enabled
                for (String t : freshTopics) ensuredTopics.remove(t); // retry on next call
            }
        }
    }

    /** Lazy-init shared AdminClient. Thread-safe. */
    private AdminClient sharedAdminClient() {
        AdminClient local = adminClient;
        if (local != null) return local;
        synchronized (adminClientLock) {
            if (adminClient == null) {
                Properties adminProps = new Properties();
                adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBrokerUrl());
                adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) props.getHealthCheckTimeoutMs());
                adminClient = AdminClient.create(adminProps);
                log.debug("[Fabric/Kafka] Shared AdminClient created");
            }
            return adminClient;
        }
    }

    public void shutdown() {
        running = false;
        for (Thread t : consumerThreads) {
            t.interrupt();
        }
        producer.close(Duration.ofSeconds(5));
        AdminClient admin = adminClient;
        if (admin != null) {
            try { admin.close(Duration.ofSeconds(2)); }
            catch (Exception ignored) { /* best-effort */ }
        }
    }
}
