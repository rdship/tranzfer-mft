package com.filetransfer.shared.client;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;

/**
 * ClamAV antivirus client using the clamd TCP protocol.
 *
 * <p>Streams files to ClamAV daemon via INSTREAM command (chunked binary protocol).
 * Fail-closed: if ClamAV is unreachable, the file is BLOCKED (security-first).
 *
 * <p>Protocol reference:
 * <ul>
 *   <li>Send: "zINSTREAM\0"</li>
 *   <li>For each chunk: 4-byte big-endian length + data</li>
 *   <li>End: 4 zero bytes</li>
 *   <li>Response: "stream: OK\0" or "stream: {virusName} FOUND\0"</li>
 * </ul>
 */
@Slf4j
@Component
public class ClamAvClient {

    @Value("${clamav.host:localhost}")
    private String host;

    @Value("${clamav.port:3310}")
    private int port;

    @Value("${clamav.timeout-ms:60000}")
    private int timeoutMs;

    @Value("${clamav.chunk-size:8192}")
    private int chunkSize;

    /**
     * Scan a file by path. Streams the file content to ClamAV.
     */
    public ScanResult scanFile(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            return scanFile(is);
        } catch (IOException e) {
            log.error("Failed to read file for AV scan: {}", filePath, e);
            return ScanResult.blocked("IO error reading file: " + e.getMessage());
        }
    }

    /**
     * Scan a file via InputStream. Streams content to ClamAV using INSTREAM protocol.
     * Fail-closed: returns BLOCKED if ClamAV is unreachable.
     */
    public ScanResult scanFile(InputStream fileStream) {
        long start = System.currentTimeMillis();

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(timeoutMs);

            OutputStream socketOut = socket.getOutputStream();
            InputStream socketIn = socket.getInputStream();

            // Send INSTREAM command (null-terminated)
            socketOut.write("zINSTREAM\0".getBytes());
            socketOut.flush();

            // Stream file in chunks: [4-byte length][data]
            byte[] buffer = new byte[chunkSize];
            int bytesRead;
            while ((bytesRead = fileStream.read(buffer)) != -1) {
                // Write chunk length as 4-byte big-endian
                byte[] lengthBytes = ByteBuffer.allocate(4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .putInt(bytesRead)
                        .array();
                socketOut.write(lengthBytes);
                socketOut.write(buffer, 0, bytesRead);
            }

            // End of stream: 4 zero bytes
            socketOut.write(new byte[]{0, 0, 0, 0});
            socketOut.flush();

            // Read response
            String response = readResponse(socketIn);
            long scanTimeMs = System.currentTimeMillis() - start;

            return parseResponse(response, scanTimeMs);

        } catch (IOException e) {
            long scanTimeMs = System.currentTimeMillis() - start;
            log.error("ClamAV unreachable at {}:{} — BLOCKING file (fail-closed): {}",
                    host, port, e.getMessage());
            return ScanResult.builder()
                    .clean(false)
                    .virusName(null)
                    .scanTimeMs(scanTimeMs)
                    .error("ClamAV unreachable: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Ping ClamAV to check if it's running.
     */
    public boolean ping() {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("zPING\0".getBytes());
            socket.getOutputStream().flush();
            String response = readResponse(socket.getInputStream());
            return "PONG".equals(response.trim());
        } catch (IOException e) {
            log.debug("ClamAV ping failed: {}", e.getMessage());
            return false;
        }
    }

    private String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == 0) break; // null-terminated
            baos.write(b);
        }
        return baos.toString().trim();
    }

    private ScanResult parseResponse(String response, long scanTimeMs) {
        if (response == null || response.isEmpty()) {
            return ScanResult.builder()
                    .clean(false).scanTimeMs(scanTimeMs)
                    .error("Empty response from ClamAV").build();
        }

        // Response format: "stream: OK" or "stream: VirusName FOUND"
        if (response.contains("OK")) {
            return ScanResult.builder()
                    .clean(true).scanTimeMs(scanTimeMs).build();
        }

        if (response.contains("FOUND")) {
            // Extract virus name: "stream: Eicar-Test-Signature FOUND"
            String virusName = response
                    .replaceAll("^stream:\\s*", "")
                    .replaceAll("\\s*FOUND$", "")
                    .trim();
            return ScanResult.builder()
                    .clean(false).virusName(virusName).scanTimeMs(scanTimeMs).build();
        }

        // Unexpected response — fail-closed
        return ScanResult.builder()
                .clean(false).scanTimeMs(scanTimeMs)
                .error("Unexpected ClamAV response: " + response).build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScanResult {
        private boolean clean;
        private String virusName;
        private long scanTimeMs;
        private String error;

        /** Factory for fail-closed blocking results. */
        public static ScanResult blocked(String error) {
            return ScanResult.builder().clean(false).error(error).build();
        }
    }
}
