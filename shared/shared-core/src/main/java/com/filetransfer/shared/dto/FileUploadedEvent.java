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
public class FileUploadedEvent {
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
}
