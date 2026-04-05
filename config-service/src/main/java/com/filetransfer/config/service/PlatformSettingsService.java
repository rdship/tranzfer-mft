package com.filetransfer.config.service;

import com.filetransfer.shared.entity.PlatformSetting;
import com.filetransfer.shared.enums.Environment;
import com.filetransfer.shared.repository.PlatformSettingRepository;
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final PlatformSettingRepository repository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    // ── Queries ─────────────────────────────────────────────────────────────

    public List<PlatformSetting> listAll() {
        return repository.findAll();
    }

    public List<PlatformSetting> listByEnvironment(Environment env) {
        return repository.findByEnvironment(env);
    }

    public List<PlatformSetting> listByEnvironmentAndService(Environment env, String serviceName) {
        return repository.findByEnvironmentAndServiceNameAndActiveTrue(env, serviceName);
    }

    public List<PlatformSetting> listByService(String serviceName) {
        return repository.findByServiceName(serviceName);
    }

    public List<PlatformSetting> listByCategory(String category) {
        return repository.findByCategory(category);
    }

    public PlatformSetting get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Setting not found: " + id));
    }

    public List<Environment> getEnvironments() {
        return repository.findDistinctEnvironments();
    }

    public List<String> getServiceNames() {
        return repository.findDistinctServiceNames();
    }

    public List<String> getCategories() {
        return repository.findDistinctCategories();
    }

    // ── Mutations ───────────────────────────────────────────────────────────

    @Transactional
    public PlatformSetting create(PlatformSetting setting) {
        if (repository.existsBySettingKeyAndEnvironmentAndServiceName(
                setting.getSettingKey(), setting.getEnvironment(), setting.getServiceName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Setting already exists: " + setting.getSettingKey()
                            + " env=" + setting.getEnvironment()
                            + " service=" + setting.getServiceName());
        }
        setting.setId(null);
        PlatformSetting saved = repository.save(setting);
        publishEvent("platform.setting.created", saved);
        log.info("Created setting: key={} env={} service={}", saved.getSettingKey(), saved.getEnvironment(), saved.getServiceName());
        return saved;
    }

    @Transactional
    public PlatformSetting update(UUID id, PlatformSetting update) {
        PlatformSetting existing = get(id);
        existing.setSettingValue(update.getSettingValue());
        existing.setDescription(update.getDescription());
        existing.setCategory(update.getCategory());
        existing.setDataType(update.getDataType());
        existing.setSensitive(update.isSensitive());
        existing.setActive(update.isActive());
        PlatformSetting saved = repository.save(existing);
        publishEvent("platform.setting.updated", saved);
        log.info("Updated setting: key={} env={} service={}", saved.getSettingKey(), saved.getEnvironment(), saved.getServiceName());
        return saved;
    }

    @Transactional
    public PlatformSetting updateValue(UUID id, String newValue) {
        PlatformSetting existing = get(id);
        existing.setSettingValue(newValue);
        PlatformSetting saved = repository.save(existing);
        publishEvent("platform.setting.updated", saved);
        log.info("Updated value: key={} env={} service={}", saved.getSettingKey(), saved.getEnvironment(), saved.getServiceName());
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        PlatformSetting existing = get(id);
        repository.deleteById(id);
        publishEvent("platform.setting.deleted", existing);
        log.info("Deleted setting: key={} env={} service={}", existing.getSettingKey(), existing.getEnvironment(), existing.getServiceName());
    }

    /**
     * Clone all settings from one environment to another.
     * Useful when promoting config from TEST → CERT → PROD.
     */
    @Transactional
    public List<PlatformSetting> cloneEnvironment(Environment source, Environment target) {
        List<PlatformSetting> sourceSettings = repository.findByEnvironment(source);
        List<PlatformSetting> cloned = sourceSettings.stream()
                .filter(s -> !repository.existsBySettingKeyAndEnvironmentAndServiceName(
                        s.getSettingKey(), target, s.getServiceName()))
                .map(s -> PlatformSetting.builder()
                        .settingKey(s.getSettingKey())
                        .settingValue(s.getSettingValue())
                        .environment(target)
                        .serviceName(s.getServiceName())
                        .dataType(s.getDataType())
                        .description(s.getDescription())
                        .category(s.getCategory())
                        .sensitive(s.isSensitive())
                        .active(s.isActive())
                        .build())
                .toList();
        List<PlatformSetting> saved = repository.saveAll(cloned);
        log.info("Cloned {} settings from {} to {}", saved.size(), source, target);
        return saved;
    }

    // ── Events ──────────────────────────────────────────────────────────────

    private void publishEvent(String eventType, PlatformSetting setting) {
        try {
            rabbitTemplate.convertAndSend(exchange, "config.changed",
                    Map.of("eventType", eventType,
                            "settingKey", setting.getSettingKey(),
                            "environment", setting.getEnvironment().name(),
                            "serviceName", setting.getServiceName()));
        } catch (Exception e) {
            log.warn("Failed to publish setting change event: {}", e.getMessage());
        }
    }
}
