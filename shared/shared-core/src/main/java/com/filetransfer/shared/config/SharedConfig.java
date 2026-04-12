package com.filetransfer.shared.config;

import com.filetransfer.shared.spiffe.SpiffeX509Manager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
     *
     * <p>Priority order:
     * <ol>
     *   <li><b>mTLS (Phase 2)</b> — when {@link SpiffeX509Manager} is present and available,
     *       creates an Apache HttpClient 5 RestTemplate with a SPIFFE-backed SSLContext.
     *       Connection pool: 200 total / 20 per route. Idle connections evicted after 30 s.
     *       Services activate this path by setting their peer URLs to {@code https://} and
     *       enabling {@code spiffe.mtls-enabled=true}. Identity rides the TLS channel —
     *       no JWT header is attached (see {@code BaseServiceClient.addInternalAuth}).
     *   <li><b>Proxy</b> — when {@code platform.proxy.enabled=true}, routes through the
     *       configured HTTP/SOCKS5 proxy (unchanged from existing behaviour).
     *   <li><b>Plain HTTP</b> — default; uses JWT-SVID cache from Phase 1.
     * </ol>
     */
    @Bean
    public RestTemplate restTemplate(ObjectProvider<SpiffeX509Manager> x509ManagerProvider) {
        SpiffeX509Manager x509Manager = x509ManagerProvider.getIfAvailable();
        if (x509Manager != null && x509Manager.isAvailable()) {
            return buildMtlsRestTemplate(x509Manager);
        }

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
        // Trust self-signed certs for inter-service HTTPS (platform TLS uses shared self-signed cert)
        try {
            javax.net.ssl.SSLContext sslCtx = org.apache.hc.core5.ssl.SSLContextBuilder.create()
                    .loadTrustMaterial(org.apache.hc.client5.http.ssl.TrustAllStrategy.INSTANCE).build();
            var cm = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                            .setSslContext(sslCtx)
                            .setHostnameVerifier(org.apache.hc.client5.http.ssl.NoopHostnameVerifier.INSTANCE)
                            .build())
                    .setMaxConnTotal(200).setMaxConnPerRoute(20).build();
            CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(cm).build();
            var factory = new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(5000);
            return new RestTemplate(factory);
        } catch (Exception e) {
            log.warn("Failed to create trust-all RestTemplate, falling back to default: {}", e.getMessage());
            return new RestTemplate();
        }
    }

    /**
     * Build an Apache HttpClient 5 RestTemplate with SPIFFE mTLS SSLContext.
     * The {@link SpiffeX509Manager}'s {@code SpiffeKeyManager} serves the current
     * X.509-SVID on every TLS handshake — live-rotating from SPIRE, zero SPIRE I/O
     * on the hot path. Falls back to plain RestTemplate on SSLContext creation failure.
     */
    private RestTemplate buildMtlsRestTemplate(SpiffeX509Manager x509Manager) {
        try {
            SSLConnectionSocketFactory sslCsf = SSLConnectionSocketFactoryBuilder.create()
                    .setSslContext(x509Manager.createSslContext())
                    .build();

            PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(sslCsf)
                    .setMaxConnTotal(200)   // across all target services
                    .setMaxConnPerRoute(20) // per service (10 services typical)
                    .build();

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(cm)
                    .evictExpiredConnections()
                    .evictIdleConnections(TimeValue.ofSeconds(30))
                    .build();

            log.info("[SPIFFE] mTLS RestTemplate configured — Apache HttpClient 5, pool max=200/route=20");
            return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
        } catch (Exception ex) {
            log.error("[SPIFFE] Failed to create mTLS RestTemplate — falling back to plain HTTP: {}",
                    ex.getMessage(), ex);
            return new RestTemplate();
        }
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
