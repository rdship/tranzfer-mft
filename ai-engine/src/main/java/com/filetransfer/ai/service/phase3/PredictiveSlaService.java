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
import java.util.stream.Collectors;

/**
 * AI Phase 3: Predicts SLA breaches before they happen.
 *
 * For each partner, monitors:
 * - Whether they're trending late vs their usual schedule
 * - If delivery times are drifting (getting later each week)
 * - Expected delivery window vs current time
 *
 * Generates early warnings like:
 * "Partner ACME's delivery has been trending 15 min later each week.
 *  Expected SLA breach in 2 weeks."
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PredictiveSlaService {

    private final FileTransferRecordRepository recordRepository;
    private final PartnerProfileService profileService;

    private final List<SlaForecast> forecasts = Collections.synchronizedList(new ArrayList<>());

    @Scheduled(fixedDelay = 1800000) // every 30 min
    @SchedulerLock(name = "ai_predictiveSla_analyzeSlaRisk", lockAtLeastFor = "PT25M", lockAtMostFor = "PT55M")
    public void analyzeSlaRisk() {
        forecasts.clear();
        List<PartnerProfileService.PartnerProfile> profiles = profileService.getAllProfiles();

        for (PartnerProfileService.PartnerProfile profile : profiles) {
            if (profile.getTotalTransfers() < 10) continue;

            // Get last 30 days of records for this partner
            List<FileTransferRecord> records = recordRepository.findAll().stream()
                    .filter(r -> r.getUploadedAt() != null && r.getFolderMapping() != null
                            && r.getFolderMapping().getSourceAccount() != null
                            && profile.getUsername().equals(r.getFolderMapping().getSourceAccount().getUsername()))
                    .filter(r -> r.getUploadedAt().isAfter(Instant.now().minus(30, ChronoUnit.DAYS)))
                    .sorted(Comparator.comparing(FileTransferRecord::getUploadedAt))
                    .collect(Collectors.toList());

            if (records.size() < 5) continue;

            // Check for delivery time drift
            List<Double> deliveryHours = records.stream()
                    .map(r -> (double) r.getUploadedAt().atZone(ZoneOffset.UTC).getHour()
                            + r.getUploadedAt().atZone(ZoneOffset.UTC).getMinute() / 60.0)
                    .collect(Collectors.toList());

            // Linear regression on delivery time
            double[] x = new double[deliveryHours.size()];
            double[] y = deliveryHours.stream().mapToDouble(Double::doubleValue).toArray();
            for (int i = 0; i < x.length; i++) x[i] = i;

            double slope = linearSlope(x, y); // hours per transfer
            double avgHour = Arrays.stream(y).average().orElse(12);

            // Check if overdue
            boolean overdue = false;
            String overdueMsg = null;
            if (profile.getPredictedNextDelivery() != null
                    && Instant.now().isAfter(profile.getPredictedNextDelivery().plus(2, ChronoUnit.HOURS))) {
                overdue = true;
                long hoursLate = ChronoUnit.HOURS.between(profile.getPredictedNextDelivery(), Instant.now());
                overdueMsg = String.format("%s is %dh overdue (expected at %02d:00 UTC)",
                        profile.getUsername(), hoursLate,
                        profile.getPredictedNextDelivery().atZone(ZoneOffset.UTC).getHour());
            }

            // Drift analysis
            String driftAnalysis = null;
            int daysToSlaBreach = -1;
            if (Math.abs(slope) > 0.02) { // Meaningful drift
                double driftMinPerWeek = slope * 7 * 60; // minutes per week
                if (slope > 0) {
                    // Getting later
                    driftAnalysis = String.format("%s deliveries drifting %.0f min later per week",
                            profile.getUsername(), driftMinPerWeek);
                    // How many weeks until they drift outside their active window?
                    double maxHour = profile.getActiveHoursUtc().isEmpty() ? 23 :
                            Collections.max(profile.getActiveHoursUtc()) + 1;
                    double hoursUntilBreach = maxHour - avgHour;
                    if (hoursUntilBreach > 0 && slope > 0) {
                        double transfersUntilBreach = hoursUntilBreach / slope;
                        daysToSlaBreach = (int) (transfersUntilBreach / profile.getAvgTransfersPerDay());
                    }
                } else {
                    driftAnalysis = String.format("%s deliveries shifting %.0f min earlier per week",
                            profile.getUsername(), Math.abs(driftMinPerWeek));
                }
            }

            // Error rate trend
            String errorTrend = null;
            if (profile.getErrorRate() > 0.1) {
                errorTrend = String.format("%s error rate is %.1f%% — above 10%% threshold",
                        profile.getUsername(), profile.getErrorRate() * 100);
            }

            SlaForecast forecast = SlaForecast.builder()
                    .username(profile.getUsername())
                    .healthScore(profile.getHealthScore())
                    .overdue(overdue)
                    .overdueMessage(overdueMsg)
                    .deliveryDrift(driftAnalysis)
                    .daysToSlaBreach(daysToSlaBreach)
                    .errorRateTrend(errorTrend)
                    .lastDelivery(profile.getLastTransfer())
                    .predictedNext(profile.getPredictedNextDelivery())
                    .avgDeliveryHourUtc(avgHour)
                    .riskLevel(calculateRisk(overdue, daysToSlaBreach, profile.getErrorRate()))
                    .build();

            forecasts.add(forecast);
        }

        long atRisk = forecasts.stream().filter(f -> !"LOW".equals(f.riskLevel)).count();
        if (atRisk > 0) log.warn("SLA forecasts: {} partners at risk", atRisk);
    }

    private String calculateRisk(boolean overdue, int daysToSlaBreach, double errorRate) {
        if (overdue) return "CRITICAL";
        if (daysToSlaBreach > 0 && daysToSlaBreach < 7) return "HIGH";
        if (daysToSlaBreach > 0 && daysToSlaBreach < 14) return "MEDIUM";
        if (errorRate > 0.1) return "HIGH";
        return "LOW";
    }

    private double linearSlope(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) { sumX += x[i]; sumY += y[i]; sumXY += x[i]*y[i]; sumX2 += x[i]*x[i]; }
        double denom = n * sumX2 - sumX * sumX;
        return denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
    }

    public List<SlaForecast> getForecasts() {
        return Collections.unmodifiableList(forecasts);
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SlaForecast {
        private String username;
        private int healthScore;
        private boolean overdue;
        private String overdueMessage;
        private String deliveryDrift;
        private int daysToSlaBreach;
        private String errorRateTrend;
        private Instant lastDelivery;
        private Instant predictedNext;
        private double avgDeliveryHourUtc;
        private String riskLevel;
    }
}
