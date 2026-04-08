package com.filetransfer.dmz.tunnel;

import com.filetransfer.dmz.proxy.PortMapping;
import com.filetransfer.tunnel.protocol.TunnelFrame;
import com.filetransfer.tunnel.stream.TunnelStreamChannel;
import com.filetransfer.tunnel.stream.TunnelStreamManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Replaces {@link io.netty.bootstrap.Bootstrap#connect()} with tunnel stream creation
 * for proxied connections. The returned {@link TunnelStreamChannel} is a real Netty Channel
 * that existing {@code RelayHandler} can use unchanged.
 * <p>
 * No Spring DI — constructed by {@link TunnelAcceptor} when a tunnel client connects.
 */
@Slf4j
public class TunnelAwareBackendConnector {

    private final DmzTunnelHandler tunnelHandler;

    public TunnelAwareBackendConnector(DmzTunnelHandler tunnelHandler) {
        this.tunnelHandler = tunnelHandler;
    }

    /**
     * Creates a multiplexed stream over the tunnel to reach the target backend.
     * Sends a STREAM_OPEN frame with JSON payload describing the target.
     * <p>
     * The returned {@link TunnelStreamChannel} is registered on the tunnel's EventLoop
     * and behaves as a standard Netty {@link Channel} — RelayHandler can write/read
     * ByteBufs without knowing it runs over a multiplexed tunnel.
     *
     * @param mapping       the port mapping defining the backend target
     * @param clientChannel the inbound client channel (for context/logging)
     * @return the stream channel's registration future (succeeds when the stream is ready)
     * @throws IllegalStateException if the tunnel is not active
     */
    public ChannelFuture connectViaStream(PortMapping mapping, Channel clientChannel) {
        TunnelStreamManager streamManager = tunnelHandler.getStreamManager();
        if (streamManager == null) {
            throw new IllegalStateException("Tunnel stream manager not initialized — tunnel not active");
        }

        Channel tunnelChannel = tunnelHandler.getTunnelChannel();
        if (tunnelChannel == null || !tunnelChannel.isActive()) {
            throw new IllegalStateException("Tunnel channel not active — cannot create stream for "
                    + mapping.getName());
        }

        InetSocketAddress targetAddr = new InetSocketAddress(mapping.getTargetHost(), mapping.getTargetPort());
        TunnelStreamChannel stream = streamManager.createStream(targetAddr);
        if (stream == null) {
            throw new IllegalStateException("Max streams reached — cannot create tunnel stream for "
                    + mapping.getName());
        }

        // Build STREAM_OPEN payload: lightweight JSON (no Jackson on data path)
        String payload = "{\"targetHost\":\"" + mapping.getTargetHost()
                + "\",\"targetPort\":" + mapping.getTargetPort()
                + ",\"mappingName\":\"" + mapping.getName() + "\"}";

        // Send STREAM_OPEN with SYN flag on the physical tunnel channel
        TunnelFrame openFrame = TunnelFrame.streamOpen(
                stream.streamId(), payload.getBytes(StandardCharsets.UTF_8));
        tunnelChannel.writeAndFlush(openFrame, tunnelChannel.voidPromise());

        log.debug("Opened tunnel stream {} for mapping [{}] -> {}:{} (client={})",
                stream.streamId(), mapping.getName(),
                mapping.getTargetHost(), mapping.getTargetPort(),
                clientChannel.remoteAddress());

        // The stream is already registered on the EventLoop by createStream().
        // Return a succeeded future — caller can attach the RelayHandler pipeline immediately.
        // Actual data flow begins once we receive STREAM_OPEN_ACK from gateway-service.
        return stream.newSucceededFuture();
    }
}
