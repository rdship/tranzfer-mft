package com.filetransfer.shared.enums;

/**
 * Authentication types supported by delivery endpoints.
 */
public enum AuthType {
    NONE,
    BASIC,
    BEARER_TOKEN,
    API_KEY,
    SSH_KEY,
    OAUTH2
}
