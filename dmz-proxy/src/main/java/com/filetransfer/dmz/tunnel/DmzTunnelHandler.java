package com.filetransfer.dmz.tunnel;

import com.filetransfer.tunnel.control.ControlMessage;
import com.filetransfer.tunnel.control.ControlMessageCodec;
import com.filetransfer.tunnel.control.PendingControlRequest;
import com.filetransfer.tunnel.flow.TunnelFlowController;
import com.filetransfer.tunnel.protocol.TunnelFrame;
import com.filetransfer.tunnel.protocol.TunnelFrameType;
import com.filetransfer.tunnel.stream.TunnelStreamChannel;
import com.filetransfer.tunnel.stream.TunnelStreamManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DMZ-side tunnel frame dispatcher. Handles all frame types on the multiplexed tunnel.
 * <p>
 * PING/PONG are consumed by {@link com.filetransfer.tunnel.keepalive.TunnelKeepAliveHandler}
 * before reaching this handler. All other frame types are dispatched here.
 * <p>
 * Stream ID allocation: DMZ side uses odd IDs (dmzSide=true in TunnelStreamManager).
 * Gateway-service (internal side) uses even IDs.
 * <p>
 * No Spring DI — constructed manually by {@link TunnelAcceptor}.
 */
@Slf4j
public class DmzTunnelHandler extends SimpleChannelInboundHandler<TunnelFrame> {

    private final int maxStreams;
    private final int windowSize;

    @Getter
    private volatile TunnelStreamManager streamManager;
    @Getter
    private volatile TunnelFlowController flowController;

    private final ConcurrentHashMap<String, PendingControlRequest> pendingControls = new ConcurrentHashMap<>();

    private volatile ChannelHandlerContext ctx;
    private volatile SocketAddress remoteAddress;

    /**
     * @param maxStreams  max concurrent multiplexed streams
     * @param windowSize per-stream flow control window in bytes (0 = default 256KB)
     */
    public DmzTunnelHandler(int maxStreams, int windowSize) {
        this.maxStreams = maxStreams;
        this.windowSize = windowSize;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        this.remoteAddress = ctx.channel().remoteAddress();

        int effectiveWindow = windowSize > 0 ? windowSize : TunnelFlowController.DEFAULT_WINDOW_SIZE;
        this.flowController = new TunnelFlowController(effectiveWindow);
        this.streamManager = new TunnelStreamManager(ctx.channel(), flowController, true, maxStreams);

        log.info("Tunnel active from {} — stream manager ready (maxStreams={}, windowSize={})",
                remoteAddress, maxStreams, effectiveWindow);

        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TunnelFrame frame) {
        TunnelFrameType type = frame.getType();
        int streamId = frame.getStreamId();

        switch (type) {
            case DATA -> handleData(streamId, frame);

            case STREAM_OPEN -> handleStreamOpen(ctx, streamId, frame);

            case STREAM_CLOSE -> streamManager.closeStream(streamId, frame.getFlags());

            case CONTROL_REQ -> handleControlRequest(ctx, frame);

            case CONTROL_RES -> handleControlResponse(frame);

            case WINDOW_UPDATE -> handleWindowUpdate(streamId, frame);

            case GO_AWAY -> handleGoAway(ctx);

            case HEALTH_PROBE -> handleHealthProbe(ctx, frame);

            case HEALTH_RESULT -> {
                // Health results are responses to probes we sent — log for observability
                log.debug("Health result received on tunnel from {}", remoteAddress);
            }

            // PING/PONG already consumed by TunnelKeepAliveHandler
            default -> log.warn("Unexpected frame type {} on tunnel from {}", type, remoteAddress);
        }
    }

    // ── DATA ──

    private void handleData(int streamId, TunnelFrame frame) {
        ByteBuf payload = frame.getPayload();
        // Retain for dispatch — TunnelStreamManager/TunnelStreamChannel will release
        payload.retain();
        streamManager.dispatchData(streamId, payload);
    }

    // ── STREAM_OPEN ──

    private void handleStreamOpen(ChannelHandlerContext ctx, int streamId, TunnelFrame frame) {
        if (frame.hasFlag(TunnelFrame.FLAG_SYN)) {
            // Remote (gateway-service) is opening a new stream
            SocketAddress remoteAddr = parseStreamOpenPayload(frame);
            TunnelStreamChannel stream = streamManager.acceptStream(streamId, remoteAddr);
            if (stream != null) {
                // Acknowledge the stream open
                ctx.writeAndFlush(TunnelFrame.streamOpenAck(streamId), ctx.voidPromise());
                log.debug("Accepted STREAM_OPEN id={} target={}", streamId, remoteAddr);
            }
            // If stream is null, acceptStream already sent RST
        } else if (frame.hasFlag(TunnelFrame.FLAG_ACK)) {
            // ACK for a stream we opened — activate it
            TunnelStreamChannel stream = streamManager.getStream(streamId);
            if (stream != null) {
                stream.activate();
                stream.pipeline().fireChannelActive();
                log.debug("STREAM_OPEN_ACK received for stream {}", streamId);
            } else {
                log.warn("STREAM_OPEN_ACK for unknown stream {}", streamId);
            }
        }
    }

    /**
     * Parses the STREAM_OPEN payload to extract the target address.
     * Payload is JSON: {"targetHost":"...", "targetPort":..., "mappingName":"..."}
     * Returns an InetSocketAddress for the target, or a placeholder if parsing fails.
     */
    private SocketAddress parseStreamOpenPayload(TunnelFrame frame) {
        ByteBuf payload = frame.getPayload();
        if (payload == null || payload.readableBytes() == 0) {
            return new InetSocketAddress("unknown", 0);
        }
        try {
            byte[] bytes = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), bytes);
            String json = new String(bytes, StandardCharsets.UTF_8);
            // Lightweight JSON parsing — avoid pulling in full Jackson for DMZ proxy hot path
            String host = extractJsonString(json, "targetHost");
            int port = extractJsonInt(json, "targetPort");
            return new InetSocketAddress(host != null ? host : "unknown", port);
        } catch (Exception e) {
            log.warn("Failed to parse STREAM_OPEN payload: {}", e.getMessage());
            return new InetSocketAddress("unknown", 0);
        }
    }

    // ── CONTROL_REQ ──

    private void handleControlRequest(ChannelHandlerContext ctx, TunnelFrame frame) {
        ByteBuf payload = frame.getPayload();
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);

        try {
            ControlMessage request = ControlMessageCodec.decode(bytes);
            log.debug("Control request: {} {} (correlationId={})",
                    request.getMethod(), request.getPath(), request.getCorrelationId());

            // Route to internal handler — for now, respond with 501 Not Implemented
            // The routing logic will be plugged in by ProxyManager or a dedicated controller
            ControlMessage response = ControlMessage.response(
                    request.getCorrelationId(), 501, "Not implemented".getBytes(StandardCharsets.UTF_8));
            byte[] responseBytes = ControlMessageCodec.encode(response);
            ctx.writeAndFlush(TunnelFrame.controlResponse(responseBytes), ctx.voidPromise());
        } catch (Exception e) {
            log.error("Failed to process control request: {}", e.getMessage(), e);
        }
    }

    // ── CONTROL_RES ──

    private void handleControlResponse(TunnelFrame frame) {
        ByteBuf payload = frame.getPayload();
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);

        try {
            ControlMessage response = ControlMessageCodec.decode(bytes);
            String correlationId = response.getCorrelationId();

            PendingControlRequest pending = pendingControls.remove(correlationId);
            if (pending != null) {
                pending.complete(response);
                log.debug("Control response received: correlationId={}, status={}, elapsed={}ms",
                        correlationId, response.getStatusCode(), pending.elapsedMs());
            } else {
                log.warn("Control response for unknown correlationId: {}", correlationId);
            }
        } catch (Exception e) {
            log.error("Failed to decode control response: {}", e.getMessage(), e);
        }
    }

    // ── WINDOW_UPDATE ──

    private void handleWindowUpdate(int streamId, TunnelFrame frame) {
        int increment = frame.getPayload().readInt();
        streamManager.handleWindowUpdate(streamId, increment);
    }

    // ── GO_AWAY ──

    private void handleGoAway(ChannelHandlerContext ctx) {
        log.warn("GO_AWAY received from {} — closing all streams and tunnel", remoteAddress);
        streamManager.closeAll();
        ctx.close();
    }

    // ── HEALTH_PROBE ──

    private void handleHealthProbe(ChannelHandlerContext ctx, TunnelFrame frame) {
        // Echo back as HEALTH_RESULT with the same payload
        ByteBuf payload = frame.getPayload();
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);
        ctx.writeAndFlush(TunnelFrame.healthResult(bytes), ctx.voidPromise());
        log.trace("Health probe responded on tunnel from {}", remoteAddress);
    }

    // ── Outbound control request API ──

    /**
     * Sends a control request to gateway-service and returns a future for the response.
     *
     * @param request   the control message (correlationId will be assigned if null)
     * @param timeoutMs timeout in milliseconds
     * @return future that completes with the response or times out
     */
    public CompletableFuture<ControlMessage> sendControlRequest(ControlMessage request, long timeoutMs) {
        Channel ch = ctx != null ? ctx.channel() : null;
        if (ch == null || !ch.isActive()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Tunnel not active — cannot send control request"));
        }

        // Assign correlation ID if not set
        if (request.getCorrelationId() == null) {
            request.setCorrelationId(UUID.randomUUID().toString());
        }
        String correlationId = request.getCorrelationId();

        PendingControlRequest pending = new PendingControlRequest(correlationId, timeoutMs);
        pendingControls.put(correlationId, pending);

        byte[] encoded = ControlMessageCodec.encode(request);
        ch.writeAndFlush(TunnelFrame.controlRequest(encoded), ch.voidPromise());

        // Clean up on completion (success, failure, or timeout)
        pending.getFuture().whenComplete((res, ex) -> pendingControls.remove(correlationId));

        return pending.getFuture();
    }

    // ── Lifecycle ──

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.warn("Tunnel connection lost from {}", remoteAddress);

        if (streamManager != null) {
            streamManager.closeAll();
        }

        // Fail all pending control requests
        pendingControls.forEach((id, pending) ->
                pending.fail(new IllegalStateException("Tunnel disconnected")));
        pendingControls.clear();

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Tunnel error from {}: {}", remoteAddress, cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * Closes the tunnel connection gracefully by sending GO_AWAY.
     */
    public void close() {
        Channel ch = ctx != null ? ctx.channel() : null;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(TunnelFrame.goAway()).addListener(f -> ch.close());
        }
    }

    /**
     * Returns the remote address of the connected tunnel client.
     */
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns the physical tunnel channel (for sending frames directly).
     */
    public Channel getTunnelChannel() {
        return ctx != null ? ctx.channel() : null;
    }

    /**
     * Returns true if the tunnel is connected and active.
     */
    public boolean isConnected() {
        Channel ch = getTunnelChannel();
        return ch != null && ch.isActive();
    }

    // ── Minimal JSON helpers (avoids Jackson dependency in hot path) ──

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    private static int extractJsonInt(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return 0;
        int start = colonIdx + 1;
        while (start < json.length() && !Character.isDigit(json.charAt(start)) && json.charAt(start) != '-') {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (start >= end) return 0;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
