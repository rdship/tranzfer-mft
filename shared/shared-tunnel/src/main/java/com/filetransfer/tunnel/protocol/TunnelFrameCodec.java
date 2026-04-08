package com.filetransfer.tunnel.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Encodes/decodes tunnel frames on the wire.
 * <pre>
 * [4B StreamID][4B Length][1B Type][1B Flags][Payload]
 * </pre>
 */
@Slf4j
public class TunnelFrameCodec extends ByteToMessageCodec<TunnelFrame> {

    private final int maxPayloadSize;

    public TunnelFrameCodec() {
        this(TunnelFrame.MAX_PAYLOAD_SIZE);
    }

    public TunnelFrameCodec(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, TunnelFrame frame, ByteBuf out) {
        out.writeInt(frame.getStreamId());
        out.writeInt(frame.payloadLength());
        out.writeByte(frame.getType().getCode());
        out.writeByte(frame.getFlags());
        if (frame.payloadLength() > 0) {
            out.writeBytes(frame.getPayload(), frame.getPayload().readerIndex(), frame.payloadLength());
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= TunnelFrame.HEADER_SIZE) {
            in.markReaderIndex();

            int streamId = in.readInt();
            int length = in.readInt();
            int typeCode = in.readByte() & 0xFF;
            byte flags = in.readByte();

            // Validate payload length
            if (length < 0 || length > maxPayloadSize) {
                log.error("Invalid frame payload size: {} (max {})", length, maxPayloadSize);
                ctx.close();
                return;
            }

            // Wait for full payload
            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }

            TunnelFrameType type = TunnelFrameType.fromCode(typeCode);
            ByteBuf payload = length > 0 ? in.readRetainedSlice(length) : null;

            out.add(new TunnelFrame(streamId, type, flags, payload));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Tunnel codec error: {}", cause.getMessage());
        ctx.close();
    }
}
