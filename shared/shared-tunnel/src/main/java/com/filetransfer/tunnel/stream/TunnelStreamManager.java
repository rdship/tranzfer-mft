package com.filetransfer.tunnel.stream;

import com.filetransfer.tunnel.flow.TunnelFlowController;
import com.filetransfer.tunnel.protocol.TunnelFrame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of multiplexed streams over a single tunnel connection.
 * <p>
 * Stream ID allocation: odd = DMZ-initiated, even = internal-initiated, 0 = control channel.
 * Thread-safe: all operations use ConcurrentHashMap and atomic counters.
 */
@Slf4j
public class TunnelStreamManager {

    private final ConcurrentHashMap<Integer, TunnelStreamChannel> streams = new ConcurrentHashMap<>();
    private final Channel tunnelChannel;
    private final TunnelFlowController flowController;
    private final boolean dmzSide; // true = DMZ (odd IDs), false = internal (even IDs)
    private final AtomicInteger nextStreamId;
    private final int maxStreams;

    // Callback when remote opens a stream (set by DmzTunnelHandler / InternalTunnelHandler)
    private volatile Consumer<TunnelStreamChannel> onStreamOpened;

    public TunnelStreamManager(Channel tunnelChannel, TunnelFlowController flowController,
                               boolean dmzSide, int maxStreams) {
        this.tunnelChannel = tunnelChannel;
        this.flowController = flowController;
        this.dmzSide = dmzSide;
        this.maxStreams = maxStreams;
        this.nextStreamId = new AtomicInteger(dmzSide ? 1 : 2);
    }

    public void setOnStreamOpened(Consumer<TunnelStreamChannel> callback) {
        this.onStreamOpened = callback;
    }

    /**
     * Creates a new outbound stream. Returns the virtual channel registered on the tunnel EventLoop.
     */
    public TunnelStreamChannel createStream(SocketAddress remoteAddr) {
        int id = nextStreamId.getAndAdd(2);
        if (streams.size() >= maxStreams) {
            log.warn("Max streams ({}) reached, rejecting stream {}", maxStreams, id);
            return null;
        }
        TunnelStreamChannel stream = newStreamChannel(id, remoteAddr);
        streams.put(id, stream);
        flowController.registerStream(id);
        registerOnEventLoop(stream);
        log.debug("Created outbound stream {} (total: {})", id, streams.size());
        return stream;
    }

    /**
     * Handles STREAM_OPEN from the remote side. Creates the virtual channel and notifies the callback.
     */
    public TunnelStreamChannel acceptStream(int streamId, SocketAddress remoteAddr) {
        if (streams.size() >= maxStreams) {
            log.warn("Max streams ({}) reached, rejecting inbound stream {}", maxStreams, streamId);
            tunnelChannel.writeAndFlush(TunnelFrame.streamReset(streamId));
            return null;
        }
        TunnelStreamChannel stream = newStreamChannel(streamId, remoteAddr);
        streams.put(streamId, stream);
        flowController.registerStream(streamId);
        registerOnEventLoop(stream);

        Consumer<TunnelStreamChannel> cb = onStreamOpened;
        if (cb != null) {
            cb.accept(stream);
        }
        log.debug("Accepted inbound stream {} (total: {})", streamId, streams.size());
        return stream;
    }

    /**
     * Dispatches a DATA frame to the appropriate stream channel.
     */
    public void dispatchData(int streamId, ByteBuf data) {
        TunnelStreamChannel stream = streams.get(streamId);
        if (stream == null || !stream.isActive()) {
            log.debug("DATA for unknown/inactive stream {}, discarding {} bytes", streamId, data.readableBytes());
            data.release();
            return;
        }
        // Update flow control — we consumed window on the receive side
        flowController.onDataReceived(streamId, data.readableBytes());
        stream.receiveData(data);
    }

    /**
     * Handles STREAM_CLOSE (FIN) from remote.
     */
    public void closeStream(int streamId, byte flags) {
        TunnelStreamChannel stream = streams.remove(streamId);
        if (stream == null) return;
        flowController.deregisterStream(streamId);

        if ((flags & TunnelFrame.FLAG_RST) != 0) {
            stream.remoteReset();
        } else {
            stream.remoteClose();
        }
        log.debug("Stream {} closed by remote (flags=0x{}, remaining: {})",
                streamId, Integer.toHexString(flags & 0xFF), streams.size());
    }

    /**
     * Handles WINDOW_UPDATE for a stream.
     */
    public void handleWindowUpdate(int streamId, int increment) {
        flowController.onWindowUpdate(streamId, increment);
        TunnelStreamChannel stream = streams.get(streamId);
        if (stream != null) {
            stream.onWindowUpdate();
        }
    }

    /**
     * Locally removes a stream (e.g., when the local side closes it).
     */
    public void removeStream(int streamId) {
        TunnelStreamChannel stream = streams.remove(streamId);
        if (stream != null) {
            flowController.deregisterStream(streamId);
            log.debug("Stream {} removed locally (remaining: {})", streamId, streams.size());
        }
    }

    /**
     * Closes all streams — called on tunnel disconnect or GO_AWAY.
     */
    public void closeAll() {
        log.info("Closing all {} streams", streams.size());
        for (Map.Entry<Integer, TunnelStreamChannel> entry : streams.entrySet()) {
            entry.getValue().remoteClose();
            flowController.deregisterStream(entry.getKey());
        }
        streams.clear();
    }

    public TunnelStreamChannel getStream(int streamId) {
        return streams.get(streamId);
    }

    public int activeStreamCount() {
        return streams.size();
    }

    /**
     * Sends a WINDOW_UPDATE to the remote for a given stream, letting it send more data.
     * Called after consuming data from the stream.
     */
    public void sendWindowUpdate(int streamId, int consumed) {
        int increment = flowController.onDataConsumed(streamId, consumed);
        if (increment > 0) {
            tunnelChannel.writeAndFlush(TunnelFrame.windowUpdate(streamId, increment),
                    tunnelChannel.voidPromise());
        }
    }

    private TunnelStreamChannel newStreamChannel(int streamId, SocketAddress remoteAddr) {
        return new TunnelStreamChannel(null, tunnelChannel, streamId, flowController, remoteAddr);
    }

    private void registerOnEventLoop(TunnelStreamChannel stream) {
        EventLoop loop = tunnelChannel.eventLoop();
        loop.register(stream).addListener(f -> {
            if (f.isSuccess()) {
                stream.activate();
                stream.pipeline().fireChannelActive();
            } else {
                log.error("Failed to register stream {} on EventLoop", stream.streamId(), f.cause());
                streams.remove(stream.streamId());
            }
        });
    }
}
