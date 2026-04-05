package com.filetransfer.shared.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.InetSocketAddress;
import java.net.Proxy;

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

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
