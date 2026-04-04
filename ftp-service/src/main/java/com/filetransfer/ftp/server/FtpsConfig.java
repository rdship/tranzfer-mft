package com.filetransfer.ftp.server;

import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * FTPS (FTP over TLS) configuration.
 * When enabled, the FTP server supports both explicit and implicit FTPS.
 *
 * Explicit FTPS: Client connects on port 21, upgrades to TLS via AUTH TLS
 * Implicit FTPS: Client connects directly to TLS port (990)
 */
@Component
@Slf4j
public class FtpsConfig {

    @Value("${ftp.ftps.enabled:false}")
    private boolean enabled;

    @Value("${ftp.ftps.keystore-path:./ftp-keystore.jks}")
    private String keystorePath;

    @Value("${ftp.ftps.keystore-password:changeit}")
    private String keystorePassword;

    @Value("${ftp.ftps.protocol:TLSv1.2}")
    private String protocol;

    @Value("${ftp.ftps.client-auth:false}")
    private boolean clientAuth;

    public boolean isEnabled() { return enabled; }

    /**
     * Build SSL configuration for Apache FTP Server.
     * Returns null if FTPS is disabled or keystore not found.
     */
    public SslConfiguration buildSslConfig() {
        if (!enabled) {
            log.info("FTPS disabled. To enable: set ftp.ftps.enabled=true and provide keystore");
            return null;
        }

        File ksFile = new File(keystorePath);
        if (!ksFile.exists()) {
            log.warn("FTPS enabled but keystore not found at {}. Generating self-signed...", keystorePath);
            generateSelfSignedKeystore(ksFile);
        }

        try {
            SslConfigurationFactory sslFactory = new SslConfigurationFactory();
            sslFactory.setKeystoreFile(ksFile);
            sslFactory.setKeystorePassword(keystorePassword);
            sslFactory.setSslProtocol(protocol);
            sslFactory.setClientAuthentication(clientAuth ? "NEED" : "NONE");

            log.info("FTPS configured: protocol={}, clientAuth={}, keystore={}", protocol, clientAuth, keystorePath);
            return sslFactory.createSslConfiguration();
        } catch (Exception e) {
            log.error("FTPS configuration failed: {}", e.getMessage());
            return null;
        }
    }

    private void generateSelfSignedKeystore(File ksFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("keytool",
                    "-genkeypair", "-alias", "ftps",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-keystore", ksFile.getAbsolutePath(),
                    "-storepass", keystorePassword,
                    "-dname", "CN=TranzFer FTPS,O=TranzFer MFT,C=US",
                    "-keypass", keystorePassword);
            pb.inheritIO();
            Process p = pb.start();
            p.waitFor();
            log.info("Generated self-signed FTPS keystore at {}", ksFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("Could not generate keystore: {}", e.getMessage());
        }
    }
}
