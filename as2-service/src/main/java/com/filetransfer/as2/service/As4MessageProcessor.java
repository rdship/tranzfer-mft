package com.filetransfer.as2.service;

import com.filetransfer.shared.entity.integration.As2Message;
import com.filetransfer.shared.entity.integration.As2Partnership;
import com.filetransfer.shared.repository.As2MessageRepository;
import com.filetransfer.shared.repository.As2PartnershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes inbound AS4 (OASIS ebMS3) messages.
 * Parses the SOAP envelope, extracts ebMS3 headers (PartyInfo, MessageInfo, PayloadInfo),
 * validates partnership, and extracts the Base64-encoded payload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class As4MessageProcessor {

    private final As2PartnershipRepository partnershipRepository;
    private final As2MessageRepository messageRepository;

    private static final Pattern MSG_ID_PATTERN = Pattern.compile(
            "<eb:MessageId>([^<]+)</eb:MessageId>", Pattern.DOTALL);
    private static final Pattern FROM_PARTY_PATTERN = Pattern.compile(
            "<eb:From>\\s*<eb:PartyId[^>]*>([^<]+)</eb:PartyId>", Pattern.DOTALL);
    private static final Pattern TO_PARTY_PATTERN = Pattern.compile(
            "<eb:To>\\s*<eb:PartyId[^>]*>([^<]+)</eb:PartyId>", Pattern.DOTALL);
    private static final Pattern PAYLOAD_PATTERN = Pattern.compile(
            "<Payload[^>]*>([^<]+)</Payload>", Pattern.DOTALL);
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile(
            "<eb:ConversationId>([^<]+)</eb:ConversationId>", Pattern.DOTALL);
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "<eb:Action>([^<]+)</eb:Action>", Pattern.DOTALL);

    /**
     * Result of processing an inbound AS4 message.
     */
    public record ProcessingResult(
            boolean success,
            As2Message message,
            As2Partnership partnership,
            byte[] payload,
            String filename,
            String conversationId,
            String errorReason
    ) {
        public static ProcessingResult failure(String reason) {
            return new ProcessingResult(false, null, null, null, null, null, reason);
        }
    }

    /**
     * Process an inbound AS4 SOAP envelope.
     *
     * @param soapBody The full SOAP XML body
     * @return ProcessingResult with extracted data or error
     */
    public ProcessingResult process(String soapBody) {
        if (soapBody == null || soapBody.isBlank()) {
            return ProcessingResult.failure("Empty SOAP body");
        }

        // 1. Extract ebMS3 MessageId
        String messageId = extractPattern(MSG_ID_PATTERN, soapBody);
        if (messageId == null) {
            return ProcessingResult.failure("Missing eb:MessageId in SOAP envelope");
        }

        // 2. Duplicate detection
        Optional<As2Message> existingMsg = messageRepository.findByMessageId(messageId);
        if (existingMsg.isPresent()) {
            log.warn("Duplicate AS4 MessageId received: {}", messageId);
            return ProcessingResult.failure("Duplicate MessageId: " + messageId);
        }

        // 3. Extract PartyInfo
        String fromParty = extractPattern(FROM_PARTY_PATTERN, soapBody);
        String toParty = extractPattern(TO_PARTY_PATTERN, soapBody);
        if (fromParty == null) {
            return ProcessingResult.failure("Missing eb:From PartyId in SOAP envelope");
        }
        if (toParty == null) {
            return ProcessingResult.failure("Missing eb:To PartyId in SOAP envelope");
        }

        // 4. Look up partnership
        Optional<As2Partnership> partnerOpt = partnershipRepository.findByPartnerAs2IdAndActiveTrue(fromParty);
        if (partnerOpt.isEmpty()) {
            log.error("Unknown AS4 trading partner: {}", fromParty);
            return ProcessingResult.failure("Unknown trading partner: " + fromParty);
        }
        As2Partnership partnership = partnerOpt.get();

        // 5. Verify our PartyId matches
        if (!partnership.getOurAs2Id().equalsIgnoreCase(toParty)) {
            log.error("AS4 To-Party mismatch: expected={} received={}", partnership.getOurAs2Id(), toParty);
            return ProcessingResult.failure("To PartyId does not match our configuration");
        }

        // 6. Extract payload (Base64-encoded in SOAP body)
        String payloadBase64 = extractPattern(PAYLOAD_PATTERN, soapBody);
        if (payloadBase64 == null) {
            return ProcessingResult.failure("No payload found in SOAP envelope");
        }

        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(payloadBase64.trim());
        } catch (IllegalArgumentException e) {
            return ProcessingResult.failure("Invalid Base64 payload: " + e.getMessage());
        }

        // 7. Extract metadata
        String conversationId = extractPattern(CONVERSATION_ID_PATTERN, soapBody);
        String action = extractPattern(ACTION_PATTERN, soapBody);
        String filename = (action != null && action.contains(".")) ? action : messageId + ".dat";

        // 8. Create message record
        As2Message message = new As2Message();
        message.setMessageId(messageId);
        message.setPartnership(partnership);
        message.setDirection("INBOUND");
        message.setFilename(filename);
        message.setFileSize((long) payload.length);
        message.setStatus("RECEIVED");
        messageRepository.save(message);

        log.info("AS4 message processed: messageId={} from={} file={} size={} conversation={}",
                messageId, fromParty, filename, payload.length, conversationId);

        return new ProcessingResult(true, message, partnership, payload, filename, conversationId, null);
    }

    private String extractPattern(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
