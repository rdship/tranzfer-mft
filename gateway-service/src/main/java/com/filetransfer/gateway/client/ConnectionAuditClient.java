package com.filetransfer.gateway.client;

import com.filetransfer.shared.client.ResilientServiceClient;
import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fire-and-forget audit of inbound connections for migration tracking.
 * Calls config-service POST /api/v1/migration/audit-connection.
 *
 * <p>Error strategy: <b>graceful degradation</b> — audit failures never
 * block or delay connection routing.
 */
@Slf4j
@Component
public class ConnectionAuditClient extends ResilientServiceClient {

    public ConnectionAuditClient(RestTemplate restTemplate,
                                 PlatformConfig platformConfig,
                                 ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getConfigService(), "config-service");
    }

    /**
     * Record an inbound connection asynchronously for migration tracking.
     * Silently swallows all errors — never blocks routing.
     */
    @Async
    public void recordConnection(String username, String sourceIp, String protocol,
                                 String routedTo, String legacyHost,
                                 UUID partnerId, String partnerName,
                                 boolean success, String failureReason) {
        if (!isEnabled()) return;
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("username", username != null ? username : "");
            body.put("sourceIp", sourceIp != null ? sourceIp : "");
            body.put("protocol", protocol != null ? protocol : "");
            body.put("routedTo", routedTo);
            body.put("legacyHost", legacyHost != null ? legacyHost : "");
            body.put("partnerId", partnerId != null ? partnerId.toString() : "");
            body.put("partnerName", partnerName != null ? partnerName : "");
            body.put("success", String.valueOf(success));
            body.put("failureReason", failureReason != null ? failureReason : "");

            withResilience("auditConnection",
                    () -> post("/api/v1/migration/audit-connection", body, String.class));
        } catch (Exception e) {
            log.debug("Connection audit failed (non-critical): {}", e.getMessage());
        }
    }

    @Override
    protected String healthPath() {
        return "/actuator/health";
    }
}
