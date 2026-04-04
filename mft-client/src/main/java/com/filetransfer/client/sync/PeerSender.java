package com.filetransfer.client.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.client.config.ClientConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Sends a file directly to another MFT client via HTTP.
 *
 * Steps:
 * 1. Compute SHA-256 of the file
 * 2. Request transfer ticket from server
 * 3. POST file directly to receiver's HTTP endpoint
 * 4. Report completion to server
 */
@Slf4j
@RequiredArgsConstructor
public class PeerSender {

    private final ClientConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Send a file to a remote client.
     * @param file     Local file to send
     * @param receiver Username of the receiving client
     * @return Track ID of the transfer
     */
    public String send(Path file, String receiver) throws Exception {
        String filename = file.getFileName().toString();
        long fileSize = Files.size(file);
        String checksum = computeSha256(file);

        log.info("P2P send: {} -> {} ({} bytes, sha256={})",
                filename, receiver, fileSize, checksum.substring(0, 16) + "...");

        // Step 1: Request transfer ticket from server
        String serverUrl = config.getP2p().getServerUrl();
        Map<String, Object> ticketReq = Map.of(
                "senderUsername", config.getServer().getUsername(),
                "receiverUsername", receiver,
                "filename", filename,
                "fileSizeBytes", fileSize,
                "sha256Checksum", checksum
        );

        Map<String, Object> ticket = postJson(serverUrl + "/api/p2p/tickets", ticketReq);
        String ticketId = (String) ticket.get("ticketId");
        String trackId = (String) ticket.get("trackId");
        String senderToken = (String) ticket.get("senderToken");
        String receiverHost = (String) ticket.get("receiverHost");
        int receiverPort = ((Number) ticket.get("receiverPort")).intValue();

        log.info("[{}] Ticket obtained. Connecting to {}:{}", trackId, receiverHost, receiverPort);

        // Step 2: POST file directly to receiver
        URL receiverUrl = new URL("http://" + receiverHost + ":" + receiverPort + "/receive");
        HttpURLConnection conn = (HttpURLConnection) receiverUrl.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("X-Ticket-Id", ticketId);
        conn.setRequestProperty("X-Sender-Token", senderToken);
        conn.setRequestProperty("X-Filename", filename);
        conn.setRequestProperty("X-SHA256", checksum);
        conn.setRequestProperty("X-Track-Id", trackId);
        conn.setChunkedStreamingMode(8192);

        try (InputStream in = Files.newInputStream(file);
             OutputStream out = conn.getOutputStream()) {
            in.transferTo(out);
        }

        int respCode = conn.getResponseCode();
        if (respCode == 200) {
            String respBody = new String(conn.getInputStream().readAllBytes());
            Map<String, Object> result = mapper.readValue(respBody, Map.class);
            log.info("[{}] P2P transfer complete: {} -> {} (status={})",
                    trackId, filename, receiver, result.get("status"));

            // Move to sent
            Path sent = file.getParent().getParent().resolve("sent").resolve(file.getFileName());
            Files.createDirectories(sent.getParent());
            Files.move(file, sent, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return trackId;
        } else {
            String error = new String(conn.getErrorStream().readAllBytes());
            log.error("[{}] P2P transfer failed: HTTP {} — {}", trackId, respCode, error);
            throw new IOException("P2P transfer failed: HTTP " + respCode + " — " + error);
        }
    }

    private Map<String, Object> postJson(String url, Map<String, Object> body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        String token = null; // Could add JWT auth here
        conn.setDoOutput(true);
        conn.getOutputStream().write(mapper.writeValueAsBytes(body));

        if (conn.getResponseCode() == 201 || conn.getResponseCode() == 200) {
            return mapper.readValue(conn.getInputStream(), Map.class);
        }
        String error = new String(conn.getErrorStream().readAllBytes());
        throw new IOException("Server returned HTTP " + conn.getResponseCode() + ": " + error);
    }

    private String computeSha256(Path file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) d.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
}
