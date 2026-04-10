package com.filetransfer.shared.flow;

/**
 * I/O priority lanes for flow processing. Each lane has its own concurrency
 * budget (semaphore permits) to prevent any single traffic class from
 * starving the others.
 */
public enum IOLane {
    /** Partner SFTP uploads, flow-processing reads — lowest latency, highest priority. */
    REALTIME,
    /** Tier migrations, scheduled backups — throttled to protect REALTIME. */
    BULK,
    /** Predictive pre-stage, dedup scans, orphan cleanup — best-effort. */
    BACKGROUND
}
