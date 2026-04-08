package com.filetransfer.gateway.tunnel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.tunnel.control.ControlMessage;
import com.filetransfer.tunnel.control.ControlMessageCodec;
import com.filetransfer.tunnel.control.PendingControlRequest;
import com.filetransfer.tunnel.flow.TunnelFlowController;
import com.filetransfer.tunnel.protocol.TunnelFrame;
import com.filetransfer.tunnel.stream.TunnelStreamChannel;
import com.filetransfer.tunnel.stream.TunnelStreamManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles frames received from the DMZ proxy on the internal (gateway) side of the tunnel.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>DATA -> dispatches to the appropriate TunnelStreamChannel via TunnelStreamManager</li>
 *   <li>STREAM_OPEN (SYN) -> DMZ wants to reach an internal service; connects locally and bridges via TunnelStreamRelay</li>
 *   <li>STREAM_CLOSE -> closes the stream</li>
 *   <li>CONTROL_REQ -> routes to internal HTTP services via TunnelControlForwarder</li>
 *   <li>CONTROL_RES -> completes a pending control request future</li>
 *   <li>WINDOW_UPDATE -> replenishes the send window for a stream</li>
 *   <li>HEALTH_PROBE -> delegates to TunnelHealthProber</li>
 *   <li>HEALTH_RESULT -> completes a pending health future</li>
 *   <li>GO_AWAY -> closes all streams and triggers reconnect</li>
 * </ul>
 * Stream ID allocation: dmzSide=false -> even IDs for locally-initiated streams.
 */
@Slf4j
public class InternalTunnelHandler extends SimpleChannelInboundHandler<TunnelFrame> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TunnelClientProperties properties;
    private final TunnelControlForwarder controlForwarder;
    private final TunnelClient tunnelClient;

    @Getter
    private volatile TunnelStreamManager streamManager;
    @Getter
    private volatile TunnelFlowController flowController;

    private final ConcurrentHashMap<String, PendingControlRequest> pendingControlRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingHealthRequests = new ConcurrentHashMap<>();

    public InternalTunnelHandler(TunnelClientProperties properties,
                                 TunnelControlForwarder controlForwarder,
                                 TunnelClient tunnelClient) {
        this.properties = properties;
        this.controlForwarder = controlForwarder;
        this.tunnelClient = tunnelClient;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.flowController = new TunnelFlowController(properties.getWindowSize());
        this.streamManager = new TunnelStreamManager(
                ctx.channel(), flowController, false, properties.getMaxStreams());
        log.info("Internal tunnel handler active, stream manager initialized (maxStreams={}, windowSize={})",
                properties.getMaxStreams(), properties.getWindowSize());
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TunnelFrame frame) {
        switch (frame.getType()) {
            case DATA -> handleData(frame);
            case STREAM_OPEN -> handleStreamOpen(ctx, frame);
            case STREAM_CLOSE -> handleStreamClose(frame);
            case CONTROL_REQ -> handleControlRequest(ctx, frame);
            case CONTROL_RES -> handleControlResponse(frame);
            case WINDOW_UPDATE -> handleWindowUpdate(frame);
            case HEALTH_PROBE -> handleHealthProbe(ctx, frame);
            case HEALTH_RESULT -> handleHealthResult(frame);
            case GO_AWAY -> handleGoAway(ctx);
            default -> log.warn("Unexpected frame type on internal handler: {}", frame.getType());
        }
    }

    private void handleData(TunnelFrame frame) {
        // Retain the payload since dispatchData takes ownership
        ByteBuf payload = frame.getPayload().retain();
        streamManager.dispatchData(frame.getStreamId(), payload);
    }

    private void handleStreamOpen(ChannelHandlerContext ctx, TunnelFrame frame) {
        if (!frame.hasFlag(TunnelFrame.FLAG_SYN)) {
            // ACK for a stream we initiated -- register it
            TunnelStreamChannel stream = streamManager.getStream(frame.getStreamId());
            if (stream != null) {
                log.debug("Stream {} open ACK received", frame.getStreamId());
            }
            return;
        }

        // DMZ wants to open a stream TO an internal service
        int streamId = frame.getStreamId();
        byte[] payload = extractPayloadBytes(frame.getPayload());

        try {
            Map<?, ?> openRequest = MAPPER.readValue(payload, Map.class);
            String targetHost = (String) openRequest.get("targetHost");
            int targetPort = ((Number) openRequest.get("targetPort")).intValue();
            String mappingName = openRequest.containsKey("mappingName")
                    ? (String) openRequest.get("mappingName") : "unknown";

            log.info("Stream {} open request: {} -> {}:{}", streamId, mappingName, targetHost, targetPort);

            // Accept the stream in the manager
            TunnelStreamChannel tunnelStream = streamManager.acceptStream(
                    streamId, new InetSocketAddress(targetHost, targetPort));
            if (tunnelStream == null) {
                log.warn("Failed to accept stream {} (max streams reached)", streamId);
                return;
            }

            // Connect to the internal service
            Bootstrap bootstrap = new Bootstrap()
                    .group(ctx.channel().eventLoop())
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // Pipeline is empty initially -- TunnelStreamRelay.bridge() will add handlers
                        }
                    });

            bootstrap.connect(targetHost, targetPort).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    Channel localChannel = future.channel();
                    // Bridge the tunnel stream with the local connection
                    TunnelStreamRelay.bridge(tunnelStream, localChannel);
                    // Send ACK to DMZ
                    ctx.writeAndFlush(TunnelFrame.streamOpenAck(streamId));
                    log.info("Stream {} bridged to {}:{} ({})", streamId, targetHost, targetPort, mappingName);
                } else {
                    log.error("Failed to connect stream {} to {}:{}: {}",
                            streamId, targetHost, targetPort, future.cause().getMessage());
                    streamManager.removeStream(streamId);
                    ctx.writeAndFlush(TunnelFrame.streamReset(streamId));
                }
            });

        } catch (Exception e) {
            log.error("Failed to process stream open for stream {}: {}", streamId, e.getMessage());
            ctx.writeAndFlush(TunnelFrame.streamReset(streamId));
        }
    }

    private void handleStreamClose(TunnelFrame frame) {
        streamManager.closeStream(frame.getStreamId(), frame.getFlags());
    }

    private void handleControlRequest(ChannelHandlerContext ctx, TunnelFrame frame) {
        byte[] payload = extractPayloadBytes(frame.getPayload());
        try {
            ControlMessage request = ControlMessageCodec.decode(payload);
            controlForwarder.forward(ctx.channel(), request);
        } catch (Exception e) {
            log.error("Failed to decode control request: {}", e.getMessage());
        }
    }

    private void handleControlResponse(TunnelFrame frame) {
        byte[] payload = extractPayloadBytes(frame.getPayload());
        try {
            ControlMessage response = ControlMessageCodec.decode(payload);
            PendingControlRequest pending = pendingControlRequests.remove(response.getCorrelationId());
            if (pending != null) {
                pending.complete(response);
                log.debug("Control response received: correlationId={}, status={}, elapsed={}ms",
                        response.getCorrelationId(), response.getStatusCode(), pending.elapsedMs());
            } else {
                log.warn("No pending control request for correlationId={}", response.getCorrelationId());
            }
        } catch (Exception e) {
            log.error("Failed to decode control response: {}", e.getMessage());
        }
    }

    private void handleWindowUpdate(TunnelFrame frame) {
        int increment = frame.getPayload().readInt();
        streamManager.handleWindowUpdate(frame.getStreamId(), increment);
    }

    private void handleHealthProbe(ChannelHandlerContext ctx, TunnelFrame frame) {
        byte[] payload = extractPayloadBytes(frame.getPayload());
        TunnelHealthProber.probe(ctx.channel(), payload);
    }

    private void handleHealthResult(TunnelFrame frame) {
        byte[] payload = extractPayloadBytes(frame.getPayload());
        try {
            Map<?, ?> result = MAPPER.readValue(payload, Map.class);
            String probeId = (String) result.get("probeId");
            if (probeId != null) {
                CompletableFuture<byte[]> future = pendingHealthRequests.remove(probeId);
                if (future != null) {
                    future.complete(payload);
                }
            }
        } catch (Exception e) {
            log.debug("Health result received (no pending future): {}", e.getMessage());
        }
    }

    private void handleGoAway(ChannelHandlerContext ctx) {
        log.warn("GO_AWAY received from DMZ proxy, closing all streams and reconnecting");
        if (streamManager != null) {
            streamManager.closeAll();
        }
        ctx.close(); // channelInactive will trigger TunnelReconnectHandler
    }

    /**
     * Sends a CONTROL_REQ through the tunnel and returns a future for the response.
     *
     * @param request   the control message to send
     * @param timeoutMs timeout in milliseconds
     * @return future that completes with the response ControlMessage
     */
    public CompletableFuture<ControlMessage> sendControlRequest(ControlMessage request, long timeoutMs) {
        if (request.getCorrelationId() == null) {
            request.setCorrelationId(UUID.randomUUID().toString());
        }
        PendingControlRequest pending = new PendingControlRequest(request.getCorrelationId(), timeoutMs);
        pendingControlRequests.put(request.getCorrelationId(), pending);

        byte[] encoded = ControlMessageCodec.encode(request);
        Channel tunnelChannel = tunnelClient.getChannel();
        if (tunnelChannel == null || !tunnelChannel.isActive()) {
            pending.fail(new IllegalStateException("Tunnel is not active"));
            pendingControlRequests.remove(request.getCorrelationId());
            return pending.getFuture();
        }

        tunnelChannel.writeAndFlush(TunnelFrame.controlRequest(encoded)).addListener(f -> {
            if (!f.isSuccess()) {
                pending.fail(f.cause());
                pendingControlRequests.remove(request.getCorrelationId());
            }
        });

        // Clean up on timeout
        pending.getFuture().whenComplete((result, ex) ->
                pendingControlRequests.remove(request.getCorrelationId()));

        return pending.getFuture();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Internal tunnel handler error: {}", cause.getMessage());
        ctx.close();
    }

    /**
     * Extracts bytes from a ByteBuf payload without affecting the reader index adversely.
     */
    private byte[] extractPayloadBytes(ByteBuf payload) {
        byte[] bytes = new byte[payload.readableBytes()];
        payload.readBytes(bytes);
        return bytes;
    }
}
