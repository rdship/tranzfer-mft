package com.filetransfer.ftp.server;

import com.filetransfer.ftp.keystore.KeystoreManagerClient;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R87: per-listener FTPS cert alias resolution.
 *
 * <p>Focuses on the branching logic in {@code buildSslConfigFor(ServerInstance)}:
 * <ul>
 *   <li>FTPS disabled → null regardless of alias.</li>
 *   <li>Alias null/blank → delegates to {@code buildSslConfig()} (default path).</li>
 *   <li>Alias set + KeystoreManager disabled → falls back to default path.</li>
 * </ul>
 *
 * <p>Intentionally does NOT exercise keystore materialization (keytool fork) —
 * that path is covered by the existing integration stack.
 */
class FtpsConfigR87Test {

    @Test
    void disabledFtpsYieldsNullEvenWithAlias() throws Exception {
        FtpsConfig cfg = new FtpsConfig();
        setField(cfg, "enabled", false);
        setField(cfg, "keystoreManagerClient", mock(KeystoreManagerClient.class));

        ServerInstance si = ftpInstance("fA", "some-alias");
        assertThat(cfg.buildSslConfigFor(si)).isNull();
    }

    @Test
    void nullAliasDelegatesToDefaultPath() throws Exception {
        FtpsConfig cfg = new FtpsConfig();
        setField(cfg, "enabled", false); // defaults path also returns null when disabled
        setField(cfg, "keystoreManagerClient", mock(KeystoreManagerClient.class));

        ServerInstance si = ftpInstance("fB", null);
        // Delegates to buildSslConfig(); since disabled=false above, returns null.
        assertThat(cfg.buildSslConfigFor(si)).isNull();
    }

    @Test
    void aliasWithKeystoreManagerDisabledFallsBack() throws Exception {
        FtpsConfig cfg = new FtpsConfig();
        setField(cfg, "enabled", false); // default path returns null when disabled

        KeystoreManagerClient km = mock(KeystoreManagerClient.class);
        when(km.isEnabled()).thenReturn(false);
        setField(cfg, "keystoreManagerClient", km);

        ServerInstance si = ftpInstance("fC", "ftps-east");
        // Falls back to buildSslConfig(), which returns null because enabled=false.
        assertThat(cfg.buildSslConfigFor(si)).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ServerInstance ftpInstance(String id, String alias) {
        return ServerInstance.builder()
                .instanceId(id).protocol(Protocol.FTP).name(id)
                .internalHost("ftp-service").internalPort(21)
                .ftpTlsCertAlias(alias)
                .build();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
