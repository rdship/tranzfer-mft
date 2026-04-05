package com.filetransfer.as2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Generates AS2 MDN (Message Disposition Notification) responses per RFC 4130 Section 7.
 * MDN is the receipt acknowledgment that trading partners use to confirm message delivery.
 */
@Slf4j
@Service
public class MdnGenerator {

    private static final DateTimeFormatter RFC_2822 = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z");

    /**
     * MDN response data holder.
     */
    public record MdnResponse(
            String body,
            String contentType,
            String messageId,
            boolean success
    ) {}

    /**
     * Generate a synchronous MDN for a successfully processed AS2 message.
     *
     * @param originalMessageId Message-ID of the received message
     * @param ourAs2Id          Our AS2 identifier (goes in AS2-From of MDN)
     * @param partnerAs2Id      Partner's AS2 identifier (goes in AS2-To of MDN)
     * @param mic               Message Integrity Check computed on received payload
     * @return MDN response body and content type
     */
    public MdnResponse generateSuccess(String originalMessageId, String ourAs2Id,
                                         String partnerAs2Id, String mic) {
        String mdnMessageId = "<" + UUID.randomUUID() + "@" + ourAs2Id + ">";
        String date = ZonedDateTime.now().format(RFC_2822);

        String boundary = "----=_mdn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // Build multipart/report MDN per RFC 3798 + RFC 4130
        StringBuilder mdn = new StringBuilder();
        mdn.append("--").append(boundary).append("\r\n");
        mdn.append("Content-Type: text/plain\r\n\r\n");
        mdn.append("The AS2 message has been received and processed successfully.\r\n");
        mdn.append("Original Message-ID: ").append(originalMessageId).append("\r\n");
        mdn.append("Date: ").append(date).append("\r\n");
        mdn.append("\r\n");
        mdn.append("--").append(boundary).append("\r\n");
        mdn.append("Content-Type: message/disposition-notification\r\n\r\n");
        mdn.append("Reporting-UA: TranzFer-MFT/AS2-Service\r\n");
        mdn.append("Original-Recipient: rfc822; ").append(ourAs2Id).append("\r\n");
        mdn.append("Final-Recipient: rfc822; ").append(ourAs2Id).append("\r\n");
        mdn.append("Original-Message-ID: ").append(originalMessageId).append("\r\n");
        mdn.append("Disposition: automatic-action/MDN-sent-automatically; processed\r\n");
        if (mic != null && !mic.isBlank()) {
            mdn.append("Received-Content-MIC: ").append(mic).append("\r\n");
        }
        mdn.append("\r\n");
        mdn.append("--").append(boundary).append("--\r\n");

        String contentType = "multipart/report; report-type=disposition-notification; boundary=\"" + boundary + "\"";

        log.info("Generated success MDN for message={} mdnId={}", originalMessageId, mdnMessageId);
        return new MdnResponse(mdn.toString(), contentType, mdnMessageId, true);
    }

    /**
     * Generate an error MDN for a failed AS2 message processing.
     */
    public MdnResponse generateError(String originalMessageId, String ourAs2Id,
                                       String partnerAs2Id, String errorDescription) {
        String mdnMessageId = "<" + UUID.randomUUID() + "@" + ourAs2Id + ">";
        String boundary = "----=_mdn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        StringBuilder mdn = new StringBuilder();
        mdn.append("--").append(boundary).append("\r\n");
        mdn.append("Content-Type: text/plain\r\n\r\n");
        mdn.append("AS2 message processing failed.\r\n");
        mdn.append("Original Message-ID: ").append(originalMessageId != null ? originalMessageId : "unknown").append("\r\n");
        mdn.append("Error: ").append(errorDescription).append("\r\n");
        mdn.append("\r\n");
        mdn.append("--").append(boundary).append("\r\n");
        mdn.append("Content-Type: message/disposition-notification\r\n\r\n");
        mdn.append("Reporting-UA: TranzFer-MFT/AS2-Service\r\n");
        mdn.append("Original-Recipient: rfc822; ").append(ourAs2Id).append("\r\n");
        mdn.append("Final-Recipient: rfc822; ").append(ourAs2Id).append("\r\n");
        if (originalMessageId != null) {
            mdn.append("Original-Message-ID: ").append(originalMessageId).append("\r\n");
        }
        mdn.append("Disposition: automatic-action/MDN-sent-automatically; failed/failure: ").append(errorDescription).append("\r\n");
        mdn.append("\r\n");
        mdn.append("--").append(boundary).append("--\r\n");

        String contentType = "multipart/report; report-type=disposition-notification; boundary=\"" + boundary + "\"";

        log.info("Generated error MDN for message={}: {}", originalMessageId, errorDescription);
        return new MdnResponse(mdn.toString(), contentType, mdnMessageId, false);
    }

    /**
     * Generate an AS4 ebMS3 Receipt signal for successful message processing.
     */
    public String generateAs4Receipt(String originalMessageId, String ourPartyId, String partnerPartyId) {
        String receiptId = UUID.randomUUID().toString();
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                    <soap:Header>
                        <eb:Messaging>
                            <eb:SignalMessage>
                                <eb:MessageInfo>
                                    <eb:Timestamp>%s</eb:Timestamp>
                                    <eb:MessageId>%s</eb:MessageId>
                                    <eb:RefToMessageId>%s</eb:RefToMessageId>
                                </eb:MessageInfo>
                                <eb:Receipt>
                                    <NonRepudiationInformation>
                                        <MessagePartNRInformation>
                                            <Reference URI="cid:payload"/>
                                        </MessagePartNRInformation>
                                    </NonRepudiationInformation>
                                </eb:Receipt>
                            </eb:SignalMessage>
                        </eb:Messaging>
                    </soap:Header>
                    <soap:Body/>
                </soap:Envelope>
                """.formatted(timestamp, receiptId, originalMessageId);
    }

    /**
     * Generate an AS4 ebMS3 Error signal for failed processing.
     */
    public String generateAs4Error(String originalMessageId, String errorCode, String errorDescription) {
        String errorId = UUID.randomUUID().toString();
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                    <soap:Header>
                        <eb:Messaging>
                            <eb:SignalMessage>
                                <eb:MessageInfo>
                                    <eb:Timestamp>%s</eb:Timestamp>
                                    <eb:MessageId>%s</eb:MessageId>
                                    <eb:RefToMessageId>%s</eb:RefToMessageId>
                                </eb:MessageInfo>
                                <eb:Error errorCode="%s" severity="failure" shortDescription="%s">
                                    <eb:Description xml:lang="en">%s</eb:Description>
                                </eb:Error>
                            </eb:SignalMessage>
                        </eb:Messaging>
                    </soap:Header>
                    <soap:Body/>
                </soap:Envelope>
                """.formatted(timestamp, errorId, originalMessageId != null ? originalMessageId : "unknown",
                errorCode, errorDescription, errorDescription);
    }
}
