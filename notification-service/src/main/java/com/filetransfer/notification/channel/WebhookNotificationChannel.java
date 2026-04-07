package com.filetransfer.notification.channel;

import com.filetransfer.notification.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Webhook notification channel.
 * Sends HTTP POST requests with JSON payloads to configured webhook URLs.
 */
@Slf4j
@Component
public class WebhookNotificationChannel implements NotificationChannel {

    private final RestTemplate webhookRestTemplate;

    public WebhookNotificationChannel(NotificationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getWebhook().getConnectTimeoutMs());
        factory.setReadTimeout(properties.getWebhook().getReadTimeoutMs());
        this.webhookRestTemplate = new RestTemplate(factory);
    }

    @Override
    public void send(String recipient, String subject, String body, Map<String, Object> metadata) throws Exception {
        log.info("Sending webhook to url={}", recipient);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Add custom headers from metadata if present
        if (metadata != null && metadata.containsKey("headers")) {
            @SuppressWarnings("unchecked")
            Map<String, String> customHeaders = (Map<String, String>) metadata.get("headers");
            if (customHeaders != null) {
                customHeaders.forEach(headers::set);
            }
        }

        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = webhookRestTemplate.exchange(
                recipient, HttpMethod.POST, request, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("Webhook delivered successfully to url={} status={}", recipient, response.getStatusCode());
        } else {
            throw new RuntimeException("Webhook returned non-2xx status: " + response.getStatusCode());
        }
    }

    @Override
    public String getChannelType() {
        return "WEBHOOK";
    }
}
