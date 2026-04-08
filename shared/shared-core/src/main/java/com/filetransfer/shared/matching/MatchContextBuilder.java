package com.filetransfer.shared.matching;

import com.filetransfer.shared.enums.Protocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builder for MatchContext. Not Lombok — MatchContext is a record.
 */
public class MatchContextBuilder {

    private String filename;
    private String extension;
    private long fileSize = -1;
    private Protocol protocol;
    private MatchContext.Direction direction;
    private UUID partnerId;
    private String partnerSlug;
    private String accountUsername;
    private UUID sourceAccountId;
    private String sourcePath;
    private String sourceIp;
    private String ediStandard;
    private String ediType;
    private LocalTime timeOfDay;
    private DayOfWeek dayOfWeek;
    private Map<String, String> metadata = new HashMap<>();

    /**
     * Populate core fields from a file upload event.
     */
    public MatchContextBuilder fromUploadEvent(Protocol accountProtocol,
                                                String accountUsername,
                                                UUID accountId,
                                                UUID accountPartnerId,
                                                String relativeFilePath,
                                                String absoluteSourcePath) {
        // Extract filename from path
        String fname = relativeFilePath;
        if (fname.contains("/")) {
            fname = fname.substring(fname.lastIndexOf('/') + 1);
        }
        this.filename = fname;

        // Derive extension (lowercase, no dot)
        int dotIdx = fname.lastIndexOf('.');
        this.extension = dotIdx >= 0 ? fname.substring(dotIdx + 1).toLowerCase() : "";

        // Account fields
        this.protocol = accountProtocol;
        this.accountUsername = accountUsername;
        this.sourceAccountId = accountId;
        this.partnerId = accountPartnerId;

        this.sourcePath = relativeFilePath;
        return this;
    }

    public MatchContextBuilder withDirection(MatchContext.Direction direction) {
        this.direction = direction;
        return this;
    }

    public MatchContextBuilder withFileSize(long fileSize) {
        this.fileSize = fileSize;
        return this;
    }

    public MatchContextBuilder withSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
        return this;
    }

    public MatchContextBuilder withPartnerSlug(String partnerSlug) {
        this.partnerSlug = partnerSlug;
        return this;
    }

    /**
     * Detect EDI standard and type from file header (first 128 bytes).
     * Safe to call on any file — returns silently if not EDI.
     */
    public MatchContextBuilder withEdiDetection(Path filePath) {
        try {
            if (filePath != null && Files.exists(filePath) && Files.size(filePath) > 0) {
                EdiDetector.EdiInfo info = EdiDetector.detect(filePath);
                if (info != null) {
                    this.ediStandard = info.standard();
                    this.ediType = info.typeCode();
                }
            }
        } catch (Exception ignored) {
            // EDI detection is best-effort
        }
        return this;
    }

    public MatchContextBuilder withTimeNow() {
        this.timeOfDay = LocalTime.now();
        this.dayOfWeek = DayOfWeek.from(java.time.LocalDate.now());
        return this;
    }

    public MatchContextBuilder withFilename(String filename) {
        this.filename = filename;
        return this;
    }

    public MatchContextBuilder withExtension(String extension) {
        this.extension = extension;
        return this;
    }

    public MatchContextBuilder withProtocol(String protocolName) {
        this.protocol = protocolName != null ? Protocol.valueOf(protocolName) : null;
        return this;
    }

    public MatchContextBuilder withPartnerId(UUID partnerId) {
        this.partnerId = partnerId;
        return this;
    }

    public MatchContextBuilder withAccountUsername(String accountUsername) {
        this.accountUsername = accountUsername;
        return this;
    }

    public MatchContextBuilder withSourceAccountId(UUID sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
        return this;
    }

    public MatchContextBuilder withSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
        return this;
    }

    public MatchContextBuilder withEdiStandard(String ediStandard) {
        this.ediStandard = ediStandard;
        return this;
    }

    public MatchContextBuilder withEdiType(String ediType) {
        this.ediType = ediType;
        return this;
    }

    public MatchContextBuilder withDayOfWeek(DayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
        return this;
    }

    public MatchContextBuilder withTimeOfDay(LocalTime timeOfDay) {
        this.timeOfDay = timeOfDay;
        return this;
    }

    public MatchContextBuilder withHour(int hour) {
        this.timeOfDay = LocalTime.of(hour, 0);
        return this;
    }

    public MatchContextBuilder withMetadata(String key, String value) {
        this.metadata.put(key, value);
        return this;
    }

    public MatchContextBuilder withMetadata(Map<String, String> metadata) {
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }
        return this;
    }

    public MatchContext build() {
        return new MatchContext(
                filename, extension, fileSize, protocol, direction,
                partnerId, partnerSlug, accountUsername, sourceAccountId,
                sourcePath, sourceIp, ediStandard, ediType,
                timeOfDay, dayOfWeek,
                metadata.isEmpty() ? Map.of() : Map.copyOf(metadata)
        );
    }
}
