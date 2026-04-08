package com.filetransfer.shared.enums;

/**
 * Supported protocols for delivery endpoints (external client communication).
 */
public enum DeliveryProtocol {
    SFTP,
    FTP,
    FTPS,
    HTTP,
    HTTPS,
    API,
    AS2,
    AS4
}
