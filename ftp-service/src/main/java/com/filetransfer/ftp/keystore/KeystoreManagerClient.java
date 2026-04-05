package com.filetransfer.ftp.keystore;

import com.filetransfer.shared.client.KeystoreServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FTP-specific adapter for the centralized Keystore Manager.
 * Delegates to {@link KeystoreServiceClient} from the shared module.
 *
 * <p>If the Keystore Manager is unavailable, the FTP service falls back to
 * local self-signed certificate generation for backward compatibility.
 */
@Slf4j
@Component
public class KeystoreManagerClient {

    private final KeystoreServiceClient keystoreService;
    private final String instanceId;

    public KeystoreManagerClient(
            KeystoreServiceClient keystoreService,
            @Value("${ftp.instance-id:ftp-default}") String instanceId) {
        this.keystoreService = keystoreService;
        this.instanceId = instanceId;
    }

    public boolean isEnabled() {
        return keystoreService.isEnabled();
    }

    public String getTlsCertificate(String alias) {
        if (!isEnabled()) return null;
        return keystoreService.getTlsCertificate(alias);
    }

    public String generateAndStoreTlsCert(String alias, String cn, int validDays) {
        if (!isEnabled()) return null;
        return keystoreService.generateAndStoreTlsCert(alias, cn, validDays);
    }

    public void importTlsKeystore(String alias, String keystoreBase64) {
        if (!isEnabled()) return;
        keystoreService.importTlsKeystore(alias, keystoreBase64, "ftp-service-" + instanceId);
    }

    public String getDefaultAlias() {
        return "ftp-tls-cert-" + instanceId;
    }
}
