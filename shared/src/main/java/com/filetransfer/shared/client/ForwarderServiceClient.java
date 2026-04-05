package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for the External Forwarder Service (port 8087).
 * Triggers file delivery to external partners via SFTP, FTP, FTPS, HTTP, AS2, or AS4.
 *
 * <p>Error strategy: <b>fail-fast</b> — forwarding requests must succeed or
 * return a clear error for retry decisions.
 */
@Slf4j
@Component
public class ForwarderServiceClient extends BaseServiceClient {

    public ForwarderServiceClient(RestTemplate restTemplate,
                                  PlatformConfig platformConfig,
                                  ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getForwarderService(), "external-forwarder-service");
    }

    /**
     * Forward a file to an external destination.
     *
     * @param filename the file name
     * @param fileBytes the file content
     * @param destinationId the external destination ID
     * @param trackId the transfer tracking ID
     * @return delivery result metadata
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> forward(String filename, byte[] fileBytes,
                                        String destinationId, String trackId) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            if (destinationId != null) params.put("destinationId", destinationId);
            if (trackId != null) params.put("trackId", trackId);
            return postMultipartBytes("/api/forwarder/forward", filename, fileBytes, params);
        } catch (Exception e) {
            throw serviceError("forward", e);
        }
    }

    /**
     * Trigger delivery to a specific delivery endpoint.
     *
     * @param endpointId the delivery endpoint ID
     * @param filename the file name
     * @param fileBytes the file content
     * @param trackId the transfer tracking ID
     * @return delivery result metadata
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deliverToEndpoint(String endpointId, String filename,
                                                  byte[] fileBytes, String trackId) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("endpointId", endpointId);
            if (trackId != null) params.put("trackId", trackId);
            return postMultipartBytes("/api/forwarder/deliver", filename, fileBytes, params);
        } catch (Exception e) {
            throw serviceError("deliverToEndpoint", e);
        }
    }
}
