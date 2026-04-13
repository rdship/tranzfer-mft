package com.filetransfer.as2.controller;

import com.filetransfer.shared.entity.integration.As2Message;
import com.filetransfer.shared.repository.As2MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Handles asynchronous MDN (Message Disposition Notification) callbacks.
 *
 * Two responsibilities:
 * 1. Receive async MDN POSTed back to us by trading partners (for our outbound messages)
 * 2. Send pending async MDNs to trading partners (for their inbound messages we received)
 */
@Slf4j
@RestController
@RequestMapping("/as2")
@RequiredArgsConstructor
public class As2MdnCallbackController {

    private final As2MessageRepository messageRepository;
    private final RestTemplate restTemplate;

    /**
     * Receive an async MDN from a trading partner.
     * This is called when WE sent an AS2 message with Receipt-Delivery-Option header
     * and the partner is now sending back the MDN asynchronously.
     */
    @PostMapping(value = "/mdn", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> receiveAsyncMdn(
            @RequestHeader(value = "Message-ID", required = false) String mdnMessageId,
            @RequestHeader(value = "Original-Message-ID", required = false) String originalMessageId,
            @RequestBody(required = false) String mdnBody) {

        log.info("Async MDN received: mdnId={} originalMsgId={}", mdnMessageId, originalMessageId);

        if (originalMessageId == null && mdnBody != null) {
            // Try to extract Original-Message-ID from body
            originalMessageId = extractOriginalMessageId(mdnBody);
        }

        if (originalMessageId != null) {
            String cleanId = originalMessageId.replaceAll("^<|>$", "").trim();
            messageRepository.findByMessageId(cleanId).ifPresent(msg -> {
                boolean success = mdnBody != null && mdnBody.contains("processed");
                msg.setMdnReceived(true);
                msg.setMdnStatus(success ? "processed" : "failed");
                msg.setStatus(success ? "ACKNOWLEDGED" : "FAILED");
                messageRepository.save(msg);
                log.info("Async MDN applied: messageId={} status={}", cleanId, msg.getStatus());
            });
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Periodically send pending async MDNs for inbound messages we received.
     * When a partner sends us a message with Receipt-Delivery-Option header,
     * we store the callback URL and send the MDN asynchronously.
     */
    @Scheduled(fixedDelayString = "${as2.mdn.async-send-interval:30000}")
    @SchedulerLock(name = "sendPendingAsyncMdns", lockAtLeastFor = "25s", lockAtMostFor = "120s")
    public void sendPendingAsyncMdns() {
        List<As2Message> pending = messageRepository.findByStatus("RECEIVED");
        for (As2Message msg : pending) {
            String mdnStatus = msg.getMdnStatus();
            if (mdnStatus == null || !mdnStatus.startsWith("ASYNC_PENDING:")) continue;

            String callbackUrl = mdnStatus.substring("ASYNC_PENDING:".length());
            try {
                // Build a simple MDN body
                String mdnBody = buildAsyncMdnBody(msg);
                restTemplate.postForEntity(callbackUrl, mdnBody, String.class);

                msg.setMdnReceived(true);
                msg.setMdnStatus("ASYNC_SENT");
                msg.setStatus("ACKNOWLEDGED");
                messageRepository.save(msg);
                log.info("Async MDN sent: messageId={} url={}", msg.getMessageId(), callbackUrl);
            } catch (Exception e) {
                log.warn("Failed to send async MDN for message={}: {}", msg.getMessageId(), e.getMessage());
            }
        }
    }

    private String buildAsyncMdnBody(As2Message msg) {
        return "Content-Type: message/disposition-notification\r\n\r\n" +
                "Reporting-UA: TranzFer-MFT/AS2-Service\r\n" +
                "Original-Message-ID: " + msg.getMessageId() + "\r\n" +
                "Disposition: automatic-action/MDN-sent-automatically; processed\r\n";
    }

    private String extractOriginalMessageId(String body) {
        if (body == null) return null;
        for (String line : body.split("\r?\n")) {
            if (line.startsWith("Original-Message-ID:")) {
                return line.substring("Original-Message-ID:".length()).trim();
            }
        }
        return null;
    }
}
