package com.filetransfer.client;

import com.filetransfer.client.config.ClientConfig;
import com.filetransfer.client.sync.FileSyncEngine;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
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

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n  Shutting down...");
            engine.stop();
        }));

        engine.start();

        // Interactive console
        System.out.println();
        System.out.println("  Commands: status | quit | help");
        System.out.println();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            String cmd = line.trim().toLowerCase();
            switch (cmd) {
                case "status", "s" -> System.out.println("  " + engine.getStatus());
                case "quit", "exit", "q" -> { engine.stop(); System.exit(0); }
                case "help", "h" -> printInteractiveHelp();
                case "" -> {} // ignore empty
                default -> System.out.println("  Unknown command. Type 'help'.");
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

    private static void printInteractiveHelp() {
        System.out.println("""
                status   Show upload/download counts and connection status
                quit     Stop the client gracefully
                help     Show this help
            """);
    }
}
