package com.filetransfer.shared.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
public class SharedConfig {

    private final PlatformConfig platformConfig;

    /**
     * Central RestTemplate bean used by all services for outbound HTTP calls.
     * When platform.proxy.enabled=true, routes through the configured proxy.
     * When false (default), connects directly.
     */
    @Bean
    public RestTemplate restTemplate() {
        PlatformConfig.ProxyConfig proxyConfig = platformConfig.getProxy();
        if (proxyConfig != null && proxyConfig.isEnabled() && proxyConfig.getHost() != null) {
            Proxy.Type proxyType = "SOCKS5".equalsIgnoreCase(proxyConfig.getType())
                    ? Proxy.Type.SOCKS : Proxy.Type.HTTP;

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setProxy(new Proxy(proxyType,
                    new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort())));

            log.info("Service-level proxy enabled: {} {}:{} (bypass: {})",
                    proxyConfig.getType(), proxyConfig.getHost(),
                    proxyConfig.getPort(), proxyConfig.getNoProxyHosts());
            return new RestTemplate(factory);
        }
        return new RestTemplate();
    }

    /**
     * Async executor for @Async methods (routing, audit, flow processing).
     * Bounded queue prevents OOM under load; caller-runs policy applies backpressure.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mft-async-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Shared JSON message converter for RabbitMQ — ensures all services
     * serialize/deserialize events as JSON instead of Java serialization.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("http://localhost:*", "https://localhost:*")
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
