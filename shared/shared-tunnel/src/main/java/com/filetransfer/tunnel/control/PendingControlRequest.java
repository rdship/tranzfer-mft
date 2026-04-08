package com.filetransfer.tunnel.control;

import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks an in-flight CONTROL_REQ awaiting its CONTROL_RES.
 * Wraps a CompletableFuture with automatic timeout.
 */
@Getter
public class PendingControlRequest {

    private final String correlationId;
    private final CompletableFuture<ControlMessage> future;
    private final long createdAtNanos;

    public PendingControlRequest(String correlationId, long timeoutMs) {
        this.correlationId = correlationId;
        this.createdAtNanos = System.nanoTime();
        this.future = new CompletableFuture<ControlMessage>()
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void complete(ControlMessage response) {
        future.complete(response);
    }

    public void fail(Throwable cause) {
        future.completeExceptionally(cause);
    }

    public long elapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - createdAtNanos);
    }
}
