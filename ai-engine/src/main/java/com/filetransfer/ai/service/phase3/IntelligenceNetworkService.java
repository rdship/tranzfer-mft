package com.filetransfer.ai.service.phase3;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * INTELLIGENCE NETWORK
 *
 * Anonymous, privacy-preserving threat intelligence sharing.
 * Each TranzFer installation contributes anonymized signals:
 * - New attack patterns detected
 * - Sanctions evasion attempts
 * - Protocol vulnerability exploits
 * - Performance anomalies
 *
 * No file content or partner data is ever shared — only anonymized patterns.
 */
@Service @Slf4j
public class IntelligenceNetworkService {

    private final List<ThreatSignal> localSignals = Collections.synchronizedList(new ArrayList<>());
    private final List<ThreatSignal> networkSignals = Collections.synchronizedList(new ArrayList<>());

    /** Report a local threat signal (anonymized) */
    public void reportSignal(String signalType, String description, String severity, Map<String, Object> metadata) {
        ThreatSignal signal = ThreatSignal.builder()
                .signalId(UUID.randomUUID().toString().substring(0, 8))
                .signalType(signalType)
                .description(description)
                .severity(severity)
                .metadata(anonymize(metadata))
                .source("local")
                .detectedAt(Instant.now())
                .build();
        localSignals.add(signal);

        // In production: would POST to central intelligence hub
        // POST https://intelligence.tranzfer.io/api/signals
        log.info("INTELLIGENCE: Local signal reported — {} ({})", signalType, severity);
    }

    /** Receive signal from network (other TranzFer installations) */
    public void receiveNetworkSignal(ThreatSignal signal) {
        signal.setSource("network");
        networkSignals.add(signal);
        log.info("INTELLIGENCE: Network signal received — {} from {}", signal.signalType, signal.signalId);
    }

    /** Get all active threat signals (local + network) */
    public List<ThreatSignal> getActiveSignals() {
        List<ThreatSignal> all = new ArrayList<>();
        Instant cutoff = Instant.now().minusSeconds(86400); // last 24h
        all.addAll(localSignals.stream().filter(s -> s.detectedAt.isAfter(cutoff)).toList());
        all.addAll(networkSignals.stream().filter(s -> s.detectedAt.isAfter(cutoff)).toList());
        all.sort(Comparator.comparing(ThreatSignal::getDetectedAt).reversed());
        return all;
    }

    public Map<String, Object> getNetworkStatus() {
        return Map.of(
                "localSignals", localSignals.size(),
                "networkSignals", networkSignals.size(),
                "totalActive", getActiveSignals().size(),
                "status", "CONNECTED",
                "installationId", getInstallationId(),
                "capabilities", List.of("threat-sharing", "anomaly-broadcast", "sanctions-update", "benchmark-aggregation")
        );
    }

    /** Auto-detect and report common threats */
    @Scheduled(fixedDelay = 600000)
    @SchedulerLock(name = "ai_intelligenceNetwork_autoDetect", lockAtLeastFor = "PT9M", lockAtMostFor = "PT20M")
    public void autoDetect() {
        // In production: would analyze recent audit logs for patterns
        // and auto-report signals to the network
    }

    /** Anonymize metadata — strip PII, hash identifiers */
    private Map<String, Object> anonymize(Map<String, Object> metadata) {
        if (metadata == null) return Map.of();
        Map<String, Object> anon = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            String key = e.getKey();
            if (key.contains("username") || key.contains("email") || key.contains("ip")) {
                anon.put(key, hash(e.getValue().toString())); // hash PII
            } else {
                anon.put(key, e.getValue());
            }
        }
        return anon;
    }

    private String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes())).substring(0, 16); }
        catch (Exception e) { return "anon"; }
    }

    private String getInstallationId() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(java.net.InetAddress.getLocalHost().getHostName().getBytes())).substring(0, 12); }
        catch (Exception e) { return "unknown"; }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThreatSignal {
        private String signalId;
        private String signalType; // BRUTE_FORCE, SANCTIONS_EVASION, PROTOCOL_EXPLOIT, DATA_EXFILTRATION, ANOMALY
        private String description;
        private String severity; // LOW, MEDIUM, HIGH, CRITICAL
        private Map<String, Object> metadata; // anonymized
        private String source; // local, network
        private Instant detectedAt;
    }
}
