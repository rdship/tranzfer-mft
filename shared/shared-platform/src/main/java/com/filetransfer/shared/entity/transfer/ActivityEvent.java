package com.filetransfer.shared.entity.transfer;

import com.filetransfer.shared.entity.core.*;

import lombok.*;
import java.time.Instant;

/**
 * In-memory real-time activity event (not persisted — ephemeral).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ActivityEvent {
    private String trackId;
    private String eventType; // CONNECTION_OPEN, FILE_UPLOAD_START, FILE_UPLOAD_COMPLETE, FILE_ROUTE, FILE_DOWNLOAD, CONNECTION_CLOSE
    private String protocol; // SFTP, FTP, HTTPS, P2P
    private String account;
    private String filename;
    private Long fileSizeBytes;
    private String sourceIp;
    private String status; // IN_PROGRESS, COMPLETED, FAILED
    private String service;
    private Instant timestamp;
}
