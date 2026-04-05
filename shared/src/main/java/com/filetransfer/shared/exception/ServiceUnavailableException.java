package com.filetransfer.shared.exception;

import com.filetransfer.shared.enums.ErrorCode;

/**
 * Thrown when a downstream service is unreachable (connection refused, timeout, circuit open).
 * Maps to HTTP 503.
 */
public class ServiceUnavailableException extends PlatformException {

    public ServiceUnavailableException(String serviceName) {
        super(ErrorCode.SERVICE_UNAVAILABLE, serviceName + " is currently unavailable");
    }

    public ServiceUnavailableException(String serviceName, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, serviceName + " is currently unavailable", cause);
    }
}
