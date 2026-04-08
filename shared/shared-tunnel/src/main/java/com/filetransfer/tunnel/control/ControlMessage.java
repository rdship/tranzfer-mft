package com.filetransfer.tunnel.control;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * HTTP-like request/response message carried over the tunnel control channel (stream 0).
 * <p>
 * Request: correlationId + method + path + headers + body (statusCode unused).
 * Response: correlationId + statusCode + body (method/path unused).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControlMessage {

    private String correlationId;
    private String method;       // GET, POST, PUT, DELETE
    private String path;         // /api/v1/proxy/verdict, /api/keystore/entries/...
    private Map<String, String> headers;
    private byte[] body;
    private int statusCode;      // 200, 404, 500 etc. (response only)

    // ── Factory methods ──

    public static ControlMessage request(String correlationId, String method, String path,
                                         Map<String, String> headers, byte[] body) {
        return ControlMessage.builder()
                .correlationId(correlationId)
                .method(method)
                .path(path)
                .headers(headers)
                .body(body)
                .build();
    }

    public static ControlMessage response(String correlationId, int statusCode, byte[] body) {
        return ControlMessage.builder()
                .correlationId(correlationId)
                .statusCode(statusCode)
                .body(body)
                .build();
    }

    public boolean isRequest() {
        return method != null;
    }
}
