package com.filetransfer.gateway.tunnel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.tunnel.protocol.TunnelFrame;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Handles HEALTH_PROBE frames from the DMZ proxy.
 * <p>
 * Performs a non-blocking TCP connect probe to the specified host:port and returns
 * a HEALTH_RESULT with success status, latency, and any error message.
 * Probes run on the common ForkJoinPool to avoid blocking the Netty event loop.
 */
@Slf4j
public class TunnelHealthProber {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private TunnelHealthProber() {}

    /**
     * Processes a HEALTH_PROBE and writes the HEALTH_RESULT back on the tunnel channel.
     *
     * @param tunnelChannel the physical tunnel channel
     * @param probePayload  JSON payload: {"host": "...", "port": N}
     */
    public static void probe(Channel tunnelChannel, byte[] probePayload) {
        CompletableFuture.runAsync(() -> {
            long startNanos = System.nanoTime();
            Map<String, Object> result;
            try {
                Map<?, ?> request = MAPPER.readValue(probePayload, Map.class);
                String host = (String) request.get("host");
                int port = ((Number) request.get("port")).intValue();

                try (SocketChannel sc = SocketChannel.open()) {
                    sc.configureBlocking(true);
                    sc.socket().connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                    result = Map.of("success", true, "latencyMs", latencyMs);
                    log.debug("Health probe {}:{} succeeded in {}ms", host, port, latencyMs);
                }
            } catch (Exception e) {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                result = Map.of("success", false, "latencyMs", latencyMs,
                        "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                log.debug("Health probe failed: {}", e.getMessage());
            }

            try {
                byte[] resultBytes = MAPPER.writeValueAsBytes(result);
                tunnelChannel.writeAndFlush(TunnelFrame.healthResult(resultBytes));
            } catch (IOException e) {
                log.error("Failed to encode health result: {}", e.getMessage());
            }
        }, ForkJoinPool.commonPool());
    }
}
