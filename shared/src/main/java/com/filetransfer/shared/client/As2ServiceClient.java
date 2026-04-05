package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for the AS2 Service (port 8094).
 * Sends AS2/AS4 messages to the AS2 service for outbound delivery.
 *
 * <p>Error strategy: <b>fail-fast</b> — AS2 message delivery must succeed
 * or return a clear error for MDN handling and retry logic.
 */
@Slf4j
@Component
public class As2ServiceClient extends BaseServiceClient {

    public As2ServiceClient(RestTemplate restTemplate,
                            PlatformConfig platformConfig,
                            ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getAs2Service(), "as2-service");
    }

    /**
     * Send an AS2 message to a partner.
     *
     * @param as2From our AS2 ID
     * @param as2To partner's AS2 ID
     * @param messageId unique message ID
     * @param subject message subject
     * @param payload the file bytes to send
     * @param contentType MIME type of the payload
     * @return the MDN response
     */
    public String sendAs2Message(String as2From, String as2To, String messageId,
                                  String subject, byte[] payload, String contentType) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    contentType != null ? contentType : "application/octet-stream"));
            headers.set("X-Internal-Key", platformConfig.getSecurity().getControlApiKey());
            headers.set("AS2-From", as2From);
            headers.set("AS2-To", as2To);
            headers.set("Message-ID", messageId);
            if (subject != null) headers.set("Subject", subject);

            HttpEntity<byte[]> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl() + "/as2/send", entity, String.class);
            return response.getBody();
        } catch (Exception e) {
            throw serviceError("sendAs2Message", e);
        }
    }

    /**
     * Send an AS4 message to a partner.
     *
     * @param partnerId the AS4 partner ID
     * @param payload the file bytes to send
     * @param metadata additional AS4 metadata
     * @return the AS4 receipt
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendAs4Message(String partnerId, byte[] payload,
                                               Map<String, String> metadata) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("partnerId", partnerId);
            if (metadata != null) params.putAll(metadata);
            return postMultipartBytes("/api/as4/send", "payload", payload, params);
        } catch (Exception e) {
            throw serviceError("sendAs4Message", e);
        }
    }

    /** Upload a file to the AS2 service for processing. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> uploadFile(String filename, byte[] fileBytes,
                                           String account, String trackId) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            if (account != null) params.put("account", account);
            if (trackId != null) params.put("trackId", trackId);
            return postMultipartBytes("/api/files/upload", filename, fileBytes, params);
        } catch (Exception e) {
            throw serviceError("uploadFile", e);
        }
    }

    @Override
    protected String healthPath() {
        return "/api/health";
    }
}
