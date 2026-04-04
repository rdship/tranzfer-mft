package com.filetransfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.client.config.ClientConfig;
import com.filetransfer.client.sync.FileSyncEngine;
import com.filetransfer.client.sync.PeerReceiver;
import com.filetransfer.client.sync.PeerSender;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/**
 * TranzFer MFT Client — Cross-platform file transfer client.
 *
 * Usage:
 *   java -jar mft-client.jar                    # Start with mft-client.yml in current dir
 *   java -jar mft-client.jar --config /path/to/config.yml
 *   java -jar mft-client.jar --init             # Create default config and exit
 *   java -jar mft-client.jar --status           # Show client status
 *
 * How it works:
 *   1. Drop files into ./outbox  →  automatically uploaded to server /inbox
 *   2. Server processes the file (flow: encrypt/compress/route)
 *   3. Files routed to your /outbox on server  →  automatically downloaded to ./inbox
 *
 * That's it. Files in, files out.
 */
@Slf4j
public class MftClient {

    private static final String VERSION = "2.1.0";
    private static final String DEFAULT_CONFIG = "mft-client.yml";

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════╗");
        System.out.println("  ║   TranzFer MFT Client v" + VERSION + "        ║");
        System.out.println("  ╚══════════════════════════════════════╝");
        System.out.println();

        String configPath = DEFAULT_CONFIG;
        boolean initOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> { if (i + 1 < args.length) configPath = args[++i]; }
                case "--init" -> initOnly = true;
                case "--help", "-h" -> { printHelp(); return; }
                case "--version", "-v" -> { System.out.println("  Version: " + VERSION); return; }
            }
        }

        Path cfgPath = Paths.get(configPath);
        if (initOnly) {
            ClientConfig.load(cfgPath);
            return;
        }

        ClientConfig config = ClientConfig.load(cfgPath);

        if (config.getServer().getUsername() == null ||
                "your-username".equals(config.getServer().getUsername())) {
            System.out.println("  ⚠  Please edit " + configPath + " with your server credentials.");
            System.out.println("     Set server.username and server.password, then run again.");
            System.exit(1);
        }

        FileSyncEngine engine = new FileSyncEngine(config);
        PeerReceiver peerReceiver = null;
        PeerSender peerSender = new PeerSender(config);

        // Start P2P receiver if enabled
        if (config.getP2p() != null && config.getP2p().isEnabled()) {
            peerReceiver = new PeerReceiver(config);
            peerReceiver.start();

            // Register presence with server
            registerPresence(config);

            // Schedule heartbeat
            PeerReceiver finalReceiver = peerReceiver;
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                    .scheduleAtFixedRate(() -> registerPresence(config),
                            30, 30, java.util.concurrent.TimeUnit.SECONDS);
        }

        // Graceful shutdown
        PeerReceiver finalRcv = peerReceiver;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n  Shutting down...");
            engine.stop();
            if (finalRcv != null) finalRcv.close();
        }));

        engine.start();

        // Interactive console
        System.out.println();
        System.out.println("  Commands: status | send <file> <user> | peers | quit | help");
        System.out.println();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            String cmd = line.trim();
            String lower = cmd.toLowerCase();
            if (lower.startsWith("send ")) {
                handleSend(cmd, peerSender);
            } else {
                switch (lower) {
                    case "status", "s" -> System.out.println("  " + engine.getStatus());
                    case "peers" -> listPeers(config);
                    case "quit", "exit", "q" -> { engine.stop(); if (finalRcv != null) finalRcv.close(); System.exit(0); }
                    case "help", "h" -> printInteractiveHelp();
                    case "" -> {}
                    default -> System.out.println("  Unknown command. Type 'help'.");
                }
            }
        }
    }

    private static void printHelp() {
        System.out.println("""
              USAGE:
                java -jar mft-client.jar [OPTIONS]

              OPTIONS:
                --config, -c <path>    Path to config file (default: mft-client.yml)
                --init                 Create default config file and exit
                --help, -h             Show this help
                --version, -v          Show version

              HOW IT WORKS:
                1. Edit mft-client.yml with your server details
                2. Run the client
                3. Drop files into ./outbox — they upload automatically
                4. Received files appear in ./inbox

              FOLDER STRUCTURE (created automatically):
                ./outbox/     Drop files here to send to server
                ./inbox/      Received files from server appear here
                ./sent/       Successfully uploaded files are archived here
                ./failed/     Failed uploads are moved here for retry
            """);
    }

    private static void handleSend(String cmd, PeerSender sender) {
        // "send /path/to/file.csv receiver_username"
        String[] parts = cmd.split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("  Usage: send <filepath> <receiver_username>");
            return;
        }
        Path file = Paths.get(parts[1]);
        String receiver = parts[2];
        if (!Files.exists(file)) {
            System.out.println("  File not found: " + parts[1]);
            return;
        }
        try {
            String trackId = sender.send(file, receiver);
            System.out.println("  P2P transfer complete! Track ID: " + trackId);
        } catch (Exception e) {
            System.out.println("  P2P transfer failed: " + e.getMessage());
        }
    }

    private static void listPeers(ClientConfig config) {
        try {
            String serverUrl = config.getP2p().getServerUrl();
            HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "/api/p2p/presence").openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                List<Map<String, Object>> peers = new ObjectMapper().readValue(conn.getInputStream(), List.class);
                if (peers.isEmpty()) {
                    System.out.println("  No peers online.");
                } else {
                    System.out.println("  Online peers:");
                    for (Map<String, Object> p : peers) {
                        System.out.printf("    %-20s %s:%s (v%s)%n",
                                p.get("username"), p.get("host"), p.get("port"),
                                p.getOrDefault("clientVersion", "?"));
                    }
                }
            } else {
                System.out.println("  Could not reach server: HTTP " + conn.getResponseCode());
            }
        } catch (Exception e) {
            System.out.println("  Error listing peers: " + e.getMessage());
        }
    }

    private static void registerPresence(ClientConfig config) {
        try {
            String serverUrl = config.getP2p().getServerUrl();
            Map<String, Object> body = Map.of(
                    "username", config.getServer().getUsername(),
                    "host", config.getP2p().getExternalHost(),
                    "port", config.getP2p().getReceiverPort(),
                    "clientVersion", VERSION
            );
            HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "/api/p2p/presence").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(new ObjectMapper().writeValueAsBytes(body));
            conn.getResponseCode();
        } catch (Exception e) {
            log.debug("Presence registration failed: {}", e.getMessage());
        }
    }

    private static void printInteractiveHelp() {
        System.out.println("""
                status               Upload/download counts and connection status
                send <file> <user>   Send file directly to another client (P2P)
                peers                List online clients available for P2P
                quit                 Stop the client gracefully
                help                 Show this help
            """);
    }
}
