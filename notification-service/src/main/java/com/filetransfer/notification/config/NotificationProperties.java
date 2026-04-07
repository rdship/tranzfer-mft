package com.filetransfer.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Notification-specific configuration properties.
 */
@Configuration
@ConfigurationProperties(prefix = "notification")
@Getter @Setter
public class NotificationProperties {

    private String fromAddress = "noreply@tranzfer.io";

    private RetryConfig retry = new RetryConfig();
    private WebhookConfig webhook = new WebhookConfig();

    @Getter @Setter
    public static class RetryConfig {
        private int maxAttempts = 3;
        private int delaySeconds = 60;
    }

    @Getter @Setter
    public static class WebhookConfig {
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
    }
}
