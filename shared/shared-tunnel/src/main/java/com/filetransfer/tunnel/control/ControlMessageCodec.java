package com.filetransfer.tunnel.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Serializes/deserializes {@link ControlMessage} to/from JSON bytes.
 * <p>
 * Used only on the control channel (stream 0) — NOT on the data hot path.
 * Jackson ObjectMapper is thread-safe and reused.
 */
@Slf4j
public class ControlMessageCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ControlMessageCodec() {}

    public static byte[] encode(ControlMessage msg) {
        try {
            return MAPPER.writeValueAsBytes(msg);
        } catch (Exception e) {
            log.error("Failed to encode ControlMessage: {}", e.getMessage());
            throw new RuntimeException("Control message encoding failed", e);
        }
    }

    public static ControlMessage decode(byte[] data) {
        try {
            return MAPPER.readValue(data, ControlMessage.class);
        } catch (Exception e) {
            log.error("Failed to decode ControlMessage: {}", e.getMessage());
            throw new RuntimeException("Control message decoding failed", e);
        }
    }

    public static ControlMessage decode(byte[] data, int offset, int length) {
        try {
            return MAPPER.readValue(data, offset, length, ControlMessage.class);
        } catch (Exception e) {
            log.error("Failed to decode ControlMessage: {}", e.getMessage());
            throw new RuntimeException("Control message decoding failed", e);
        }
    }
}
