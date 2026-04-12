package com.filetransfer.ai.service.phase3;

import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Phase 3: Answer natural language questions about the platform with real data.
 *
 * Examples:
 *   "How many files did we transfer today?"
 *   "Which partner has the most failures?"
 *   "What's the average transfer time?"
 *   "Are there any stuck transfers?"
 *   "Show me partner ACME's activity"
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NaturalLanguageMonitoringService {

    private final FileTransferRecordRepository recordRepository;
    private final TransferAccountRepository accountRepository;
    private final AuditLogRepository auditLogRepository;
    private final PartnerProfileService profileService;
    private final PredictiveSlaService slaService;
    private final AutoRemediationService remediationService;
    private final com.filetransfer.ai.service.SystemStateService systemState;

    /**
     * Answer a natural language question about the platform.
     * No LLM needed — pattern-matches the question and queries real data.
     */
    public String answer(String question) {
        String q = question.toLowerCase().trim();

        // Specific transfer diagnosis — "why did TRZ-X7K9M2 fail?" or "diagnose TRZ123"
        java.util.regex.Matcher trackMatcher = java.util.regex.Pattern.compile(
                "\\b(TRZ[A-Z0-9]{6,10})\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(question);
        if (trackMatcher.find()) {
            return systemState.diagnoseTransfer(trackMatcher.group(1).toUpperCase());
        }

        // "what's failing?" / "show me recent failures"
        if ((q.contains("what") && q.contains("fail")) || q.contains("recent failure") || q.contains("what broke")) {
            return systemState.getRecentFailures();
        }

        // "system health" / "how's the platform" / "status"
        if (q.contains("health") || q.contains("status") || q.contains("how") && q.contains("platform")) {
            var health = systemState.getHealthSummary();
            StringBuilder sb = new StringBuilder("Platform Health:\n");
            health.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            return sb.toString();
        }

        List<FileTransferRecord> allRecords = recordRepository.findAll();

        // Transfer count questions
        if (q.contains("how many") && (q.contains("transfer") || q.contains("file"))) {
            if (q.contains("today")) {
                long count = allRecords.stream()
                        .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(todayStart()))
                        .count();
                return "Today: " + count + " file transfers.";
            }
            if (q.contains("hour") || q.contains("last hour")) {
                long count = allRecords.stream()
                        .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(Instant.now().minus(1, ChronoUnit.HOURS)))
                        .count();
                return "Last hour: " + count + " file transfers.";
            }
            return "Total: " + allRecords.size() + " file transfer records in the system.";
        }

        // Failure questions
        if (q.contains("fail") || q.contains("error") || q.contains("problem")) {
            long failed = allRecords.stream().filter(r -> r.getStatus() == FileTransferStatus.FAILED).count();
            long pending = allRecords.stream().filter(r -> r.getStatus() == FileTransferStatus.PENDING).count();

            // Who fails most?
            Map<String, Long> failsByAccount = allRecords.stream()
                    .filter(r -> r.getStatus() == FileTransferStatus.FAILED && r.getFolderMapping() != null
                            && r.getFolderMapping().getSourceAccount() != null)
                    .collect(Collectors.groupingBy(
                            r -> r.getFolderMapping().getSourceAccount().getUsername(), Collectors.counting()));
            String worstAccount = failsByAccount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> e.getKey() + " (" + e.getValue() + " failures)").orElse("none");

            return String.format("""
                    Failures: %d total, %d currently pending retry
                    Most failures: %s
                    
                    Recent remediation actions: %d
                    """, failed, pending, worstAccount, remediationService.getRecentActions().size());
        }

        // Stuck transfers
        if (q.contains("stuck") || q.contains("pending") || q.contains("stalled")) {
            long stuck = allRecords.stream()
                    .filter(r -> r.getStatus() == FileTransferStatus.PENDING
                            && r.getUploadedAt() != null
                            && r.getUploadedAt().isBefore(Instant.now().minus(30, ChronoUnit.MINUTES)))
                    .count();
            return stuck > 0
                    ? stuck + " transfers stuck in PENDING for >30 min. Auto-remediation will clear them."
                    : "No stuck transfers. All transfers are processing normally.";
        }

        // Partner questions
        if (q.contains("partner") || q.contains("account")) {
            // Extract partner name if mentioned
            List<PartnerProfileService.PartnerProfile> profiles = profileService.getAllProfiles();
            for (PartnerProfileService.PartnerProfile p : profiles) {
                if (q.contains(p.getUsername().toLowerCase())) {
                    return String.format("""
                            Partner: %s
                            Health Score: %d/100
                            Avg Transfers/Day: %.1f
                            Avg File Size: %.0f KB
                            Error Rate: %.1f%%
                            Active Hours (UTC): %s
                            Last Transfer: %s
                            Next Expected: %s
                            """, p.getUsername(), p.getHealthScore(), p.getAvgTransfersPerDay(),
                            p.getAvgFileSizeBytes() / 1024.0, p.getErrorRate() * 100,
                            p.getActiveHoursUtc(), p.getLastTransfer(), p.getPredictedNextDelivery());
                }
            }
            // General partner list
            StringBuilder sb = new StringBuilder("Partner Health:\n");
            profiles.sort(Comparator.comparingInt(PartnerProfileService.PartnerProfile::getHealthScore));
            for (PartnerProfileService.PartnerProfile p : profiles) {
                sb.append(String.format("  %s: %d/100 (%.1f/day, %.1f%% errors)\n",
                        p.getUsername(), p.getHealthScore(), p.getAvgTransfersPerDay(), p.getErrorRate() * 100));
            }
            return sb.toString();
        }

        // SLA questions
        if (q.contains("sla") || q.contains("late") || q.contains("overdue") || q.contains("breach")) {
            List<PredictiveSlaService.SlaForecast> forecasts = slaService.getForecasts();
            if (forecasts.isEmpty()) return "No SLA data available yet. Profiles are built after 30 days of data.";

            StringBuilder sb = new StringBuilder("SLA Forecast:\n");
            for (PredictiveSlaService.SlaForecast f : forecasts) {
                sb.append(String.format("  %s: %s", f.getUsername(), f.getRiskLevel()));
                if (f.isOverdue()) sb.append(" — OVERDUE: ").append(f.getOverdueMessage());
                if (f.getDeliveryDrift() != null) sb.append(" — ").append(f.getDeliveryDrift());
                if (f.getDaysToSlaBreach() > 0) sb.append(" — breach in ~").append(f.getDaysToSlaBreach()).append(" days");
                sb.append("\n");
            }
            return sb.toString();
        }

        // Average time
        if (q.contains("average") && q.contains("time")) {
            double avgMs = allRecords.stream()
                    .filter(r -> r.getUploadedAt() != null && r.getRoutedAt() != null)
                    .mapToLong(r -> ChronoUnit.MILLIS.between(r.getUploadedAt(), r.getRoutedAt()))
                    .average().orElse(0);
            return String.format("Average transfer time (upload to route): %.0f ms (%.1f seconds)", avgMs, avgMs / 1000);
        }

        // Remediation
        if (q.contains("remediat") || q.contains("auto-fix") || q.contains("self-heal")) {
            var actions = remediationService.getRecentActions();
            if (actions.isEmpty()) return "No auto-remediation actions taken recently.";
            StringBuilder sb = new StringBuilder("Recent auto-remediation actions:\n");
            for (var a : actions.subList(Math.max(0, actions.size() - 10), actions.size())) {
                sb.append(String.format("  [%s] %s: %s\n", a.getTrackId(), a.getActionType(), a.getSummary()));
            }
            return sb.toString();
        }

        return """
                I can answer questions like:
                - "How many files transferred today?"
                - "Which partner has the most failures?"
                - "Are there any stuck transfers?"
                - "Show me partner ACME's activity"
                - "Any SLA risks?"
                - "What's the average transfer time?"
                - "Show recent auto-remediation actions"
                
                Set CLAUDE_API_KEY for full natural language understanding.
                """;
    }

    private Instant todayStart() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS);
    }
}
