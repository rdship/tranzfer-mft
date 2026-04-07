package com.filetransfer.notification.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Factory that resolves the correct notification channel by type.
 * Channels self-register via Spring's component scanning.
 */
@Slf4j
@Component
public class NotificationChannelFactory {

    private final Map<String, NotificationChannel> channelMap;

    public NotificationChannelFactory(List<NotificationChannel> channels) {
        this.channelMap = channels.stream()
                .collect(Collectors.toMap(
                        ch -> ch.getChannelType().toUpperCase(),
                        Function.identity()
                ));
        log.info("Registered notification channels: {}", channelMap.keySet());
    }

    /**
     * Get a notification channel by type.
     *
     * @param channelType the channel type (EMAIL, WEBHOOK, SMS)
     * @return the channel implementation
     * @throws IllegalArgumentException if no channel exists for the type
     */
    public NotificationChannel getChannel(String channelType) {
        NotificationChannel channel = channelMap.get(channelType.toUpperCase());
        if (channel == null) {
            throw new IllegalArgumentException("Unsupported notification channel: " + channelType);
        }
        return channel;
    }

    /**
     * Check if a channel type is supported.
     */
    public boolean isSupported(String channelType) {
        return channelMap.containsKey(channelType.toUpperCase());
    }
}
