package com.filetransfer.tunnel.stream;

import com.filetransfer.tunnel.flow.TunnelFlowController;
import com.filetransfer.tunnel.protocol.TunnelFrame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Virtual Netty Channel backed by a multiplexed tunnel stream.
 * <p>
 * RelayHandler, BandwidthQoS, IntelligentProxyHandler see standard Channel semantics:
 * <ul>
 *   <li>{@code writeAndFlush(ByteBuf)} wraps in DATA frame, writes to tunnel</li>
 *   <li>{@code config().setAutoRead(false)} stops dispatching DATA frames for this stream</li>
 *   <li>{@code close()} sends STREAM_CLOSE frame</li>
 *   <li>{@code isActive()} reflects stream open AND tunnel active</li>
 * </ul>
 * Zero-copy: doWrite slices the ByteBuf into the DATA frame payload without copying.
 */
@Slf4j
public class TunnelStreamChannel extends AbstractChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(true, 16);

    private final int streamId;
    private final Channel tunnelChannel;          // physical TCP tunnel
    private final TunnelFlowController flowController;
    private final DefaultChannelConfig config;

    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile boolean readPending;

    // Addressing for ChannelHandlerContext introspection
    private final SocketAddress localAddr;
    private final SocketAddress remoteAddr;

    public TunnelStreamChannel(Channel parent, Channel tunnelChannel, int streamId,
                               TunnelFlowController flowController,
                               SocketAddress remoteAddr) {
        super(parent);
        this.tunnelChannel = tunnelChannel;
        this.streamId = streamId;
        this.flowController = flowController;
        this.localAddr = tunnelChannel.localAddress();
        this.remoteAddr = remoteAddr != null ? remoteAddr
                : new InetSocketAddress("tunnel-stream-" + streamId, 0);
        this.config = new DefaultChannelConfig(this);
    }

    public int streamId() {
        return streamId;
    }

    // ── Channel state ──

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public boolean isActive() {
        return active.get() && open.get() && tunnelChannel.isActive();
    }

    public void activate() {
        active.set(true);
    }

    // ── Metadata & config ──

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true; // works with any EventLoop
    }

    // ── Addressing ──

    @Override
    protected SocketAddress localAddress0() {
        return localAddr;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddr;
    }

    // ── I/O operations ──

    @Override
    protected void doRegister() {
        // No-op: stream registration handled by TunnelStreamManager
    }

    @Override
    protected void doDeregister() {
        // No-op
    }

    @Override
    protected void doBeginRead() {
        readPending = true;
    }

    /**
     * Wraps outbound ByteBufs into DATA frames and writes to the physical tunnel.
     * Zero-copy: retains a slice of the original buffer (no memcpy on the hot path).
     * Respects per-stream flow control window.
     */
    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        while (true) {
            Object msg = in.current();
            if (msg == null) break;

            if (msg instanceof ByteBuf buf) {
                int readable = buf.readableBytes();
                if (readable == 0) {
                    in.remove();
                    continue;
                }

                // Flow control: check available window
                int allowed = flowController.tryConsume(streamId, readable);
                if (allowed <= 0) {
                    // Window exhausted — stop writing, will resume on WINDOW_UPDATE
                    break;
                }

                // Slice only what the window allows (zero-copy retain)
                ByteBuf slice = buf.readRetainedSlice(allowed);
                TunnelFrame frame = TunnelFrame.data(streamId, slice);

                tunnelChannel.write(frame, tunnelChannel.voidPromise());

                if (buf.readableBytes() == 0) {
                    in.remove();
                } else {
                    // Partial write — will continue on next flush cycle
                    in.progress(allowed);
                    break;
                }
            } else {
                // Non-ByteBuf messages passed through as-is (shouldn't happen in normal flow)
                in.remove();
            }
        }
        tunnelChannel.flush();
    }

    @Override
    protected void doClose() {
        if (open.compareAndSet(true, false)) {
            active.set(false);
            if (tunnelChannel.isActive()) {
                tunnelChannel.writeAndFlush(TunnelFrame.streamClose(streamId),
                        tunnelChannel.voidPromise());
            }
            log.debug("Stream {} closed", streamId);
        }
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        // Virtual channel — bind is a no-op
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    // ── Unsafe ──

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new TunnelStreamUnsafe();
    }

    /**
     * Receives inbound DATA from the tunnel and fires it through this channel's pipeline.
     * Called by TunnelStreamManager when DATA frame arrives for this stream.
     */
    public void receiveData(ByteBuf data) {
        if (!isActive()) {
            data.release();
            return;
        }
        if (!readPending && !config.isAutoRead()) {
            // Backpressure: not reading — buffer will be queued by manager
            data.release();
            return;
        }
        readPending = false;
        pipeline().fireChannelRead(data);
        pipeline().fireChannelReadComplete();
    }

    /**
     * Signals that the remote end has closed this stream (FIN received).
     */
    public void remoteClose() {
        if (open.compareAndSet(true, false)) {
            active.set(false);
            pipeline().fireChannelInactive();
        }
    }

    /**
     * Signals that the remote end has reset this stream (RST received).
     */
    public void remoteReset() {
        if (open.compareAndSet(true, false)) {
            active.set(false);
            pipeline().fireExceptionCaught(
                    new ChannelException("Stream " + streamId + " reset by remote"));
            pipeline().fireChannelInactive();
        }
    }

    /**
     * Called when WINDOW_UPDATE received — resume writing if we were blocked.
     */
    public void onWindowUpdate() {
        if (isActive()) {
            eventLoop().execute(this::flush);
        }
    }

    private class TunnelStreamUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress,
                            ChannelPromise promise) {
            // Virtual channel — "connect" just activates
            activate();
            promise.setSuccess();
        }
    }
}
