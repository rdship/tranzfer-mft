package com.filetransfer.shared.matching;

import com.filetransfer.shared.enums.Protocol;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/**
 * All available dimensions at file match time.
 * Built by RoutingEngine from the upload event context.
 * Immutable value object — safe for concurrent evaluation.
 */
public record MatchContext(
        String filename,
        String extension,
        long fileSize,
        Protocol protocol,
        Direction direction,
        UUID partnerId,
        String partnerSlug,
        String accountUsername,
        UUID sourceAccountId,
        String sourcePath,
        String sourceIp,
        String ediStandard,
        String ediType,
        LocalTime timeOfDay,
        DayOfWeek dayOfWeek,
        Map<String, String> metadata
) {

    public enum Direction { INBOUND, OUTBOUND }

    public static MatchContextBuilder builder() {
        return new MatchContextBuilder();
    }
}
