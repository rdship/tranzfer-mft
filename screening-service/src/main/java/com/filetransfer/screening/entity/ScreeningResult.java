package com.filetransfer.screening.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "screening_results", indexes = {
    @Index(name = "idx_screen_track", columnList = "trackId"),
    @Index(name = "idx_screen_outcome", columnList = "outcome")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ScreeningResult {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    /** Track ID of the file transfer */
    @Column(length = 64) private String trackId;
    /** File that was screened */
    @Column(nullable = false) private String filename;
    /** Account that submitted the file */
    private String accountUsername;
    /** CLEAR, HIT, POSSIBLE_HIT, ERROR */
    @Column(nullable = false, length = 15) private String outcome;
    /** Number of records scanned from the file */
    private int recordsScanned;
    /** Number of hits found */
    private int hitsFound;
    /** Duration in milliseconds */
    private long durationMs;
    /** Matched entries details */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private List<HitDetail> hits;
    /** Action taken: BLOCKED, FLAGGED, PASSED */
    @Column(length = 10) private String actionTaken;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant screenedAt = Instant.now();

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HitDetail {
        private String matchedName;
        private String sanctionsListName;
        private String sanctionsListSource;
        private double matchScore;
        private String fileField;
        private String fileValue;
        private int lineNumber;
    }
}
