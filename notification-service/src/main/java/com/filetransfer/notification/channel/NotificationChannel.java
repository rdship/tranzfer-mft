package com.filetransfer.notification.channel;

import java.util.Map;

/**
 * Interface for notification dispatch channels.
 * Each implementation handles a specific delivery mechanism (email, webhook, SMS).
 */
public interface NotificationChannel {

    /**
     * Send a notification to the specified recipient.
     *
     * @param recipient target address (email, URL, phone number)
     * @param subject   notification subject (may be null for webhooks)
     * @param body      notification body content
     * @param metadata  additional metadata for the channel
     * @throws Exception if delivery fails
     */
    void send(String recipient, String subject, String body, Map<String, Object> metadata) throws Exception;

    /**
     * @return the channel type identifier (EMAIL, WEBHOOK, SMS)
     */
    String getChannelType();
}
