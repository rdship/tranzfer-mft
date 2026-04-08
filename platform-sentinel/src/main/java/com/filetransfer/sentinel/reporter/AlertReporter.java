package com.filetransfer.sentinel.reporter;

import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.connector.ConnectorDispatcher.MftEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertReporter {

    private final ConnectorDispatcher connectorDispatcher;
    private final GitHubReporter gitHubReporter;

    public void report(SentinelFinding finding) {
        // Dispatch to Slack/PagerDuty/etc for HIGH and CRITICAL
        if ("HIGH".equals(finding.getSeverity()) || "CRITICAL".equals(finding.getSeverity())) {
            try {
                connectorDispatcher.dispatch(MftEvent.builder()
                        .eventType("SENTINEL_" + finding.getAnalyzer() + "_" + finding.getRuleName())
                        .severity(finding.getSeverity())
                        .summary(finding.getTitle())
                        .details(finding.getDescription())
                        .service("platform-sentinel")
                        .account(finding.getAffectedAccount())
                        .trackId(finding.getTrackId())
                        .build());
                log.info("AlertReporter: dispatched alert for '{}' ({})", finding.getRuleName(), finding.getSeverity());
            } catch (Exception e) {
                log.warn("AlertReporter: ConnectorDispatcher failed: {}", e.getMessage());
            }
        }

        // Create GitHub issue for all findings
        gitHubReporter.report(finding);
    }
}
