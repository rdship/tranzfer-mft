package com.filetransfer.shared.exception;

import com.filetransfer.shared.enums.ErrorCode;
import lombok.Getter;

/**
 * Base exception for all platform-specific errors.
 * Carries an ErrorCode for consistent API responses.
 */
@Getter
public class PlatformException extends RuntimeException {

    private final ErrorCode errorCode;

    public PlatformException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PlatformException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
