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
 * Microsoft Teams notification channel.
 * Sends notifications via Teams Incoming Webhook URLs using Adaptive Card format.
 * The webhook URL can be provided per-notification as the recipient, or
 * falls back to the configured default webhook URL.
 *
 * Only loaded when notification.teams.default-webhook-url is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.teams.default-webhook-url")
public class TeamsNotificationChannel implements NotificationChannel {

    private final RestTemplate teamsRestTemplate;
    private final NotificationProperties properties;

    public TeamsNotificationChannel(NotificationProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTeams().getConnectTimeoutMs());
        factory.setReadTimeout(properties.getTeams().getReadTimeoutMs());
        this.teamsRestTemplate = new RestTemplate(factory);
    }

    @Override
    public void send(String recipient, String subject, String body, Map<String, Object> metadata) throws Exception {
        // Use recipient as webhook URL if it looks like a URL, otherwise use default
        String webhookUrl = (recipient != null && recipient.startsWith("https://"))
                ? recipient
                : properties.getTeams().getDefaultWebhookUrl();

        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException("No Teams webhook URL configured and no URL provided as recipient");
        }

        log.info("Sending Teams notification subject={}", subject);

        String headerText = subject != null ? subject : "TranzFer MFT Notification";
        String escapedHeader = escapeJson(headerText);
        String escapedBody = escapeJson(body != null ? body : "");

        // Microsoft Teams Adaptive Card payload
        String payload = """
                {
                    "type": "message",
                    "attachments": [
                        {
                            "contentType": "application/vnd.microsoft.card.adaptive",
                            "content": {
                                "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                                "type": "AdaptiveCard",
                                "version": "1.4",
                                "body": [
                                    {
                                        "type": "TextBlock",
                                        "text": "%s",
                                        "weight": "Bolder",
                                        "size": "Medium",
                                        "wrap": true
                                    },
                                    {
                                        "type": "TextBlock",
                                        "text": "%s",
                                        "wrap": true
                                    }
                                ]
                            }
                        }
                    ]
                }""".formatted(escapedHeader, escapedBody);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = teamsRestTemplate.exchange(
                    webhookUrl, HttpMethod.POST, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Teams notification sent successfully");
            } else {
                throw new RuntimeException("Teams webhook returned non-2xx: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to send Teams notification: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public String getChannelType() {
        return "TEAMS";
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
