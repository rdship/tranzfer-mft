package com.filetransfer.ai.entity.intelligence;

/**
 * Severity classification for threat intelligence indicators.
 *
 * <p>Levels are ordered from least to most severe and are used by the
 * {@link ThreatIndicator} entity to communicate the assessed risk
 * of an indicator of compromise.</p>
 */
public enum ThreatLevel {

    /** Threat level has not been assessed or is indeterminate. */
    UNKNOWN,

    /** Low-confidence or low-impact indicator; informational. */
    LOW,

    /** Moderate confidence or impact; warrants monitoring. */
    MEDIUM,

    /** High-confidence indicator associated with known threat activity. */
    HIGH,

    /** Confirmed active threat with severe potential impact. */
    CRITICAL
}
