package com.filetransfer.shared.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized URL and connection configuration for every microservice in the platform.
 * Each service is configured under the "platform.services" namespace.
 *
 * <p>Usage in application.yml:
 * <pre>
 * platform:
 *   services:
 *     encryption-service:
 *       url: http://encryption-service:8086
 *     screening-service:
 *       url: http://screening-service:8092
 *       enabled: false
 * </pre>
 *
 * <p>Each endpoint supports:
 * <ul>
 *   <li>url — base URL of the service (including protocol + port, no trailing slash)</li>
 *   <li>enabled — whether calls to this service are active (default: true)</li>
 *   <li>connect-timeout-ms — TCP connect timeout (default: 5000)</li>
 *   <li>read-timeout-ms — response read timeout (default: 30000)</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "platform.services")
@Getter @Setter
public class ServiceClientProperties {

    private ServiceEndpoint onboardingApi = new ServiceEndpoint("https://onboarding-api:9080");
    private ServiceEndpoint sftpService = new ServiceEndpoint("https://sftp-service:9081");
    private ServiceEndpoint ftpService = new ServiceEndpoint("https://ftp-service:9082");
    private ServiceEndpoint ftpWebService = new ServiceEndpoint("https://ftp-web-service:9083");
    private ServiceEndpoint configService = new ServiceEndpoint("https://config-service:9084");
    private ServiceEndpoint gatewayService = new ServiceEndpoint("https://gateway-service:9085");
    private ServiceEndpoint encryptionService = new ServiceEndpoint("https://encryption-service:9086");
    private ServiceEndpoint forwarderService = new ServiceEndpoint("https://external-forwarder-service:9087");
    private ServiceEndpoint dmzProxy = new ServiceEndpoint("https://dmz-proxy:9088");
    private ServiceEndpoint licenseService = new ServiceEndpoint("https://license-service:9089");
    private ServiceEndpoint analyticsService = new ServiceEndpoint("https://analytics-service:9090");
    private ServiceEndpoint aiEngine = new ServiceEndpoint("https://ai-engine:9091");
    private ServiceEndpoint screeningService = new ServiceEndpoint("https://screening-service:9092");
    private ServiceEndpoint keystoreManager = new ServiceEndpoint("https://keystore-manager:9093");
    private ServiceEndpoint as2Service = new ServiceEndpoint("https://as2-service:9094");
    private ServiceEndpoint ediConverter = new ServiceEndpoint("https://edi-converter:9095");
    private ServiceEndpoint storageManager = new ServiceEndpoint("https://storage-manager:9096");

    @Getter @Setter
    public static class ServiceEndpoint {
        private String url;
        private boolean enabled = true;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 30000;

        public ServiceEndpoint() {}

        public ServiceEndpoint(String url) {
            this.url = url;
        }
    }
}
