package com.filetransfer.sftp.keystore;

import com.filetransfer.shared.client.KeystoreServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SFTP-specific adapter for the centralized Keystore Manager.
 * Delegates to {@link KeystoreServiceClient} from the shared module.
 *
 * <p>If the Keystore Manager is unavailable, the SFTP service falls back to
 * local key generation for backward compatibility.
 */
@Slf4j
@Component
public class KeystoreManagerClient {

    private final KeystoreServiceClient keystoreService;
    private final String instanceId;

    public KeystoreManagerClient(
            KeystoreServiceClient keystoreService,
            @Value("${sftp.instance-id:sftp-default}") String instanceId) {
        this.keystoreService = keystoreService;
        this.instanceId = instanceId;
    }

    public boolean isEnabled() {
        return keystoreService.isEnabled();
    }

    public String getHostKey(String alias) {
        if (!isEnabled()) return null;
        return keystoreService.getHostKey(alias);
    }

    public String generateAndStoreHostKey(String alias) {
        if (!isEnabled()) return null;
        return keystoreService.generateAndStoreHostKey(alias, "sftp-service-" + instanceId);
    }

    public void importHostKey(String alias, String keyMaterial) {
        if (!isEnabled()) return;
        keystoreService.importHostKey(alias, keyMaterial, "sftp-service-" + instanceId);
    }

    public String getDefaultAlias() {
        return "sftp-host-key-" + instanceId;
    }
}
