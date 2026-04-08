package com.filetransfer.tunnel.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.ToString;

/**
 * Single multiplexed frame on the tunnel wire.
 * <pre>
 * [4B StreamID][4B Length][1B Type][1B Flags][Payload]
 * Header: 10 bytes fixed
 * </pre>
 */
@Getter
@ToString(exclude = "payload")
public class TunnelFrame {

    public static final int HEADER_SIZE = 10;
    public static final int MAX_PAYLOAD_SIZE = 256 * 1024; // 256KB

    // Flag constants
    public static final byte FLAG_FIN = 0x01;
    public static final byte FLAG_SYN = 0x02;
    public static final byte FLAG_ACK = 0x04;
    public static final byte FLAG_RST = 0x08;

    private final int streamId;
    private final TunnelFrameType type;
    private final byte flags;
    private final ByteBuf payload;

    public TunnelFrame(int streamId, TunnelFrameType type, byte flags, ByteBuf payload) {
        this.streamId = streamId;
        this.type = type;
        this.flags = flags;
        this.payload = payload != null ? payload : Unpooled.EMPTY_BUFFER;
    }

    public int payloadLength() {
        return payload.readableBytes();
    }

    public boolean hasFlag(byte flag) {
        return (flags & flag) != 0;
    }

    // ── Factory methods ──

    public static TunnelFrame data(int streamId, ByteBuf payload) {
        return new TunnelFrame(streamId, TunnelFrameType.DATA, (byte) 0, payload);
    }

    public static TunnelFrame streamOpen(int streamId, byte[] payload) {
        return new TunnelFrame(streamId, TunnelFrameType.STREAM_OPEN, FLAG_SYN,
                Unpooled.wrappedBuffer(payload));
    }

    public static TunnelFrame streamOpenAck(int streamId) {
        return new TunnelFrame(streamId, TunnelFrameType.STREAM_OPEN, FLAG_ACK, null);
    }

    public static TunnelFrame streamClose(int streamId) {
        return new TunnelFrame(streamId, TunnelFrameType.STREAM_CLOSE, FLAG_FIN, null);
    }

    public static TunnelFrame streamReset(int streamId) {
        return new TunnelFrame(streamId, TunnelFrameType.STREAM_CLOSE, FLAG_RST, null);
    }

    public static TunnelFrame controlRequest(byte[] payload) {
        return new TunnelFrame(0, TunnelFrameType.CONTROL_REQ, (byte) 0,
                Unpooled.wrappedBuffer(payload));
    }

    public static TunnelFrame controlResponse(byte[] payload) {
        return new TunnelFrame(0, TunnelFrameType.CONTROL_RES, (byte) 0,
                Unpooled.wrappedBuffer(payload));
    }

    public static TunnelFrame ping(long timestamp) {
        ByteBuf buf = Unpooled.buffer(8);
        buf.writeLong(timestamp);
        return new TunnelFrame(0, TunnelFrameType.PING, (byte) 0, buf);
    }

    public static TunnelFrame pong(long timestamp) {
        ByteBuf buf = Unpooled.buffer(8);
        buf.writeLong(timestamp);
        return new TunnelFrame(0, TunnelFrameType.PONG, (byte) 0, buf);
    }

    public static TunnelFrame windowUpdate(int streamId, int increment) {
        ByteBuf buf = Unpooled.buffer(4);
        buf.writeInt(increment);
        return new TunnelFrame(streamId, TunnelFrameType.WINDOW_UPDATE, (byte) 0, buf);
    }

    public static TunnelFrame healthProbe(byte[] payload) {
        return new TunnelFrame(0, TunnelFrameType.HEALTH_PROBE, (byte) 0,
                Unpooled.wrappedBuffer(payload));
    }

    public static TunnelFrame healthResult(byte[] payload) {
        return new TunnelFrame(0, TunnelFrameType.HEALTH_RESULT, (byte) 0,
                Unpooled.wrappedBuffer(payload));
    }

    public static TunnelFrame goAway() {
        return new TunnelFrame(0, TunnelFrameType.GO_AWAY, (byte) 0, null);
    }
}
