package com.filetransfer.shared.enums;

public enum EncryptionOption {
    /** No encryption/decryption — forward file as-is */
    NONE,
    /** Encrypt file using destination's key before forwarding */
    ENCRYPT_BEFORE_FORWARD,
    /** Decrypt file using source's key before forwarding */
    DECRYPT_THEN_FORWARD
}
