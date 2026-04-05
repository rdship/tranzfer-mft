package com.filetransfer.shared.config;

import com.filetransfer.shared.entity.PlatformSetting;
import com.filetransfer.shared.enums.Environment;
import com.filetransfer.shared.repository.PlatformSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads platform settings from the database for the current service and environment.
 * Service-specific values override GLOBAL values for the same key.
 *
 * Every microservice that includes the shared module gets this bean automatically.
 * On startup it loads its settings; on RabbitMQ config-change events it reloads.
 *
 * Usage:
 *   @Autowired PlatformConfigLoader config;
 *   int port = config.getInt("sftp.port", 2222);
 *   String host = config.getString("gateway.internal-sftp-host", "sftp-service");
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformConfigLoader {

    private final PlatformSettingRepository settingRepository;

    @Value("${cluster.service-type:ONBOARDING}")
    private String serviceType;

    @Value("${platform.environment:PROD}")
    private String environmentName;

    /** In-memory cache: key → value (service-specific overrides already applied) */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void load() {
        reload();
    }

    /**
     * Reload all settings from DB. Called on startup and on config-change events.
     */
    public void reload() {
        Environment env = parseEnvironment(environmentName);
        List<PlatformSetting> settings = settingRepository.findForServiceInEnvironment(env, serviceType);

        Map<String, String> fresh = new ConcurrentHashMap<>();
        // GLOBAL settings first (they come last in the ORDER BY serviceName DESC query
        // because 'GLOBAL' < service names alphabetically, but we sort: service-specific first).
        // So we load all, letting service-specific overwrite GLOBAL.
        for (PlatformSetting s : settings) {
            // Service-specific takes precedence: if already set by a service-specific entry, skip GLOBAL
            if ("GLOBAL".equals(s.getServiceName()) && fresh.containsKey(s.getSettingKey())) {
                continue;
            }
            if (s.getSettingValue() != null) {
                fresh.put(s.getSettingKey(), s.getSettingValue());
            }
        }

        cache.clear();
        cache.putAll(fresh);
        log.info("Loaded {} platform settings for service={} env={}", cache.size(), serviceType, env);
    }

    public String getString(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    public long getLong(String key, long defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    public double getDouble(String key, double defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(cache.get(key));
    }

    public Map<String, String> getAll() {
        return Map.copyOf(cache);
    }

    public Environment getCurrentEnvironment() {
        return parseEnvironment(environmentName);
    }

    public String getServiceType() {
        return serviceType;
    }

    private Environment parseEnvironment(String name) {
        try {
            return Environment.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown environment '{}', defaulting to PROD", name);
            return Environment.PROD;
        }
    }
}
