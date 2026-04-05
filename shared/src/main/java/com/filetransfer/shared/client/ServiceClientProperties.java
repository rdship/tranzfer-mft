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

    private ServiceEndpoint onboardingApi = new ServiceEndpoint("http://onboarding-api:8080");
    private ServiceEndpoint sftpService = new ServiceEndpoint("http://sftp-service:8081");
    private ServiceEndpoint ftpService = new ServiceEndpoint("http://ftp-service:8082");
    private ServiceEndpoint ftpWebService = new ServiceEndpoint("http://ftp-web-service:8083");
    private ServiceEndpoint configService = new ServiceEndpoint("http://config-service:8084");
    private ServiceEndpoint gatewayService = new ServiceEndpoint("http://gateway-service:8085");
    private ServiceEndpoint encryptionService = new ServiceEndpoint("http://encryption-service:8086");
    private ServiceEndpoint forwarderService = new ServiceEndpoint("http://external-forwarder-service:8087");
    private ServiceEndpoint dmzProxy = new ServiceEndpoint("http://dmz-proxy:8088");
    private ServiceEndpoint licenseService = new ServiceEndpoint("http://license-service:8089");
    private ServiceEndpoint analyticsService = new ServiceEndpoint("http://analytics-service:8090");
    private ServiceEndpoint aiEngine = new ServiceEndpoint("http://ai-engine:8091");
    private ServiceEndpoint screeningService = new ServiceEndpoint("http://screening-service:8092");
    private ServiceEndpoint keystoreManager = new ServiceEndpoint("http://keystore-manager:8093");
    private ServiceEndpoint as2Service = new ServiceEndpoint("http://as2-service:8094");
    private ServiceEndpoint ediConverter = new ServiceEndpoint("http://edi-converter:8095");
    private ServiceEndpoint storageManager = new ServiceEndpoint("http://storage-manager:8096");

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
