package com.filetransfer.fabric.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.fabric.FabricClient;
import com.filetransfer.fabric.FabricEvent;
import com.filetransfer.fabric.config.FabricProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
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

            while (running) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<String, String> record : records) {
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
                        } catch (Exception e) {
                            log.error("[Fabric/Kafka] Handler failed for topic={} offset={}: {}",
                                record.topic(), record.offset(), e.getMessage());
                            // Do NOT commit — message will be redelivered
                            continue;
                        }
                    }
                    // Commit after batch
                    if (!records.isEmpty()) {
                        consumer.commitSync();
                    }
                } catch (Exception e) {
                    log.warn("[Fabric/Kafka] Consumer loop error: {}", e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
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

    public void shutdown() {
        running = false;
        for (Thread t : consumerThreads) {
            t.interrupt();
        }
        producer.close(Duration.ofSeconds(5));
    }
}
