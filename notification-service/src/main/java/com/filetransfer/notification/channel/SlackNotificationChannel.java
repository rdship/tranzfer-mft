package com.filetransfer.notification.channel;

import com.filetransfer.notification.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Slack notification channel.
 * Sends notifications via Slack Incoming Webhook URLs with Block Kit formatting.
 * The webhook URL can be provided per-notification as the recipient, or
 * falls back to the configured default webhook URL.
 *
 * Only loaded when notification.slack.default-webhook-url is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.slack.default-webhook-url")
public class SlackNotificationChannel implements NotificationChannel {

    private final RestTemplate slackRestTemplate;
    private final NotificationProperties properties;

    public SlackNotificationChannel(NotificationProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getSlack().getConnectTimeoutMs());
        factory.setReadTimeout(properties.getSlack().getReadTimeoutMs());
        this.slackRestTemplate = new RestTemplate(factory);
    }

    @Override
    public void send(String recipient, String subject, String body, Map<String, Object> metadata) throws Exception {
        // Use recipient as webhook URL if it looks like a URL, otherwise use default
        String webhookUrl = (recipient != null && recipient.startsWith("https://"))
                ? recipient
                : properties.getSlack().getDefaultWebhookUrl();

        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException("No Slack webhook URL configured and no URL provided as recipient");
        }

        log.info("Sending Slack notification subject={}", subject);

        // Build Slack Block Kit payload with header and body sections
        String headerText = subject != null ? subject : "TranzFer MFT Notification";
        String escapedHeader = escapeJson(headerText);
        String escapedBody = escapeJson(body != null ? body : "");

        String payload = """
                {
                    "text": "%s: %s",
                    "blocks": [
                        {
                            "type": "header",
                            "text": { "type": "plain_text", "text": "%s" }
                        },
                        {
                            "type": "section",
                            "text": { "type": "mrkdwn", "text": "%s" }
                        }
                    ]
                }""".formatted(escapedHeader, escapedBody, escapedHeader, escapedBody);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = slackRestTemplate.exchange(
                    webhookUrl, HttpMethod.POST, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Slack notification sent successfully");
            } else {
                throw new RuntimeException("Slack webhook returned non-2xx: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to send Slack notification: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public String getChannelType() {
        return "SLACK";
    }

    /** Escape special JSON characters in a string value. */
    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
