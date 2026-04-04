package com.filetransfer.client.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.client.config.ClientConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Embedded HTTP server that receives files directly from other MFT clients.
 * Runs on a configurable port (default 9900).
 *
 * Endpoints:
 *   POST /receive  — accept a file transfer (multipart: ticket + file data)
 *   GET  /health   — receiver health check
 */
@Slf4j
public class PeerReceiver implements AutoCloseable {

    private final ClientConfig config;
    private final HttpServer server;
    private final int port;

    public PeerReceiver(ClientConfig config) throws IOException {
        this.config = config;
        this.port = config.getP2p() != null ? config.getP2p().getReceiverPort() : 9900;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/receive", this::handleReceive);
        server.createContext("/health", ex -> {
            byte[] resp = "{\"status\":\"UP\"}".getBytes();
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
    }

    public void start() {
        server.start();
        log.info("P2P receiver listening on port {}", port);
    }

    public int getPort() { return port; }

    private void handleReceive(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        try {
            // Read headers
            String ticketId = exchange.getRequestHeaders().getFirst("X-Ticket-Id");
            String senderToken = exchange.getRequestHeaders().getFirst("X-Sender-Token");
            String filename = exchange.getRequestHeaders().getFirst("X-Filename");
            String senderChecksum = exchange.getRequestHeaders().getFirst("X-SHA256");
            String trackId = exchange.getRequestHeaders().getFirst("X-Track-Id");

            if (ticketId == null || senderToken == null || filename == null) {
                sendError(exchange, 400, "Missing required headers: X-Ticket-Id, X-Sender-Token, X-Filename");
                return;
            }

            // Validate ticket with server
            if (config.getP2p() != null && config.getP2p().isValidateTickets()) {
                boolean valid = validateTicketWithServer(ticketId, senderToken);
                if (!valid) {
                    sendError(exchange, 403, "Ticket validation failed");
                    return;
                }
            }

            // Save file to inbox
            Path inboxDir = Paths.get(config.getFolders().getInbox());
            Files.createDirectories(inboxDir);
            Path destFile = inboxDir.resolve(filename);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = exchange.getRequestBody();
                 OutputStream out = Files.newOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                }
            }

            String receivedChecksum = HexFormat.of().formatHex(digest.digest());
            long fileSize = Files.size(destFile);

            log.info("[{}] P2P received: {} ({} bytes, sha256={})",
                    trackId, filename, fileSize, receivedChecksum.substring(0, 16) + "...");

            // Verify checksum
            boolean checksumMatch = senderChecksum == null || senderChecksum.equals(receivedChecksum);
            if (!checksumMatch) {
                log.error("[{}] CHECKSUM MISMATCH! sender={} received={}", trackId, senderChecksum, receivedChecksum);
            }

            // Report completion to server
            if (config.getP2p() != null && config.getP2p().isValidateTickets()) {
                reportCompletion(ticketId, receivedChecksum, checksumMatch);
            }

            // Response
            String response = new ObjectMapper().writeValueAsString(Map.of(
                    "status", checksumMatch ? "OK" : "CHECKSUM_MISMATCH",
                    "filename", filename,
                    "size", fileSize,
                    "sha256", receivedChecksum,
                    "trackId", trackId != null ? trackId : ""
            ));
            byte[] respBytes = response.getBytes();
            exchange.sendResponseHeaders(200, respBytes.length);
            exchange.getResponseBody().write(respBytes);
            exchange.close();

        } catch (Exception e) {
            log.error("P2P receive error: {}", e.getMessage());
            sendError(exchange, 500, e.getMessage());
        }
    }

    private boolean validateTicketWithServer(String ticketId, String senderToken) {
        try {
            String serverUrl = config.getP2p().getServerUrl();
            URL url = new URL(serverUrl + "/api/p2p/tickets/" + ticketId + "/validate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(("{\"senderToken\":\"" + senderToken + "\"}").getBytes());

            if (conn.getResponseCode() == 200) {
                return true;
            } else {
                log.warn("Ticket validation failed: HTTP {}", conn.getResponseCode());
                return false;
            }
        } catch (Exception e) {
            log.warn("Could not validate ticket with server: {}", e.getMessage());
            return true; // Allow transfer if server unreachable (graceful degradation)
        }
    }

    private void reportCompletion(String ticketId, String checksum, boolean success) {
        try {
            String serverUrl = config.getP2p().getServerUrl();
            URL url = new URL(serverUrl + "/api/p2p/tickets/" + ticketId + "/complete");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            String body = "{\"sha256Checksum\":\"" + checksum + "\",\"success\":" + success + "}";
            conn.getOutputStream().write(body.getBytes());
            conn.getResponseCode(); // fire and forget
        } catch (Exception e) {
            log.warn("Could not report completion to server: {}", e.getMessage());
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] resp = ("{\"error\":\"" + message + "\"}").getBytes();
        exchange.sendResponseHeaders(code, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        log.info("P2P receiver stopped");
    }
}
