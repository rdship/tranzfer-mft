package com.filetransfer.shared.scheduler;

import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.entity.PartnerAgreement;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import com.filetransfer.shared.repository.PartnerAgreementRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor @Slf4j
public class SlaBreachDetector {

    private final PartnerAgreementRepository agreementRepository;
    private final FileTransferRecordRepository recordRepository;
    private final ConnectorDispatcher connectorDispatcher;

    private final List<SlaBreachEvent> activeBreaches = Collections.synchronizedList(new ArrayList<>());

    @Scheduled(fixedDelay = 300000) // every 5 min
    @SchedulerLock(name = "slaBreachDetector_checkSlas", lockAtLeastFor = "PT4M", lockAtMostFor = "PT14M")
    public void checkSlas() {
        activeBreaches.clear();
        List<PartnerAgreement> agreements = agreementRepository.findByActiveTrue();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int currentHour = now.getHour();
        String today = now.getDayOfWeek().name();

        for (PartnerAgreement sla : agreements) {
            // Only check on expected days
            if (sla.getExpectedDays() != null && !sla.getExpectedDays().contains(today)) continue;

            // Check if we're past the delivery window + grace period
            int deadlineHour = sla.getExpectedDeliveryEndHour();
            int graceMinutes = sla.getGracePeriodMinutes();
            ZonedDateTime deadline = now.withHour(deadlineHour).withMinute(graceMinutes).withSecond(0);

            if (now.isBefore(deadline)) continue; // Not past deadline yet

            // Count files received today in the window
            Instant todayStart = now.truncatedTo(ChronoUnit.DAYS).toInstant();
            String username = sla.getAccount() != null ? sla.getAccount().getUsername() : null;
            if (username == null) continue;

            long filesReceived = recordRepository.findAll().stream()
                    .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(todayStart))
                    .filter(r -> r.getFolderMapping() != null && r.getFolderMapping().getSourceAccount() != null
                            && username.equals(r.getFolderMapping().getSourceAccount().getUsername()))
                    .count();

            if (filesReceived < sla.getMinFilesPerWindow()) {
                SlaBreachEvent breach = SlaBreachEvent.builder()
                        .agreementName(sla.getName())
                        .accountUsername(username)
                        .expectedFiles(sla.getMinFilesPerWindow())
                        .receivedFiles((int) filesReceived)
                        .deadlineHour(deadlineHour)
                        .currentHour(currentHour)
                        .severity(filesReceived == 0 ? "CRITICAL" : "HIGH")
                        .detectedAt(Instant.now())
                        .build();
                activeBreaches.add(breach);

                // Update agreement
                sla.setTotalBreaches(sla.getTotalBreaches() + 1);
                sla.setLastBreachAt(Instant.now());
                agreementRepository.save(sla);

                // Dispatch to connectors
                connectorDispatcher.dispatch(ConnectorDispatcher.MftEvent.builder()
                        .eventType("SLA_BREACH").severity(breach.severity)
                        .account(username).trackId(null)
                        .summary(String.format("SLA breach: %s expected %d files by %02d:00 UTC, received %d",
                                sla.getName(), sla.getMinFilesPerWindow(), deadlineHour, filesReceived))
                        .details("Agreement: " + sla.getName() + "\nPartner: " + username)
                        .service("sla-monitor").build());

                log.warn("SLA BREACH: {} — {} expected {} files by {}:00, got {}",
                        sla.getName(), username, sla.getMinFilesPerWindow(), deadlineHour, filesReceived);
            }
        }
    }

    public List<SlaBreachEvent> getActiveBreaches() { return Collections.unmodifiableList(activeBreaches); }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SlaBreachEvent {
        private String agreementName;
        private String accountUsername;
        private int expectedFiles;
        private int receivedFiles;
        private int deadlineHour;
        private int currentHour;
        private String severity;
        private Instant detectedAt;
    }
}
