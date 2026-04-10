package com.filetransfer.forwarder.service;

import com.filetransfer.shared.entity.ExternalDestination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Forwards file bytes to a Kafka topic.
 * Key = filename, Value = raw file bytes.
 * Only loaded when forwarder.kafka.enabled=true — avoids requiring Kafka dependencies at runtime.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forwarder.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaForwarderService {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public void forward(ExternalDestination dest, String filename, byte[] fileBytes) throws Exception {
        String topic = dest.getKafkaTopic();
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Kafka topic not configured for destination: " + dest.getName());
        }

        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, filename, fileBytes);
        CompletableFuture<SendResult<String, byte[]>> future = kafkaTemplate.send(record);

        SendResult<String, byte[]> result = future.get();
        RecordMetadata metadata = result.getRecordMetadata();
        log.info("Kafka forward complete: file={} topic={} partition={} offset={}",
                filename, metadata.topic(), metadata.partition(), metadata.offset());
    }
}
