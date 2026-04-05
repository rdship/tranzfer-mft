package com.filetransfer.ai.entity.intelligence;

/**
 * Classification of threat intelligence indicator types.
 *
 * <p>Each value represents a distinct category of observable artefact
 * that can appear in threat intelligence feeds or be extracted from
 * live telemetry during file-transfer operations.</p>
 */
public enum IndicatorType {

    /** IPv4 or IPv6 address. */
    IP,

    /** Fully qualified domain name. */
    DOMAIN,

    /** Full URL including scheme, host, path, and query parameters. */
    URL,

    /** MD5 file hash (128-bit). */
    HASH_MD5,

    /** SHA-1 file hash (160-bit). */
    HASH_SHA1,

    /** SHA-256 file hash (256-bit). */
    HASH_SHA256,

    /** Email address associated with threat activity. */
    EMAIL,

    /** Common Vulnerabilities and Exposures identifier (e.g., CVE-2024-1234). */
    CVE,

    /** JA3 TLS client fingerprint hash. */
    JA3,

    /** JA4 TLS fingerprint (next-generation JA3). */
    JA4,

    /** Suspicious or malicious file name pattern. */
    FILENAME,

    /** Named mutex used by malware for single-instance enforcement. */
    MUTEX,

    /** Windows registry key associated with persistence or C2. */
    REGISTRY_KEY,

    /** HTTP User-Agent string linked to malicious tooling. */
    USER_AGENT,

    /** CIDR network range (e.g., 192.168.0.0/16). */
    CIDR
}
