package com.filetransfer.as2.service;

import com.filetransfer.shared.entity.integration.As2Message;
import com.filetransfer.shared.entity.integration.As2Partnership;
import com.filetransfer.shared.repository.integration.As2MessageRepository;
import com.filetransfer.shared.repository.integration.As2PartnershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Processes inbound AS2 messages per RFC 4130.
 * Parses AS2 headers, validates partnership, extracts payload,
 * computes MIC for non-repudiation, and persists the message record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class As2MessageProcessor {

    private final As2PartnershipRepository partnershipRepository;
    private final As2MessageRepository messageRepository;

    /**
     * Result of processing an inbound AS2 message.
     */
    public record ProcessingResult(
            boolean success,
            As2Message message,
            As2Partnership partnership,
            byte[] payload,
            String filename,
            String mic,
            String errorReason
    ) {
        public static ProcessingResult failure(String reason) {
            return new ProcessingResult(false, null, null, null, null, null, reason);
        }
    }

    /**
     * Process an inbound AS2 message from raw HTTP request data.
     *
     * @param as2From       AS2-From header (partner's AS2 ID)
     * @param as2To         AS2-To header (our AS2 ID)
     * @param messageId     Message-ID header
     * @param subject       Subject header (optional)
     * @param contentType   Content-Type of the payload
     * @param body          Raw request body (the file payload)
     * @param allHeaders    All HTTP headers for audit
     * @return ProcessingResult with parsed data or error
     */
    public ProcessingResult process(String as2From, String as2To, String messageId,
                                     String subject, String contentType, byte[] body,
                                     Map<String, String> allHeaders) {
        // 1. Validate required headers
        if (as2From == null || as2From.isBlank()) {
            return ProcessingResult.failure("Missing AS2-From header");
        }
        if (as2To == null || as2To.isBlank()) {
            return ProcessingResult.failure("Missing AS2-To header");
        }
        if (messageId == null || messageId.isBlank()) {
            return ProcessingResult.failure("Missing Message-ID header");
        }

        // Strip angle brackets from Message-ID if present: <uuid@host> -> uuid@host
        String cleanMessageId = messageId.replaceAll("^<|>$", "").trim();

        // 2. Duplicate detection — AS2 spec requires rejecting duplicate Message-IDs
        Optional<As2Message> existingMsg = messageRepository.findByMessageId(cleanMessageId);
        if (existingMsg.isPresent()) {
            log.warn("Duplicate AS2 Message-ID received: {}", cleanMessageId);
            return ProcessingResult.failure("Duplicate Message-ID: " + cleanMessageId);
        }

        // 3. Look up trading partner by AS2-From
        Optional<As2Partnership> partnerOpt = partnershipRepository.findByPartnerAs2IdAndActiveTrue(as2From);
        if (partnerOpt.isEmpty()) {
            log.error("Unknown AS2 trading partner: {}", as2From);
            return ProcessingResult.failure("Unknown trading partner: " + as2From);
        }
        As2Partnership partnership = partnerOpt.get();

        // 4. Verify AS2-To matches our configured AS2 ID
        if (!partnership.getOurAs2Id().equalsIgnoreCase(as2To)) {
            log.error("AS2-To mismatch: expected={} received={}", partnership.getOurAs2Id(), as2To);
            return ProcessingResult.failure("AS2-To does not match our AS2 ID");
        }

        // 5. Extract filename from Content-Disposition or Subject
        String filename = extractFilename(allHeaders, subject, cleanMessageId);

        // 6. Compute MIC (Message Integrity Check) for non-repudiation
        String mic = computeMic(body, partnership.getSigningAlgorithm());

        // 7. Create message record
        As2Message message = new As2Message();
        message.setMessageId(cleanMessageId);
        message.setPartnership(partnership);
        message.setDirection("INBOUND");
        message.setFilename(filename);
        message.setFileSize((long) body.length);
        message.setStatus("RECEIVED");
        messageRepository.save(message);

        log.info("AS2 message processed: messageId={} from={} file={} size={}",
                cleanMessageId, as2From, filename, body.length);

        return new ProcessingResult(true, message, partnership, body, filename, mic, null);
    }

    /**
     * Extract filename from Content-Disposition header, Subject, or generate from Message-ID.
     */
    private String extractFilename(Map<String, String> headers, String subject, String messageId) {
        // Try Content-Disposition: attachment; filename="invoice.edi"
        String disposition = headers.getOrDefault("Content-Disposition",
                headers.getOrDefault("content-disposition", ""));
        if (disposition.contains("filename=")) {
            String fn = disposition.substring(disposition.indexOf("filename=") + 9).trim();
            fn = fn.replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");
            if (!fn.isBlank()) return fn;
        }

        // Try Subject as filename hint
        if (subject != null && !subject.isBlank() && subject.contains(".")) {
            return subject.trim();
        }

        // Generate from Message-ID
        String base = messageId.contains("@") ? messageId.substring(0, messageId.indexOf("@")) : messageId;
        return base + ".dat";
    }

    /**
     * Compute Message Integrity Check (SHA digest of payload).
     */
    private String computeMic(byte[] payload, String algorithm) {
        try {
            String jcaAlg = switch (algorithm != null ? algorithm.toUpperCase() : "SHA256") {
                case "SHA1" -> "SHA-1";
                case "SHA384" -> "SHA-384";
                case "SHA512" -> "SHA-512";
                default -> "SHA-256";
            };
            MessageDigest digest = MessageDigest.getInstance(jcaAlg);
            byte[] hash = digest.digest(payload);
            return Base64.getEncoder().encodeToString(hash) + ", " + jcaAlg;
        } catch (Exception e) {
            log.warn("Failed to compute MIC: {}", e.getMessage());
            return "unknown";
        }
    }
}
