package com.filetransfer.shared.exception;

import com.filetransfer.shared.enums.ErrorCode;

/**
 * Thrown when a requested entity does not exist.
 * Maps to HTTP 404.
 */
public class EntityNotFoundException extends PlatformException {

    public EntityNotFoundException(String entityType, Object id) {
        super(ErrorCode.ENTITY_NOT_FOUND, entityType + " with ID " + id + " not found");
    }

    public EntityNotFoundException(String message) {
        super(ErrorCode.ENTITY_NOT_FOUND, message);
    }
}
