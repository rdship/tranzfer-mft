package com.filetransfer.shared.dto;

import com.filetransfer.shared.enums.Protocol;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Published to RabbitMQ when a file is uploaded via any protocol (SFTP/FTP/FTP-Web/AS2).
 * Consumers pick up events with backpressure (prefetch=1, manual ACK) to prevent
 * overloading the flow processing engine during burst uploads.
 *
 * <p>Routing key: {@code file.uploaded}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadedEvent implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private String trackId;
    private UUID accountId;
    private String username;
    private Protocol protocol;
    private String relativeFilePath;
    private String absoluteSourcePath;
    private String sourceIp;
    private String filename;
    private long fileSizeBytes;
    @Builder.Default
    private Instant timestamp = Instant.now();

    // ── Phase 1 enrichment: account snapshot fields ──
    // Eliminates TransferAccount DB re-fetch in FileUploadEventConsumer.
    // Consumer uses these for MatchContext building; full account loaded only
    // when legacy folder-mapping path is needed.
    private String storageMode;       // "VIRTUAL" or "PHYSICAL"
    private UUID partnerId;           // for partner slug resolution
    private String homeDir;           // for path computation
}
