package com.filetransfer.fabric;

public class FabricPublishException extends RuntimeException {
    public FabricPublishException(String message) { super(message); }
    public FabricPublishException(String message, Throwable cause) { super(message, cause); }
}
