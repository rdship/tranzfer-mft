package com.filetransfer.config.service;

import com.filetransfer.shared.entity.ServerConfig;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.ServerConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerConfigService {

    private final ServerConfigRepository repository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    public List<ServerConfig> list(ServiceType type) {
        return type != null
                ? repository.findByServiceTypeAndActiveTrue(type)
                : repository.findByActiveTrue();
    }

    public ServerConfig get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Server config not found: " + id));
    }

    @Transactional
    public ServerConfig create(ServerConfig config) {
        config.setId(null);
        ServerConfig saved = repository.save(config);
        publishConfigChange("server.config.created", saved.getId());
        log.info("Created server config: {} type={} port={}", saved.getName(), saved.getServiceType(), saved.getPort());
        return saved;
    }

    @Transactional
    public ServerConfig update(UUID id, ServerConfig config) {
        if (!repository.existsById(id)) throw new NoSuchElementException("Not found: " + id);
        config.setId(id);
        ServerConfig saved = repository.save(config);
        publishConfigChange("server.config.updated", saved.getId());
        return saved;
    }

    @Transactional
    public ServerConfig setActive(UUID id, boolean active) {
        ServerConfig config = get(id);
        config.setActive(active);
        ServerConfig saved = repository.save(config);
        publishConfigChange(active ? "server.config.enabled" : "server.config.disabled", id);
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
        publishConfigChange("server.config.deleted", id);
    }

    private void publishConfigChange(String eventType, UUID configId) {
        try {
            rabbitTemplate.convertAndSend(exchange, "config.changed",
                    Map.of("eventType", eventType, "configId", configId.toString()));
        } catch (Exception e) {
            log.warn("Failed to publish config change event: {}", e.getMessage());
        }
    }
}
