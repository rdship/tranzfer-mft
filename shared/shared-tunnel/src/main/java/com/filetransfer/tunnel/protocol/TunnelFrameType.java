package com.filetransfer.tunnel.protocol;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Frame types for the YAMUX-inspired multiplexing protocol.
 */
@Getter
@RequiredArgsConstructor
public enum TunnelFrameType {
    DATA(0x00),
    STREAM_OPEN(0x01),
    STREAM_CLOSE(0x02),
    CONTROL_REQ(0x03),
    CONTROL_RES(0x04),
    PING(0x05),
    PONG(0x06),
    WINDOW_UPDATE(0x07),
    HEALTH_PROBE(0x08),
    HEALTH_RESULT(0x09),
    GO_AWAY(0x0A);

    private final int code;

    private static final TunnelFrameType[] BY_CODE = new TunnelFrameType[16];

    static {
        for (TunnelFrameType t : values()) {
            BY_CODE[t.code] = t;
        }
    }

    public static TunnelFrameType fromCode(int code) {
        if (code < 0 || code >= BY_CODE.length || BY_CODE[code] == null) {
            throw new IllegalArgumentException("Unknown frame type: 0x" + Integer.toHexString(code));
        }
        return BY_CODE[code];
    }
}
