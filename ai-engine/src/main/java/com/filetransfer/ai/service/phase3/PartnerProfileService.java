package com.filetransfer.ai.service.phase3;

import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI Phase 3: Learns each partner's (account) behavior patterns.
 *
 * Tracks per account:
 * - Typical delivery windows (what hour/day they usually send)
 * - Average file sizes and counts per day
 * - Normal filename patterns
 * - Typical transfer frequency
 * - Error rate baseline
 *
 * Uses this to:
 * - Flag deviations in real-time ("Partner X usually sends at 2am, this is 3pm")
 * - Auto-tune poll intervals (poll more during active windows)
 * - Predict next expected delivery
 * - Generate partner health scores
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartnerProfileService {

    private final FileTransferRecordRepository recordRepository;
    private final ConcurrentHashMap<String, PartnerProfile> profiles = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 600000) // every 10 min
    @SchedulerLock(name = "ai_partnerProfile_rebuildProfiles", lockAtLeastFor = "PT9M", lockAtMostFor = "PT20M")
    public void rebuildProfiles() {
        List<FileTransferRecord> allRecords = recordRepository.findAll();
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        // Group by source account username
        Map<String, List<FileTransferRecord>> byAccount = allRecords.stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(cutoff))
                .filter(r -> r.getFolderMapping() != null && r.getFolderMapping().getSourceAccount() != null)
                .collect(Collectors.groupingBy(r -> r.getFolderMapping().getSourceAccount().getUsername()));

        for (Map.Entry<String, List<FileTransferRecord>> entry : byAccount.entrySet()) {
            String username = entry.getKey();
            List<FileTransferRecord> records = entry.getValue();
            profiles.put(username, buildProfile(username, records));
        }

        log.info("Partner profiles rebuilt: {} accounts", profiles.size());
    }

    private PartnerProfile buildProfile(String username, List<FileTransferRecord> records) {
        // Active hours
        Map<Integer, Long> hourCounts = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getUploadedAt().atZone(ZoneOffset.UTC).getHour(),
                        Collectors.counting()));
        List<Integer> activeHours = hourCounts.entrySet().stream()
                .filter(e -> e.getValue() > records.size() / 48.0) // More than avg
                .map(Map.Entry::getKey).sorted().collect(Collectors.toList());

        // Active days of week
        Map<DayOfWeek, Long> dayCounts = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getUploadedAt().atZone(ZoneOffset.UTC).getDayOfWeek(),
                        Collectors.counting()));
        List<String> activeDays = dayCounts.entrySet().stream()
                .filter(e -> e.getValue() > records.size() / 14.0)
                .map(e -> e.getKey().name()).sorted().collect(Collectors.toList());

        // File sizes
        double[] sizes = records.stream()
                .filter(r -> r.getFileSizeBytes() != null)
                .mapToDouble(r -> r.getFileSizeBytes()).toArray();
        double avgSize = sizes.length > 0 ? Arrays.stream(sizes).average().orElse(0) : 0;
        double maxSize = sizes.length > 0 ? Arrays.stream(sizes).max().orElse(0) : 0;

        // Transfer frequency
        double avgPerDay = records.size() / 30.0;

        // Error rate
        long failures = records.stream()
                .filter(r -> "FAILED".equals(r.getStatus().name())).count();
        double errorRate = records.size() > 0 ? (double) failures / records.size() : 0;

        // Filename patterns (most common extensions)
        Map<String, Long> extensions = records.stream()
                .filter(r -> r.getOriginalFilename() != null)
                .collect(Collectors.groupingBy(r -> {
                    String fn = r.getOriginalFilename();
                    int dot = fn.lastIndexOf('.');
                    return dot >= 0 ? fn.substring(dot) : "(none)";
                }, Collectors.counting()));

        // Last transfer
        Instant lastTransfer = records.stream()
                .map(FileTransferRecord::getUploadedAt)
                .max(Comparator.naturalOrder()).orElse(null);

        // Predicted next delivery
        Instant predictedNext = predictNextDelivery(records, activeHours);

        // Health score (0-100)
        int health = 100;
        if (errorRate > 0.1) health -= 30;
        else if (errorRate > 0.05) health -= 15;
        if (lastTransfer != null && lastTransfer.isBefore(Instant.now().minus(2, ChronoUnit.DAYS))) {
            health -= 20; // Haven't heard from them in 2 days
        }
        health = Math.max(0, health);

        return PartnerProfile.builder()
                .username(username)
                .totalTransfers(records.size())
                .avgTransfersPerDay(Math.round(avgPerDay * 10) / 10.0)
                .avgFileSizeBytes((long) avgSize)
                .maxFileSizeBytes((long) maxSize)
                .activeHoursUtc(activeHours)
                .activeDays(activeDays)
                .commonFileExtensions(extensions)
                .errorRate(Math.round(errorRate * 1000) / 1000.0)
                .lastTransfer(lastTransfer)
                .predictedNextDelivery(predictedNext)
                .healthScore(health)
                .profileBuiltAt(Instant.now())
                .build();
    }

    private Instant predictNextDelivery(List<FileTransferRecord> records, List<Integer> activeHours) {
        if (records.size() < 5 || activeHours.isEmpty()) return null;

        // Simple: next occurrence of the most common active hour
        int mostActiveHour = activeHours.get(activeHours.size() / 2); // median
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime predicted = now.withHour(mostActiveHour).withMinute(0);
        if (predicted.isBefore(now)) predicted = predicted.plusDays(1);
        return predicted.toInstant();
    }

    public PartnerProfile getProfile(String username) {
        return profiles.get(username);
    }

    public List<PartnerProfile> getAllProfiles() {
        return new ArrayList<>(profiles.values());
    }

    /**
     * Check if a transfer deviates from the partner's learned behavior.
     */
    public List<String> checkDeviations(String username, String filename, Long fileSize, Instant timestamp) {
        PartnerProfile profile = profiles.get(username);
        if (profile == null) return List.of("No historical profile for " + username);

        List<String> deviations = new ArrayList<>();

        // Check hour
        int hour = timestamp.atZone(ZoneOffset.UTC).getHour();
        if (!profile.activeHoursUtc.contains(hour)) {
            deviations.add(String.format("Unusual hour: %02d:00 UTC (typical: %s)", hour, profile.activeHoursUtc));
        }

        // Check file size
        if (fileSize != null && profile.avgFileSizeBytes > 0) {
            double ratio = fileSize / (double) profile.avgFileSizeBytes;
            if (ratio > 5) deviations.add(String.format("File %.1fx larger than average (%.0f KB vs %.0f KB avg)",
                    ratio, fileSize / 1024.0, profile.avgFileSizeBytes / 1024.0));
            if (ratio < 0.1 && fileSize > 0) deviations.add("File suspiciously small compared to historical average");
        }

        // Check extension
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            String ext = dot >= 0 ? filename.substring(dot) : "(none)";
            if (!profile.commonFileExtensions.containsKey(ext)) {
                deviations.add("Unusual file extension: " + ext + " (typical: " + profile.commonFileExtensions.keySet() + ")");
            }
        }

        return deviations;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PartnerProfile {
        private String username;
        private int totalTransfers;
        private double avgTransfersPerDay;
        private long avgFileSizeBytes;
        private long maxFileSizeBytes;
        private List<Integer> activeHoursUtc;
        private List<String> activeDays;
        private Map<String, Long> commonFileExtensions;
        private double errorRate;
        private Instant lastTransfer;
        private Instant predictedNextDelivery;
        private int healthScore;
        private Instant profileBuiltAt;
    }
}
