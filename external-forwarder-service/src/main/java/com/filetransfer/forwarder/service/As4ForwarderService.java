package com.filetransfer.forwarder.service;

import com.filetransfer.shared.crypto.CredentialCryptoClient;
import com.filetransfer.shared.entity.integration.As2Message;
import com.filetransfer.shared.entity.integration.As2Partnership;
import com.filetransfer.shared.repository.integration.As2MessageRepository;
import com.filetransfer.shared.repository.integration.As2PartnershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * AS4 (OASIS ebMS3/AS4) forwarder.
 *
 * AS4 is the modern successor to AS2 for B2B messaging, built on the
 * OASIS ebMS3 (ebXML Messaging Service 3.0) standard. Key differences from AS2:
 *   - SOAP-based envelope (instead of raw MIME)
 *   - Web Services Security (WS-Security) for signing/encryption
 *   - Pull mode support (receiver can initiate message retrieval)
 *   - Better reliability patterns (duplicate detection, retry)
 *   - Used by Peppol, eDelivery, and EU B2B networks
 *
 * This implementation wraps the file payload in a minimal SOAP/ebMS3 envelope
 * and sends it via HTTP POST. Full WS-Security signing/encryption requires
 * integration with Apache WSS4J (future enhancement).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class As4ForwarderService {

    private final As2PartnershipRepository partnershipRepository;
    private final As2MessageRepository messageRepository;
    private final CredentialCryptoClient credentialCrypto;

    @org.springframework.beans.factory.annotation.Value("${forwarder.as4.connect-timeout-ms:30000}")
    private int connectTimeoutMs = 30_000;

    @org.springframework.beans.factory.annotation.Value("${forwarder.as4.read-timeout-ms:60000}")
    private int readTimeoutMs = 60_000;

    @org.springframework.beans.factory.annotation.Value("${forwarder.as4.signing-enabled:true}")
    private boolean signingEnabled;

    @org.springframework.beans.factory.annotation.Value("${forwarder.as4.signing-secret:${AS4_SIGNING_SECRET:platform-default-signing-key}}")
    private String signingSecret;

    /**
     * Send a file to a trading partner via AS4 (ebMS3).
     */
    public As2Message forward(As2Partnership partnership, String filename, byte[] fileBytes, String trackId) throws Exception {
        String messageId = UUID.randomUUID() + "@" + partnership.getOurAs2Id().replaceAll("[^a-zA-Z0-9._-]", "");

        As2Message message = As2Message.builder()
                .messageId(messageId)
                .partnership(partnership)
                .direction("OUTBOUND")
                .filename(filename)
                .fileSize((long) fileBytes.length)
                .status("SENDING")
                .trackId(trackId)
                .build();
        message = messageRepository.save(message);

        try {
            // Build SOAP envelope with ebMS3 headers
            String soapEnvelope = buildEbms3Envelope(partnership, messageId, filename, fileBytes);

            // Apply WS-Security signing if enabled
            if (signingEnabled) {
                soapEnvelope = addSecurityHeader(soapEnvelope, fileBytes);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_XML);
            headers.set("SOAPAction", "");
            headers.set("MIME-Version", "1.0");

            HttpEntity<String> entity = new HttpEntity<>(soapEnvelope, headers);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setReadTimeout(readTimeoutMs);
            RestTemplate rest = new RestTemplate(factory);

            log.info("[{}] AS4 sending: {} -> {} ({} bytes)",
                    trackId, partnership.getOurAs2Id(), partnership.getPartnerAs2Id(), fileBytes.length);

            ResponseEntity<String> response = rest.exchange(
                    partnership.getEndpointUrl(), HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                String receiptStatus = parseEbms3Receipt(response.getBody());
                message.setStatus("SENT");
                message.setMdnReceived(receiptStatus != null);
                message.setMdnStatus(receiptStatus);

                if (receiptStatus != null && receiptStatus.contains("Receipt")) {
                    message.setStatus("ACKNOWLEDGED");
                }

                log.info("[{}] AS4 delivery successful: msgId={}", trackId, messageId);
            } else {
                throw new RuntimeException("AS4 HTTP response: " + response.getStatusCode());
            }

            message.setUpdatedAt(Instant.now());
            messageRepository.save(message);
            return message;

        } catch (Exception e) {
            message.setStatus("FAILED");
            message.setErrorMessage(e.getMessage());
            message.setUpdatedAt(Instant.now());
            messageRepository.save(message);

            log.error("[{}] AS4 delivery failed to {}: {}", trackId,
                    partnership.getPartnerAs2Id(), e.getMessage());
            throw e;
        }
    }

    /**
     * Send a file using partnership ID lookup.
     */
    public As2Message forward(UUID partnershipId, String filename, byte[] fileBytes, String trackId) throws Exception {
        As2Partnership partnership = partnershipRepository.findById(partnershipId)
                .filter(p -> p.isActive() && "AS4".equals(p.getProtocol()))
                .orElseThrow(() -> new IllegalArgumentException("AS4 partnership not found or inactive: " + partnershipId));
        return forward(partnership, filename, fileBytes, trackId);
    }

    /**
     * Build a minimal ebMS3 SOAP envelope.
     * The payload is Base64-encoded and included as a SOAP attachment reference.
     *
     * A production implementation would use full MIME multipart with
     * WS-Security (Apache WSS4J) for digital signatures and encryption.
     */
    private String buildEbms3Envelope(As2Partnership partnership, String messageId,
                                       String filename, byte[] fileBytes) {
        String base64Payload = java.util.Base64.getEncoder().encodeToString(fileBytes);
        String conversationId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                    <soap:Header>
                        <eb:Messaging>
                            <eb:UserMessage>
                                <eb:MessageInfo>
                                    <eb:Timestamp>%s</eb:Timestamp>
                                    <eb:MessageId>%s</eb:MessageId>
                                </eb:MessageInfo>
                                <eb:PartyInfo>
                                    <eb:From>
                                        <eb:PartyId type="urn:oasis:names:tc:ebcore:partyid-type:unregistered">%s</eb:PartyId>
                                        <eb:Role>http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/initiator</eb:Role>
                                    </eb:From>
                                    <eb:To>
                                        <eb:PartyId type="urn:oasis:names:tc:ebcore:partyid-type:unregistered">%s</eb:PartyId>
                                        <eb:Role>http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/responder</eb:Role>
                                    </eb:To>
                                </eb:PartyInfo>
                                <eb:CollaborationInfo>
                                    <eb:ConversationId>%s</eb:ConversationId>
                                    <eb:Action>DeliverFile</eb:Action>
                                </eb:CollaborationInfo>
                                <eb:PayloadInfo>
                                    <eb:PartInfo href="cid:payload">
                                        <eb:PartProperties>
                                            <eb:Property name="OriginalFileName">%s</eb:Property>
                                            <eb:Property name="MimeType">application/octet-stream</eb:Property>
                                        </eb:PartProperties>
                                    </eb:PartInfo>
                                </eb:PayloadInfo>
                            </eb:UserMessage>
                        </eb:Messaging>
                    </soap:Header>
                    <soap:Body>
                        <Payload id="payload" encoding="base64">%s</Payload>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(timestamp, messageId,
                partnership.getOurAs2Id(), partnership.getPartnerAs2Id(),
                conversationId, filename, base64Payload);
    }

    /**
     * Add WS-Security header with HMAC-SHA256 signature to the SOAP envelope.
     * Computes a digest of the payload and signs it using the configured secret.
     * Falls back to unsigned envelope if signing fails (graceful degradation).
     */
    private String addSecurityHeader(String soapEnvelope, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(
                    signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] sig = mac.doFinal(payload);
            String signatureValue = Base64.getEncoder().encodeToString(sig);
            String digestValue = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(payload));

            String securityHeader = """
                    <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                        <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                            <ds:SignedInfo>
                                <ds:CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
                                <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#hmac-sha256"/>
                                <ds:Reference URI="#body">
                                    <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                                    <ds:DigestValue>%s</ds:DigestValue>
                                </ds:Reference>
                            </ds:SignedInfo>
                            <ds:SignatureValue>%s</ds:SignatureValue>
                        </ds:Signature>
                    </wsse:Security>""".formatted(digestValue, signatureValue);

            // Insert security header into the SOAP Header element
            if (soapEnvelope.contains("<soap:Header>")) {
                return soapEnvelope.replace("<soap:Header>", "<soap:Header>" + securityHeader);
            }
            // Should not happen with our envelope, but handle defensively
            log.warn("SOAP envelope has no <soap:Header> element, cannot insert WS-Security header");
            return soapEnvelope;
        } catch (Exception e) {
            log.warn("WS-Security signing failed, sending unsigned: {}", e.getMessage());
            return soapEnvelope;
        }
    }

    /**
     * Parse ebMS3 receipt signal from response.
     */
    private String parseEbms3Receipt(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        if (responseBody.contains("Receipt") || responseBody.contains("SignalMessage")) {
            return "Receipt";
        }
        if (responseBody.contains("Error")) {
            return "Error";
        }
        return null;
    }
}
