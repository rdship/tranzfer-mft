package com.filetransfer.shared.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Platform-wide metrics configuration.
 * Tags every metric with service name and cluster ID for Prometheus/Grafana filtering.
 *
 * Endpoints exposed:
 * - /actuator/prometheus — Prometheus scrape endpoint
 * - /actuator/health — liveness + readiness
 * - /actuator/info — build info
 */
@Configuration
public class MetricsConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> platformMetricsCustomizer(
            @Value("${spring.application.name:unknown}") String appName,
            @Value("${platform.cluster.id:default-cluster}") String clusterId) {
        return registry -> registry.config()
                .commonTags(List.of(
                        Tag.of("service", appName),
                        Tag.of("cluster", clusterId)
                ));
    }

    @Bean
    JvmMemoryMetrics jvmMemoryMetrics() { return new JvmMemoryMetrics(); }

    @Bean
    JvmGcMetrics jvmGcMetrics() { return new JvmGcMetrics(); }

    @Bean
    JvmThreadMetrics jvmThreadMetrics() { return new JvmThreadMetrics(); }

    @Bean
    ProcessorMetrics processorMetrics() { return new ProcessorMetrics(); }

    @Bean
    UptimeMetrics uptimeMetrics() { return new UptimeMetrics(); }

    @Bean
    ClassLoaderMetrics classLoaderMetrics() { return new ClassLoaderMetrics(); }
}
