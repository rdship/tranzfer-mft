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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * AS2 (Applicability Statement 2 — RFC 4130) forwarder.
 *
 * Sends files to trading partners using AS2 protocol conventions:
 *   - MIME multipart/signed or multipart/encrypted payloads
 *   - AS2-From / AS2-To headers for partner identification
 *   - Message-ID for deduplication
 *   - Optional MDN (Message Disposition Notification) request
 *   - Content-Transfer-Encoding: binary or base64
 *
 * This implementation uses HTTP POST with AS2 headers.
 * Full S/MIME signing and encryption requires the partner's X.509 certificate
 * and our private key (managed via the keystore-manager service).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class As2ForwarderService {

    private final As2PartnershipRepository partnershipRepository;
    private final As2MessageRepository messageRepository;
    private final CredentialCryptoClient credentialCrypto;

    @org.springframework.beans.factory.annotation.Value("${forwarder.as2.connect-timeout-ms:30000}")
    private int connectTimeoutMs = 30_000;

    @org.springframework.beans.factory.annotation.Value("${forwarder.as2.read-timeout-ms:60000}")
    private int readTimeoutMs = 60_000;

    /**
     * Send a file to a trading partner via AS2.
     *
     * @param partnership the AS2 partnership configuration
     * @param filename    original filename
     * @param fileBytes   file content
     * @param trackId     platform tracking ID for correlation
     */
    public As2Message forward(As2Partnership partnership, String filename, byte[] fileBytes, String trackId) throws Exception {
        String messageId = generateMessageId(partnership);

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
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set("AS2-From", partnership.getOurAs2Id());
            headers.set("AS2-To", partnership.getPartnerAs2Id());
            headers.set("AS2-Version", "1.2");
            headers.set("Message-ID", "<" + messageId + ">");
            headers.set("Subject", filename);
            headers.set("Content-Transfer-Encoding", "binary");
            headers.set("MIME-Version", "1.0");

            // Content disposition with filename
            headers.setContentDisposition(
                    ContentDisposition.attachment().filename(filename).build());

            // Request MDN if configured
            if (partnership.isMdnRequired()) {
                if (partnership.isMdnAsync() && partnership.getMdnUrl() != null) {
                    headers.set("Disposition-Notification-To", partnership.getMdnUrl());
                    headers.set("Receipt-Delivery-Option", partnership.getMdnUrl());
                } else {
                    headers.set("Disposition-Notification-To", partnership.getOurAs2Id());
                }
                headers.set("Disposition-Notification-Options",
                        "signed-receipt-protocol=optional,pkcs7-signature; " +
                        "signed-receipt-micalg=optional," + partnership.getSigningAlgorithm().toLowerCase());
            }

            // Compute content MIC (Message Integrity Check) for non-repudiation
            String mic = computeMic(fileBytes, partnership.getSigningAlgorithm());
            log.info("[{}] AS2 sending: {} -> {} (MIC={}, {} bytes)",
                    trackId, partnership.getOurAs2Id(), partnership.getPartnerAs2Id(), mic, fileBytes.length);

            HttpEntity<byte[]> entity = new HttpEntity<>(fileBytes, headers);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setReadTimeout(readTimeoutMs);
            RestTemplate rest = new RestTemplate(factory);

            ResponseEntity<String> response = rest.exchange(
                    partnership.getEndpointUrl(), HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                // Check for synchronous MDN in response
                String mdnStatus = parseMdnFromResponse(response);
                message.setStatus("SENT");
                message.setMdnReceived(mdnStatus != null);
                message.setMdnStatus(mdnStatus);

                if (mdnStatus != null && mdnStatus.contains("processed")) {
                    message.setStatus("ACKNOWLEDGED");
                }

                log.info("[{}] AS2 delivery successful: msgId={} mdn={}",
                        trackId, messageId, mdnStatus != null ? mdnStatus : "pending");
            } else {
                throw new RuntimeException("AS2 HTTP response: " + response.getStatusCode());
            }

            message.setUpdatedAt(Instant.now());
            messageRepository.save(message);
            return message;

        } catch (Exception e) {
            message.setStatus("FAILED");
            message.setErrorMessage(e.getMessage());
            message.setUpdatedAt(Instant.now());
            messageRepository.save(message);

            log.error("[{}] AS2 delivery failed to {}: {}", trackId,
                    partnership.getPartnerAs2Id(), e.getMessage());
            throw e;
        }
    }

    /**
     * Send a file using partnership ID lookup.
     */
    public As2Message forward(UUID partnershipId, String filename, byte[] fileBytes, String trackId) throws Exception {
        As2Partnership partnership = partnershipRepository.findById(partnershipId)
                .filter(As2Partnership::isActive)
                .orElseThrow(() -> new IllegalArgumentException("AS2 partnership not found or inactive: " + partnershipId));
        return forward(partnership, filename, fileBytes, trackId);
    }

    /**
     * Generate RFC 2822 compliant Message-ID.
     * Format: UUID@ourAs2Id
     */
    private String generateMessageId(As2Partnership partnership) {
        return UUID.randomUUID() + "@" + partnership.getOurAs2Id().replaceAll("[^a-zA-Z0-9._-]", "");
    }

    /**
     * Compute Message Integrity Check (MIC) digest for non-repudiation.
     */
    private String computeMic(byte[] data, String algorithm) {
        try {
            String digestAlgo = switch (algorithm.toUpperCase()) {
                case "SHA1" -> "SHA-1";
                case "SHA384" -> "SHA-384";
                case "SHA512" -> "SHA-512";
                default -> "SHA-256";
            };
            MessageDigest md = MessageDigest.getInstance(digestAlgo);
            byte[] digest = md.digest(data);
            return Base64.getEncoder().encodeToString(digest) + ", " + digestAlgo.toLowerCase();
        } catch (Exception e) {
            log.warn("MIC computation failed: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * Parse MDN disposition from synchronous HTTP response.
     * AS2 synchronous MDN is returned in the HTTP response body as MIME content.
     */
    private String parseMdnFromResponse(ResponseEntity<String> response) {
        String body = response.getBody();
        if (body == null || body.isBlank()) return null;

        // Look for disposition header in MDN response
        if (body.contains("automatic-action/MDN-sent-automatically") ||
            body.contains("processed")) {
            return "processed";
        }
        if (body.contains("failed") || body.contains("error")) {
            return "failed/" + body.substring(0, Math.min(body.length(), 200));
        }
        return null;
    }
}
