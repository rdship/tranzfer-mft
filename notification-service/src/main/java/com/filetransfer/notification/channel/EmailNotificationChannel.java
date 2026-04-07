package com.filetransfer.notification.channel;

import com.filetransfer.notification.config.NotificationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Email notification channel using Spring JavaMailSender.
 * Sends plain-text emails with configurable from address.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationChannel implements NotificationChannel {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    @Override
    public void send(String recipient, String subject, String body, Map<String, Object> metadata) throws Exception {
        log.info("Sending email to={} subject={}", recipient, subject);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(recipient);
        message.setSubject(subject != null ? subject : "TranzFer MFT Notification");
        message.setText(body);

        mailSender.send(message);
        log.info("Email sent successfully to={}", recipient);
    }

    @Override
    public String getChannelType() {
        return "EMAIL";
    }
}
