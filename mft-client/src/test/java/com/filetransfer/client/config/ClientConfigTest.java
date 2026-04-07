package com.filetransfer.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClientConfigTest {

    // ── Default values ──────────────────────────────────────────────────

    @Test
    void defaultClientConfig_hasExpectedTopLevelDefaults() {
        ClientConfig config = new ClientConfig();

        assertEquals("mft-client", config.getClientName());
        assertEquals("INFO", config.getLogLevel());
        assertNotNull(config.getServer());
        assertNotNull(config.getFolders());
        assertNotNull(config.getSync());
        assertNotNull(config.getP2p());
    }

    @Test
    void defaultServerConnection_hasExpectedDefaults() {
        ClientConfig.ServerConnection server = new ClientConfig.ServerConnection();

        assertEquals("SFTP", server.getProtocol());
        assertEquals("localhost", server.getHost());
        assertEquals(2222, server.getPort());
        assertNull(server.getUsername());
        assertNull(server.getPassword());
        assertNull(server.getPrivateKeyPath());
        assertNull(server.getHostFingerprint());
        assertEquals(30, server.getTimeoutSeconds());
        assertTrue(server.isAutoRetry());
        assertEquals(3, server.getMaxRetries());
    }

    @Test
    void defaultClientFolders_hasExpectedDefaults() {
        ClientConfig.ClientFolders folders = new ClientConfig.ClientFolders();

        assertEquals("./outbox", folders.getOutbox());
        assertEquals("./inbox", folders.getInbox());
        assertEquals("./sent", folders.getSent());
        assertEquals("./failed", folders.getFailed());
        assertEquals("/inbox", folders.getRemoteInbox());
        assertEquals("/outbox", folders.getRemoteOutbox());
    }

    @Test
    void defaultSyncSettings_hasExpectedDefaults() {
        ClientConfig.SyncSettings sync = new ClientConfig.SyncSettings();

        assertTrue(sync.isWatchOutbox());
        assertTrue(sync.isPollInbox());
        assertEquals(30, sync.getPollIntervalSeconds());
        assertTrue(sync.isDeleteAfterDownload());
        assertNull(sync.getIncludePattern());
        assertNull(sync.getExcludePattern());
        assertEquals(0, sync.getMaxFileSizeMb());
    }

    @Test
    void defaultP2PSettings_hasExpectedDefaults() {
        ClientConfig.P2PSettings p2p = new ClientConfig.P2PSettings();

        assertTrue(p2p.isEnabled());
        assertEquals(9900, p2p.getReceiverPort());
        assertEquals("http://localhost:8080", p2p.getServerUrl());
        assertTrue(p2p.isValidateTickets());
        assertEquals("localhost", p2p.getExternalHost());
    }

    // ── Setter/getter roundtrip ─────────────────────────────────────────

    @Test
    void serverConnection_settersWork() {
        ClientConfig.ServerConnection server = new ClientConfig.ServerConnection();
        server.setProtocol("FTP");
        server.setHost("10.0.0.1");
        server.setPort(21);
        server.setUsername("alice");
        server.setPassword("s3cret");
        server.setPrivateKeyPath("/keys/id_rsa");
        server.setHostFingerprint("SHA256:abc");
        server.setTimeoutSeconds(60);
        server.setAutoRetry(false);
        server.setMaxRetries(5);

        assertEquals("FTP", server.getProtocol());
        assertEquals("10.0.0.1", server.getHost());
        assertEquals(21, server.getPort());
        assertEquals("alice", server.getUsername());
        assertEquals("s3cret", server.getPassword());
        assertEquals("/keys/id_rsa", server.getPrivateKeyPath());
        assertEquals("SHA256:abc", server.getHostFingerprint());
        assertEquals(60, server.getTimeoutSeconds());
        assertFalse(server.isAutoRetry());
        assertEquals(5, server.getMaxRetries());
    }

    // ── YAML round-trip ─────────────────────────────────────────────────

    @Test
    void yamlRoundTrip_serializeAndDeserialize(@TempDir Path tempDir) throws Exception {
        // Build a custom config
        ClientConfig original = new ClientConfig();
        original.setClientName("test-client");
        original.setLogLevel("DEBUG");
        original.getServer().setProtocol("FTP");
        original.getServer().setHost("192.168.1.100");
        original.getServer().setPort(21);
        original.getServer().setUsername("bob");
        original.getServer().setPassword("p@ss");
        original.getServer().setTimeoutSeconds(60);
        original.getServer().setAutoRetry(false);
        original.getServer().setMaxRetries(5);
        original.getFolders().setOutbox("/data/outbox");
        original.getFolders().setInbox("/data/inbox");
        original.getFolders().setSent("/data/sent");
        original.getFolders().setFailed("/data/failed");
        original.getFolders().setRemoteInbox("/remote/in");
        original.getFolders().setRemoteOutbox("/remote/out");
        original.getSync().setWatchOutbox(false);
        original.getSync().setPollInbox(false);
        original.getSync().setPollIntervalSeconds(120);
        original.getSync().setDeleteAfterDownload(false);
        original.getSync().setIncludePattern(".*\\.csv");
        original.getSync().setExcludePattern(".*\\.tmp");
        original.getSync().setMaxFileSizeMb(500);
        original.getP2p().setEnabled(false);
        original.getP2p().setReceiverPort(9999);
        original.getP2p().setServerUrl("https://mft.example.com");
        original.getP2p().setValidateTickets(false);
        original.getP2p().setExternalHost("mft-client-1.example.com");

        // Serialize to YAML
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path configFile = tempDir.resolve("mft-client.yml");
        yaml.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), original);

        assertTrue(Files.exists(configFile));

        // Deserialize back
        ClientConfig loaded = yaml.readValue(configFile.toFile(), ClientConfig.class);

        // Top-level
        assertEquals("test-client", loaded.getClientName());
        assertEquals("DEBUG", loaded.getLogLevel());

        // Server
        assertEquals("FTP", loaded.getServer().getProtocol());
        assertEquals("192.168.1.100", loaded.getServer().getHost());
        assertEquals(21, loaded.getServer().getPort());
        assertEquals("bob", loaded.getServer().getUsername());
        assertEquals("p@ss", loaded.getServer().getPassword());
        assertEquals(60, loaded.getServer().getTimeoutSeconds());
        assertFalse(loaded.getServer().isAutoRetry());
        assertEquals(5, loaded.getServer().getMaxRetries());

        // Folders
        assertEquals("/data/outbox", loaded.getFolders().getOutbox());
        assertEquals("/data/inbox", loaded.getFolders().getInbox());
        assertEquals("/data/sent", loaded.getFolders().getSent());
        assertEquals("/data/failed", loaded.getFolders().getFailed());
        assertEquals("/remote/in", loaded.getFolders().getRemoteInbox());
        assertEquals("/remote/out", loaded.getFolders().getRemoteOutbox());

        // Sync
        assertFalse(loaded.getSync().isWatchOutbox());
        assertFalse(loaded.getSync().isPollInbox());
        assertEquals(120, loaded.getSync().getPollIntervalSeconds());
        assertFalse(loaded.getSync().isDeleteAfterDownload());
        assertEquals(".*\\.csv", loaded.getSync().getIncludePattern());
        assertEquals(".*\\.tmp", loaded.getSync().getExcludePattern());
        assertEquals(500, loaded.getSync().getMaxFileSizeMb());

        // P2P
        assertFalse(loaded.getP2p().isEnabled());
        assertEquals(9999, loaded.getP2p().getReceiverPort());
        assertEquals("https://mft.example.com", loaded.getP2p().getServerUrl());
        assertFalse(loaded.getP2p().isValidateTickets());
        assertEquals("mft-client-1.example.com", loaded.getP2p().getExternalHost());
    }

    @Test
    void yamlLoad_fromExistingFile(@TempDir Path tempDir) throws Exception {
        // Write a YAML config file manually
        String yamlContent = """
                clientName: "yaml-client"
                logLevel: "WARN"
                server:
                  protocol: "SFTP"
                  host: "sftp.example.com"
                  port: 2222
                  username: "transfer-user"
                  password: "transfer-pass"
                  timeoutSeconds: 45
                  autoRetry: true
                  maxRetries: 10
                folders:
                  outbox: "/opt/outbox"
                  inbox: "/opt/inbox"
                  sent: "/opt/sent"
                  failed: "/opt/failed"
                  remoteInbox: "/uploads"
                  remoteOutbox: "/downloads"
                sync:
                  watchOutbox: true
                  pollInbox: false
                  pollIntervalSeconds: 60
                  deleteAfterDownload: false
                  includePattern: ".*\\\\.xml"
                  maxFileSizeMb: 100
                p2p:
                  enabled: false
                  receiverPort: 8800
                  serverUrl: "https://tranzfer.example.com"
                  validateTickets: true
                  externalHost: "client.example.com"
                """;
        Path configFile = tempDir.resolve("mft-client.yml");
        Files.writeString(configFile, yamlContent);

        // Use ClientConfig.load() which reads existing files
        ClientConfig loaded = ClientConfig.load(configFile);

        assertEquals("yaml-client", loaded.getClientName());
        assertEquals("WARN", loaded.getLogLevel());
        assertEquals("sftp.example.com", loaded.getServer().getHost());
        assertEquals("transfer-user", loaded.getServer().getUsername());
        assertEquals("transfer-pass", loaded.getServer().getPassword());
        assertEquals(45, loaded.getServer().getTimeoutSeconds());
        assertEquals(10, loaded.getServer().getMaxRetries());
        assertEquals("/opt/outbox", loaded.getFolders().getOutbox());
        assertEquals("/uploads", loaded.getFolders().getRemoteInbox());
        assertEquals("/downloads", loaded.getFolders().getRemoteOutbox());
        assertFalse(loaded.getSync().isPollInbox());
        assertEquals(60, loaded.getSync().getPollIntervalSeconds());
        assertFalse(loaded.getSync().isDeleteAfterDownload());
        assertEquals(".*\\.xml", loaded.getSync().getIncludePattern());
        assertNull(loaded.getSync().getExcludePattern());
        assertEquals(100, loaded.getSync().getMaxFileSizeMb());
        assertFalse(loaded.getP2p().isEnabled());
        assertEquals(8800, loaded.getP2p().getReceiverPort());
        assertEquals("https://tranzfer.example.com", loaded.getP2p().getServerUrl());
        assertEquals("client.example.com", loaded.getP2p().getExternalHost());
    }

    @Test
    void yamlLoad_partialConfig_appliesDefaults(@TempDir Path tempDir) throws Exception {
        // Only specify server credentials -- everything else should default
        String yamlContent = """
                server:
                  username: "minimal-user"
                  password: "minimal-pass"
                """;
        Path configFile = tempDir.resolve("minimal.yml");
        Files.writeString(configFile, yamlContent);

        ClientConfig loaded = ClientConfig.load(configFile);

        assertEquals("minimal-user", loaded.getServer().getUsername());
        assertEquals("minimal-pass", loaded.getServer().getPassword());
        // All other fields should have defaults
        assertEquals("SFTP", loaded.getServer().getProtocol());
        assertEquals("localhost", loaded.getServer().getHost());
        assertEquals(2222, loaded.getServer().getPort());
        assertEquals(30, loaded.getServer().getTimeoutSeconds());
        assertTrue(loaded.getServer().isAutoRetry());
        assertEquals(3, loaded.getServer().getMaxRetries());
        assertEquals("mft-client", loaded.getClientName());
        assertEquals("INFO", loaded.getLogLevel());
        assertEquals("./outbox", loaded.getFolders().getOutbox());
        assertTrue(loaded.getSync().isWatchOutbox());
        assertEquals(30, loaded.getSync().getPollIntervalSeconds());
    }
}
