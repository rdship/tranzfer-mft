package com.filetransfer.shared.enums;

/**
 * Deployment environments. Each environment can have its own
 * set of platform settings (ports, hosts, thresholds, etc.).
 */
public enum Environment {
    DEV,
    TEST,
    CERT,
    STAGING,
    PROD
}
