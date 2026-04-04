package com.filetransfer.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/**
 * Client configuration loaded from mft-client.yml.
 * This is the ONLY file a user needs to configure.
 */
@Data @Slf4j
public class ClientConfig {

    private ServerConnection server = new ServerConnection();
    private ClientFolders folders = new ClientFolders();
    private SyncSettings sync = new SyncSettings();
    private String clientName = "mft-client";
    private String logLevel = "INFO";

    @Data
    public static class ServerConnection {
        /** Protocol: SFTP or FTP */
        private String protocol = "SFTP";
        /** TranzFer server hostname/IP */
        private String host = "localhost";
        /** Server port (default: 2222 for SFTP, 21 for FTP) */
        private int port = 2222;
        /** Transfer account username (given by admin) */
        private String username;
        /** Transfer account password */
        private String password;
        /** Path to SSH private key (optional, SFTP only) */
        private String privateKeyPath;
        /** Server fingerprint for verification (optional) */
        private String hostFingerprint;
        /** Connection timeout in seconds */
        private int timeoutSeconds = 30;
        /** Auto-retry on failure */
        private boolean autoRetry = true;
        private int maxRetries = 3;
    }

    @Data
    public static class ClientFolders {
        /**
         * Local folder to WATCH for outgoing files.
         * Any file placed here is automatically uploaded to the server's /inbox.
         * After successful upload, files are moved to the 'sent' subfolder.
         */
        private String outbox = "./outbox";

        /**
         * Local folder where INCOMING files from the server are downloaded.
         * The client polls the server's /outbox for new files and downloads them here.
         */
        private String inbox = "./inbox";

        /**
         * Archive folder for uploaded files (moved here after successful upload).
         */
        private String sent = "./sent";

        /**
         * Failed uploads are moved here for manual retry.
         */
        private String failed = "./failed";

        /** Remote server paths */
        private String remoteInbox = "/inbox";
        private String remoteOutbox = "/outbox";
    }

    @Data
    public static class SyncSettings {
        /** Watch local outbox folder for new files (upload automatically) */
        private boolean watchOutbox = true;

        /** Poll server outbox for new files (download automatically) */
        private boolean pollInbox = true;

        /** Poll interval in seconds for checking server for new files */
        private int pollIntervalSeconds = 30;

        /** Delete files from server after successful download */
        private boolean deleteAfterDownload = true;

        /** File pattern to include (regex, null = all) */
        private String includePattern;

        /** File pattern to exclude (regex, null = none) */
        private String excludePattern;

        /** Maximum file size in MB (0 = unlimited) */
        private int maxFileSizeMb = 0;
    }

    @Data
    public static class P2PSettings {
        /** Enable peer-to-peer direct transfers */
        private boolean enabled = true;

        /** Port for the embedded P2P receiver */
        private int receiverPort = 9900;

        /** Server URL for ticket coordination */
        private String serverUrl = "http://localhost:8080";

        /** Validate tickets with server before accepting files */
        private boolean validateTickets = true;

        /** This client's externally reachable hostname/IP (for presence registration) */
        private String externalHost = "localhost";
    }

    private P2PSettings p2p = new P2PSettings();

    /**
     * Load config from YAML file, or create default config file if missing.
     */
    public static ClientConfig load(Path configPath) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

        if (!Files.exists(configPath)) {
            log.info("Config file not found. Creating default: {}", configPath);
            ClientConfig defaults = new ClientConfig();
            defaults.getServer().setUsername("your-username");
            defaults.getServer().setPassword("your-password");
            yaml.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), defaults);
            log.info("Edit {} and restart the client.", configPath);
            System.out.println("\n  Configuration file created: " + configPath.toAbsolutePath());
            System.out.println("  Edit the file with your server details and run again.\n");
            System.exit(0);
        }

        ClientConfig config = yaml.readValue(configPath.toFile(), ClientConfig.class);
        log.info("Loaded config: server={}://{}:{} user={}",
                config.getServer().getProtocol(), config.getServer().getHost(),
                config.getServer().getPort(), config.getServer().getUsername());
        return config;
    }
}
