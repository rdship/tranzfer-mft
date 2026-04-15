package com.filetransfer.as2.controller;

import com.filetransfer.as2.routing.As2RoutingHandler;
import com.filetransfer.as2.service.As2MessageProcessor;
import com.filetransfer.as2.service.MdnGenerator;
import com.filetransfer.shared.repository.integration.As2MessageRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/**
 * AS2 Inbound Receiver Controller (RFC 4130).
 *
 * Trading partners POST AS2 messages to this endpoint. The controller:
 * 1. Extracts AS2 headers (AS2-From, AS2-To, Message-ID)
 * 2. Delegates to As2MessageProcessor for validation and parsing
 * 3. Routes the received file through the platform's standard FileFlow via As2RoutingHandler
 * 4. Returns an MDN (Message Disposition Notification) receipt
 *
 * Endpoint: POST /as2/receive
 *
 * This is the AS2 equivalent of the SFTP server's SftpRoutingEventListener.
 * Files received here follow the exact same processing pipeline as SFTP/FTP uploads.
 */
@Slf4j
@RestController
@RequestMapping("/as2")
@RequiredArgsConstructor
public class As2InboundController {

    private final As2MessageProcessor messageProcessor;
    private final MdnGenerator mdnGenerator;
    private final As2RoutingHandler routingHandler;
    private final As2MessageRepository messageRepository;

    @Value("${as2.max-message-size:524288000}")
    private long maxMessageSize;

    /**
     * Receive an inbound AS2 message from a trading partner.
     * This is the primary endpoint that trading partners will POST to.
     */
    @PostMapping(value = "/receive", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<String> receiveAs2Message(
            @RequestHeader(value = "AS2-From", required = false) String as2From,
            @RequestHeader(value = "AS2-To", required = false) String as2To,
            @RequestHeader(value = "Message-ID", required = false) String messageId,
            @RequestHeader(value = "Subject", required = false) String subject,
            @RequestHeader(value = "Disposition-Notification-To", required = false) String mdnTo,
            @RequestHeader(value = "Disposition-Notification-Options", required = false) String mdnOptions,
            @RequestHeader(value = "Receipt-Delivery-Option", required = false) String asyncMdnUrl,
            HttpServletRequest request) throws IOException {

        log.info("AS2 message received: from={} to={} messageId={}", as2From, as2To, messageId);

        // Collect all headers for audit
        Map<String, String> allHeaders = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            allHeaders.put(name, request.getHeader(name));
        }

        // Read request body (file payload)
        byte[] body = request.getInputStream().readAllBytes();
        if (body.length == 0) {
            return buildErrorResponse(messageId, as2To, as2From, "Empty message body");
        }
        if (body.length > maxMessageSize) {
            return buildErrorResponse(messageId, as2To, as2From,
                    "Message exceeds maximum size: " + maxMessageSize);
        }

        // Process the message
        String contentType = request.getContentType();
        As2MessageProcessor.ProcessingResult result = messageProcessor.process(
                as2From, as2To, messageId, subject, contentType, body, allHeaders);

        if (!result.success()) {
            log.warn("AS2 message rejected: {}", result.errorReason());
            return buildErrorResponse(messageId, as2To, as2From, result.errorReason());
        }

        // Check if async MDN is requested
        boolean asyncMdn = asyncMdnUrl != null && !asyncMdnUrl.isBlank();

        // Route the file through the platform's standard flow (async)
        routingHandler.routeInboundMessage(
                result.partnership(), result.message(), result.payload(), result.filename());

        // Update message with track info
        if (asyncMdn) {
            // For async MDN: return 200 OK immediately, send MDN later via HTTP POST to asyncMdnUrl
            log.info("Async MDN requested for message={} url={}", messageId, asyncMdnUrl);
            result.message().setMdnStatus("ASYNC_PENDING");
            messageRepository.save(result.message());

            // Schedule async MDN delivery (fire-and-forget for now)
            scheduleAsyncMdn(result, asyncMdnUrl);

            return ResponseEntity.ok().build();
        }

        // Generate synchronous MDN
        MdnGenerator.MdnResponse mdn = mdnGenerator.generateSuccess(
                messageId, result.partnership().getOurAs2Id(),
                result.partnership().getPartnerAs2Id(), result.mic());

        // Update message record
        result.message().setMdnReceived(true);
        result.message().setMdnStatus("processed");
        result.message().setStatus("ACKNOWLEDGED");
        messageRepository.save(result.message());

        HttpHeaders headers = new HttpHeaders();
        headers.set("AS2-From", result.partnership().getOurAs2Id());
        headers.set("AS2-To", result.partnership().getPartnerAs2Id());
        headers.set("Message-ID", mdn.messageId());
        headers.set("AS2-Version", "1.2");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(mdn.contentType()))
                .body(mdn.body());
    }

    private ResponseEntity<String> buildErrorResponse(String originalMessageId, String ourAs2Id,
                                                        String partnerAs2Id, String error) {
        if (ourAs2Id == null) ourAs2Id = "unknown";
        if (partnerAs2Id == null) partnerAs2Id = "unknown";

        MdnGenerator.MdnResponse mdn = mdnGenerator.generateError(
                originalMessageId, ourAs2Id, partnerAs2Id, error);

        HttpHeaders headers = new HttpHeaders();
        headers.set("AS2-From", ourAs2Id);
        headers.set("AS2-To", partnerAs2Id);
        headers.set("Message-ID", mdn.messageId());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .headers(headers)
                .contentType(MediaType.parseMediaType(mdn.contentType()))
                .body(mdn.body());
    }

    private void scheduleAsyncMdn(As2MessageProcessor.ProcessingResult result, String asyncMdnUrl) {
        // Async MDN will be sent by As2MdnCallbackController's scheduled task
        // Store the callback URL in the message for later processing
        result.message().setMdnStatus("ASYNC_PENDING:" + asyncMdnUrl);
        messageRepository.save(result.message());
    }
}
