package com.filetransfer.ai.service.detection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Advanced network behavior analysis for MFT security.
 * Detects beaconing (C2 callbacks), DGA domains, DNS tunneling,
 * data exfiltration patterns, and port scanning.
 *
 * All algorithms implemented in pure Java — no external ML libraries.
 */
@Service
@Slf4j
public class NetworkBehaviorAnalyzer {

    // ── Beaconing Detection ───────────────────────────────────────────

    private final ConcurrentHashMap<String, List<Long>> connectionTimestamps = new ConcurrentHashMap<>();

    /**
     * Detect C2 beaconing patterns by analyzing connection periodicity.
     * Tracks inter-arrival times and uses coefficient of variation + Goertzel algorithm.
     */
    public BeaconingResult detectBeaconing(String srcIp, String dstIp, int dstPort) {
        String key = srcIp + "->" + dstIp + ":" + dstPort;
        long now = System.currentTimeMillis();

        List<Long> timestamps = connectionTimestamps.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (timestamps) {
            timestamps.add(now);

            // Prune timestamps older than 24 hours
            long cutoff = now - 86_400_000L;
            timestamps.removeIf(t -> t < cutoff);

            // Keep max 5000 entries
            while (timestamps.size() > 5000) {
                timestamps.remove(0);
            }

            if (timestamps.size() < 20) {
                return new BeaconingResult(false, 0, 0, 0,
                        "Insufficient samples (" + timestamps.size() + "/20) for beaconing analysis");
            }

            // Compute inter-arrival times in seconds
            double[] intervals = new double[timestamps.size() - 1];
            for (int i = 1; i < timestamps.size(); i++) {
                intervals[i - 1] = (timestamps.get(i) - timestamps.get(i - 1)) / 1000.0;
            }

            // Mean and standard deviation of intervals
            double mean = 0;
            for (double interval : intervals) mean += interval;
            mean /= intervals.length;

            double variance = 0;
            for (double interval : intervals) variance += (interval - mean) * (interval - mean);
            variance /= intervals.length;
            double stdDev = Math.sqrt(variance);

            // Coefficient of variation (low = periodic)
            double coeffOfVariation = mean > 0 ? stdDev / mean : Double.MAX_VALUE;

            // Jitter percentage
            double jitterPct = mean > 0 ? (stdDev / mean) * 100.0 : 100.0;

            // Use Goertzel algorithm to detect dominant frequency
            double dominantPeriod = goertzelDominantPeriod(intervals);

            // Beaconing detection: CoV < 0.3 suggests periodicity
            boolean detected = coeffOfVariation < 0.3 && timestamps.size() >= 20;
            double confidence = 0;
            if (detected) {
                confidence = Math.max(0, Math.min(1.0, 1.0 - coeffOfVariation));
                // Boost confidence with more samples
                confidence *= Math.min(1.0, timestamps.size() / 50.0);
            }

            String explanation;
            if (detected) {
                explanation = String.format(
                        "Beaconing detected: %s connections at ~%.1fs intervals (CoV=%.3f, jitter=%.1f%%). " +
                        "Dominant period: %.1fs. %d samples analyzed.",
                        key, mean, coeffOfVariation, jitterPct, dominantPeriod, timestamps.size());
            } else {
                explanation = String.format(
                        "No beaconing: CoV=%.3f (threshold <0.3), mean interval=%.1fs, %d samples.",
                        coeffOfVariation, mean, timestamps.size());
            }

            return new BeaconingResult(detected, mean, jitterPct, confidence, explanation);
        }
    }

    /**
     * Goertzel algorithm — efficiently detects the dominant frequency in a signal.
     * More efficient than full FFT when we only need a few frequency bins.
     * Returns the period (in seconds) of the strongest frequency component.
     */
    private double goertzelDominantPeriod(double[] signal) {
        if (signal.length < 4) return 0.0;

        int n = signal.length;
        double bestMagnitude = 0;
        double bestFrequency = 0;

        // Test frequencies from 1 cycle per (2*signal_length) up to Nyquist
        int numFreqs = Math.min(n / 2, 200);

        for (int k = 1; k <= numFreqs; k++) {
            double frequency = (double) k / n;
            double omega = 2.0 * Math.PI * frequency;
            double coeff = 2.0 * Math.cos(omega);

            double s0 = 0, s1 = 0, s2 = 0;
            for (double v : signal) {
                s0 = v + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
            }

            double magnitude = s1 * s1 + s2 * s2 - coeff * s1 * s2;
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude;
                bestFrequency = frequency;
            }
        }

        // Convert frequency to period (in the same time units as the signal intervals)
        if (bestFrequency > 0) {
            // frequency is in cycles per sample-interval
            // mean interval is captured in the signal values themselves
            double meanInterval = 0;
            for (double v : signal) meanInterval += v;
            meanInterval /= signal.length;
            return meanInterval / bestFrequency;
        }
        return 0.0;
    }

    // ── DGA Detection ─────────────────────────────────────────────────

    /**
     * Top 40 English bigram frequencies (approximate, normalized).
     * Used to score how "English-like" a domain name is.
     */
    private static final Map<String, Double> ENGLISH_BIGRAMS = Map.ofEntries(
            Map.entry("th", 0.0356), Map.entry("he", 0.0307), Map.entry("in", 0.0243),
            Map.entry("er", 0.0205), Map.entry("an", 0.0199), Map.entry("re", 0.0185),
            Map.entry("on", 0.0176), Map.entry("at", 0.0149), Map.entry("en", 0.0145),
            Map.entry("nd", 0.0135), Map.entry("ti", 0.0134), Map.entry("es", 0.0134),
            Map.entry("or", 0.0128), Map.entry("te", 0.0120), Map.entry("of", 0.0117),
            Map.entry("ed", 0.0117), Map.entry("is", 0.0113), Map.entry("it", 0.0112),
            Map.entry("al", 0.0109), Map.entry("ar", 0.0107), Map.entry("st", 0.0105),
            Map.entry("to", 0.0104), Map.entry("nt", 0.0104), Map.entry("ng", 0.0095),
            Map.entry("se", 0.0093), Map.entry("ha", 0.0093), Map.entry("as", 0.0087),
            Map.entry("ou", 0.0087), Map.entry("io", 0.0083), Map.entry("le", 0.0083),
            Map.entry("ve", 0.0083), Map.entry("co", 0.0079), Map.entry("me", 0.0079),
            Map.entry("de", 0.0076), Map.entry("hi", 0.0076), Map.entry("ri", 0.0073),
            Map.entry("ro", 0.0073), Map.entry("ic", 0.0070), Map.entry("ne", 0.0069),
            Map.entry("ea", 0.0069)
    );

    private static final Set<String> SUSPICIOUS_TLDS = Set.of(
            "tk", "ml", "ga", "cf", "gq", "xyz", "top", "club", "work", "buzz",
            "bid", "loan", "racing", "win", "download", "stream", "click", "link"
    );

    private static final Set<String> COMMON_WORDS = Set.of(
            "google", "amazon", "microsoft", "apple", "facebook", "cloud", "mail",
            "shop", "store", "bank", "pay", "login", "secure", "online", "web",
            "server", "host", "data", "file", "transfer", "support", "help",
            "service", "portal", "admin", "info", "news", "blog", "forum"
    );

    private static final String VOWELS = "aeiou";
    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxyz";

    /**
     * Detect Domain Generation Algorithm (DGA) domains.
     * Analyzes character entropy, n-gram frequency, consonant patterns, and more.
     */
    public DgaResult detectDga(String domain) {
        if (domain == null || domain.isBlank()) {
            return new DgaResult(false, 0, 0, 0, "Empty domain");
        }

        // Extract the second-level domain (strip TLD and subdomain prefixes for analysis)
        String[] parts = domain.toLowerCase().split("\\.");
        String tld = parts.length > 1 ? parts[parts.length - 1] : "";
        String sld = parts.length > 1 ? parts[parts.length - 2] : parts[0];

        if (sld.length() < 3) {
            return new DgaResult(false, 0.0, 0.0, 0.0, "Domain too short for DGA analysis");
        }

        // 1. Shannon entropy of characters
        double entropy = shannonEntropy(sld);
        double entropyScore = Math.min(1.0, Math.max(0, (entropy - 2.5) / 2.0)); // norm: 2.5-4.5 -> 0-1

        // 2. Consonant ratio
        long consonants = sld.chars().filter(c -> CONSONANTS.indexOf(c) >= 0).count();
        double consonantRatio = (double) consonants / sld.length();
        double consonantScore = consonantRatio > 0.7 ? 1.0 : consonantRatio > 0.6 ? 0.6 : 0.0;

        // 3. Digit ratio
        long digits = sld.chars().filter(Character::isDigit).count();
        double digitRatio = (double) digits / sld.length();
        double digitScore = digitRatio > 0.3 ? 1.0 : digitRatio > 0.15 ? 0.5 : 0.0;

        // 4. Domain length (DGA domains tend to be longer)
        double lengthScore = sld.length() > 20 ? 1.0 : sld.length() > 15 ? 0.7 :
                sld.length() > 10 ? 0.3 : 0.0;

        // 5. Bigram frequency analysis vs English
        double bigramScore = computeBigramScore(sld);

        // 6. Vowel/consonant alternation score
        double alternationScore = computeAlternationScore(sld);

        // 7. Unique character ratio
        long uniqueChars = sld.chars().distinct().count();
        double uniqueRatio = (double) uniqueChars / sld.length();
        // Very low unique ratio = repetitive (some DGA), very high = random
        double uniqueScore = (uniqueRatio > 0.9 && sld.length() > 8) ? 0.6 :
                (uniqueRatio < 0.3 && sld.length() > 8) ? 0.4 : 0.0;

        // 8. Contains common dictionary words?
        boolean containsWord = COMMON_WORDS.stream().anyMatch(sld::contains);
        double wordScore = containsWord ? 0.0 : 0.5;

        // 9. TLD reputation
        double tldScore = SUSPICIOUS_TLDS.contains(tld) ? 0.8 : 0.0;

        // Composite weighted score
        double compositeScore =
                entropyScore * 0.20 +
                consonantScore * 0.12 +
                digitScore * 0.10 +
                lengthScore * 0.08 +
                bigramScore * 0.20 +
                alternationScore * 0.10 +
                uniqueScore * 0.05 +
                wordScore * 0.08 +
                tldScore * 0.07;

        compositeScore = Math.max(0.0, Math.min(1.0, compositeScore));
        boolean detected = compositeScore > 0.7;

        StringBuilder explanation = new StringBuilder();
        if (detected) {
            explanation.append(String.format("Likely DGA domain '%s' (score: %.3f). ", domain, compositeScore));
            explanation.append("Indicators: ");
            List<String> indicators = new ArrayList<>();
            if (entropyScore > 0.5) indicators.add(String.format("high entropy (%.2f)", entropy));
            if (consonantScore > 0.5) indicators.add(String.format("high consonant ratio (%.0f%%)", consonantRatio * 100));
            if (digitScore > 0.3) indicators.add(String.format("high digit ratio (%.0f%%)", digitRatio * 100));
            if (bigramScore > 0.5) indicators.add("unusual bigram distribution");
            if (lengthScore > 0.5) indicators.add("unusual length (" + sld.length() + " chars)");
            if (tldScore > 0) indicators.add("suspicious TLD ." + tld);
            explanation.append(String.join(", ", indicators)).append(".");
        } else {
            explanation.append(String.format("Domain '%s' appears legitimate (score: %.3f).", domain, compositeScore));
        }

        return new DgaResult(detected, compositeScore, entropy, consonantRatio, explanation.toString());
    }

    private double shannonEntropy(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : s.toCharArray()) {
            freq.merge(c, 1, Integer::sum);
        }
        double entropy = 0.0;
        int len = s.length();
        for (int count : freq.values()) {
            double p = (double) count / len;
            if (p > 0) entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /**
     * Score how well the domain's bigrams match English language bigram frequencies.
     * Returns 0 (matches English well) to 1 (very different from English).
     */
    private double computeBigramScore(String s) {
        if (s.length() < 2) return 0.5;
        double matchingFreq = 0;
        int bigramCount = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            String bigram = s.substring(i, i + 2);
            if (bigram.chars().allMatch(Character::isLetter)) {
                Double freq = ENGLISH_BIGRAMS.get(bigram);
                if (freq != null) {
                    matchingFreq += freq;
                }
                bigramCount++;
            }
        }
        if (bigramCount == 0) return 0.5;
        double avgFreq = matchingFreq / bigramCount;
        // Low average frequency = not English-like = suspicious
        // Typical English text avg bigram freq is ~0.01
        return Math.min(1.0, Math.max(0, 1.0 - (avgFreq / 0.012)));
    }

    /**
     * Score consonant/vowel alternation. Natural language alternates; DGA often doesn't.
     * Returns 0 (natural alternation) to 1 (poor alternation — many consecutive consonants/vowels).
     */
    private double computeAlternationScore(String s) {
        if (s.length() < 3) return 0.0;
        int maxConsecutiveConsonants = 0;
        int currentConsecutive = 0;
        int transitions = 0;
        boolean lastIsVowel = VOWELS.indexOf(Character.toLowerCase(s.charAt(0))) >= 0;

        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            boolean isVowel = VOWELS.indexOf(c) >= 0;
            boolean isConsonant = CONSONANTS.indexOf(c) >= 0;

            if (isConsonant) {
                currentConsecutive++;
                maxConsecutiveConsonants = Math.max(maxConsecutiveConsonants, currentConsecutive);
            } else {
                currentConsecutive = 0;
            }

            if (i > 0 && (isVowel || isConsonant) && isVowel != lastIsVowel) {
                transitions++;
            }
            if (isVowel || isConsonant) lastIsVowel = isVowel;
        }

        // High consecutive consonants or low transition rate = suspicious
        double consScore = maxConsecutiveConsonants > 4 ? 1.0 : maxConsecutiveConsonants > 3 ? 0.5 : 0.0;
        double transitionRate = (double) transitions / (s.length() - 1);
        // English usually has transition rate 0.4-0.7
        double transScore = transitionRate < 0.25 ? 0.8 : transitionRate > 0.8 ? 0.4 : 0.0;

        return Math.max(consScore, transScore);
    }

    // ── DNS Tunneling Detection ───────────────────────────────────────

    private final ConcurrentHashMap<String, List<Long>> dnsQueryTimestamps = new ConcurrentHashMap<>();

    /**
     * Detect DNS tunneling — data exfiltration/C2 via DNS queries.
     */
    public DnsTunnelResult detectDnsTunneling(String domain, String recordType, int queryLength) {
        if (domain == null || domain.isBlank()) {
            return new DnsTunnelResult(false, 0, List.of());
        }

        List<String> indicators = new ArrayList<>();
        double score = 0;

        // Extract subdomain (everything before the last two labels)
        String[] labels = domain.split("\\.");
        String subdomain = "";
        if (labels.length > 2) {
            subdomain = String.join(".", Arrays.copyOfRange(labels, 0, labels.length - 2));
        } else if (labels.length > 1) {
            subdomain = labels[0];
        }

        // 1. Unusually long subdomain
        if (subdomain.length() > 50) {
            score += 0.30;
            indicators.add(String.format("Very long subdomain (%d chars)", subdomain.length()));
        } else if (subdomain.length() > 30) {
            score += 0.15;
            indicators.add(String.format("Long subdomain (%d chars)", subdomain.length()));
        }

        // 2. High entropy in subdomain
        double subdomainEntropy = shannonEntropy(subdomain.replace(".", ""));
        if (subdomainEntropy > 4.0) {
            score += 0.25;
            indicators.add(String.format("High subdomain entropy (%.2f)", subdomainEntropy));
        } else if (subdomainEntropy > 3.5) {
            score += 0.10;
            indicators.add(String.format("Elevated subdomain entropy (%.2f)", subdomainEntropy));
        }

        // 3. Suspicious record type with long response
        Set<String> tunnelingRecordTypes = Set.of("TXT", "NULL", "CNAME", "MX");
        if (tunnelingRecordTypes.contains(recordType != null ? recordType.toUpperCase() : "")) {
            if (queryLength > 200) {
                score += 0.20;
                indicators.add(String.format("Suspicious record type %s with large query (%d bytes)", recordType, queryLength));
            } else if (queryLength > 100) {
                score += 0.10;
                indicators.add(String.format("Suspicious record type %s with moderate query (%d bytes)", recordType, queryLength));
            }
        }

        // 4. High query volume to same base domain
        String baseDomain = labels.length >= 2
                ? labels[labels.length - 2] + "." + labels[labels.length - 1]
                : domain;
        List<Long> queryTimes = dnsQueryTimestamps.computeIfAbsent(baseDomain, k -> new ArrayList<>());
        long now = System.currentTimeMillis();
        synchronized (queryTimes) {
            queryTimes.add(now);
            // Prune older than 10 minutes
            long cutoff = now - 600_000L;
            queryTimes.removeIf(t -> t < cutoff);

            if (queryTimes.size() > 100) {
                score += 0.20;
                indicators.add(String.format("High query volume: %d queries in 10 min to %s", queryTimes.size(), baseDomain));
            } else if (queryTimes.size() > 50) {
                score += 0.10;
                indicators.add(String.format("Elevated query volume: %d queries in 10 min to %s", queryTimes.size(), baseDomain));
            }
        }

        // 5. Base64/hex-like patterns in subdomain
        if (subdomain.length() > 5) {
            String noDelims = subdomain.replace(".", "").replace("-", "");
            boolean looksBase64 = noDelims.matches("[A-Za-z0-9+/=]{10,}");
            boolean looksHex = noDelims.matches("[0-9a-fA-F]{10,}");
            if (looksBase64 || looksHex) {
                score += 0.15;
                indicators.add("Subdomain contains " + (looksHex ? "hex" : "base64") + "-like encoding");
            }
        }

        score = Math.max(0.0, Math.min(1.0, score));
        boolean detected = score > 0.5;

        return new DnsTunnelResult(detected, score, indicators);
    }

    // ── Data Exfiltration Detection ───────────────────────────────────

    private final ConcurrentHashMap<String, TransferProfile> transferProfiles = new ConcurrentHashMap<>();

    private static class TransferProfile {
        long totalBytesOut = 0;
        long totalBytesIn = 0;
        final List<Long> recentBytesOut = new ArrayList<>();
        final List<Long> recentTimestamps = new ArrayList<>();
        long baselineBytesOut = 0;
        long observationCount = 0;
        Instant firstSeen = Instant.now();
        boolean isKnownDestination = false;

        synchronized void record(long bytesOut, long bytesIn) {
            totalBytesOut += bytesOut;
            totalBytesIn += bytesIn;
            recentBytesOut.add(bytesOut);
            recentTimestamps.add(System.currentTimeMillis());
            observationCount++;

            // Update baseline (running average)
            if (observationCount <= 100) {
                baselineBytesOut = totalBytesOut / observationCount;
            } else {
                // EWMA
                baselineBytesOut = (long) (0.05 * bytesOut + 0.95 * baselineBytesOut);
            }

            // Keep last 500 entries
            while (recentBytesOut.size() > 500) {
                recentBytesOut.remove(0);
                recentTimestamps.remove(0);
            }

            if (observationCount > 10) {
                isKnownDestination = true;
            }
        }
    }

    /**
     * Detect data exfiltration patterns based on outbound volume, ratio, timing, and destination.
     */
    public ExfilResult detectExfiltration(String srcIp, String dstIp, long bytesOut, String protocol) {
        String key = srcIp + "->" + dstIp;
        TransferProfile profile = transferProfiles.computeIfAbsent(key, k -> new TransferProfile());

        double score = 0;
        List<String> reasons = new ArrayList<>();

        synchronized (profile) {
            profile.record(bytesOut, 0);

            // 1. Outbound >> inbound ratio
            if (profile.totalBytesIn > 0) {
                double ratio = (double) profile.totalBytesOut / profile.totalBytesIn;
                if (ratio > 10.0 && profile.observationCount > 5) {
                    score += 0.25;
                    reasons.add(String.format("High outbound ratio (%.1f:1)", ratio));
                }
            } else if (profile.totalBytesOut > 1_000_000 && profile.observationCount > 3) {
                score += 0.20;
                reasons.add("All outbound, no inbound traffic");
            }

            // 2. Volume exceeds baseline
            double baselineRatio = 0;
            if (profile.baselineBytesOut > 0 && profile.observationCount > 10) {
                baselineRatio = (double) bytesOut / profile.baselineBytesOut;
                if (baselineRatio > 3.0) {
                    score += 0.25;
                    reasons.add(String.format("Volume %.1fx above baseline", baselineRatio));
                }
            }

            // 3. New/unknown destination
            if (!profile.isKnownDestination) {
                score += 0.15;
                reasons.add("New destination (fewer than 10 prior connections)");
            }

            // 4. Off-hours transfer (outside 6am-10pm UTC)
            int hour = Instant.now().atZone(ZoneOffset.UTC).getHour();
            if (hour < 6 || hour > 22) {
                score += 0.15;
                reasons.add(String.format("Off-hours transfer (hour %d UTC)", hour));
            }

            // 5. Non-standard port for encrypted traffic
            if (protocol != null) {
                String proto = protocol.toUpperCase();
                if ((proto.equals("TLS") || proto.equals("SSL") || proto.equals("HTTPS"))
                        && !isStandardEncryptedPort(dstIp)) {
                    score += 0.15;
                    reasons.add("Encrypted traffic to non-standard port");
                }
            }

            // 6. Burst detection — large volume in short time
            if (profile.recentBytesOut.size() > 5) {
                long recentTotal = profile.recentBytesOut.stream()
                        .skip(Math.max(0, profile.recentBytesOut.size() - 10))
                        .mapToLong(Long::longValue)
                        .sum();
                if (recentTotal > 100_000_000) { // 100 MB in recent window
                    score += 0.10;
                    reasons.add(String.format("Burst: %.1f MB in recent transfers",
                            recentTotal / (1024.0 * 1024.0)));
                }
            }

            score = Math.max(0.0, Math.min(1.0, score));
            boolean detected = score > 0.5;

            String explanation;
            if (detected) {
                explanation = String.format("Potential data exfiltration %s: %s",
                        key, String.join("; ", reasons));
            } else {
                explanation = String.format("Transfer %s within normal parameters (score: %.3f)",
                        key, score);
            }

            return new ExfilResult(detected, score, bytesOut,
                    profile.baselineBytesOut > 0 ? (double) bytesOut / profile.baselineBytesOut : 0,
                    explanation);
        }
    }

    private boolean isStandardEncryptedPort(String dstInfo) {
        // Check if the destination implies standard ports (443, 8443, 990 for FTPS)
        // In practice, the port would be extracted; here we check naming convention
        if (dstInfo == null) return false;
        return dstInfo.contains(":443") || dstInfo.contains(":8443") || dstInfo.contains(":990");
    }

    // ── Port Scan Detection ───────────────────────────────────────────

    private final ConcurrentHashMap<String, PortScanTracker> portScanTrackers = new ConcurrentHashMap<>();

    private static class PortScanTracker {
        final Map<Integer, List<Long>> portTimestamps = new ConcurrentHashMap<>();
        final Map<String, List<Long>> hostTimestamps = new ConcurrentHashMap<>();

        synchronized void recordPort(int port, String targetHost, long timestamp) {
            portTimestamps.computeIfAbsent(port, k -> new ArrayList<>()).add(timestamp);
            if (targetHost != null) {
                hostTimestamps.computeIfAbsent(targetHost, k -> new ArrayList<>()).add(timestamp);
            }
            prune(timestamp);
        }

        synchronized void prune(long now) {
            long cutoff = now - 300_000L; // 5 minute window
            portTimestamps.values().forEach(list -> list.removeIf(t -> t < cutoff));
            portTimestamps.entrySet().removeIf(e -> e.getValue().isEmpty());
            hostTimestamps.values().forEach(list -> list.removeIf(t -> t < cutoff));
            hostTimestamps.entrySet().removeIf(e -> e.getValue().isEmpty());
        }

        synchronized int uniquePorts() {
            return portTimestamps.size();
        }

        synchronized int uniqueHosts() {
            return hostTimestamps.size();
        }
    }

    /**
     * Detect port scanning: vertical (many ports one host), horizontal (one port many hosts),
     * or strobe (specific ports across hosts).
     */
    public ScanResult detectPortScan(String srcIp, int targetPort, Instant timestamp) {
        return detectPortScan(srcIp, targetPort, null, timestamp);
    }

    public ScanResult detectPortScan(String srcIp, int targetPort, String targetHost, Instant timestamp) {
        PortScanTracker tracker = portScanTrackers.computeIfAbsent(srcIp, k -> new PortScanTracker());
        tracker.recordPort(targetPort, targetHost, timestamp.toEpochMilli());

        int uniquePorts = tracker.uniquePorts();
        int uniqueHosts = tracker.uniqueHosts();

        String scanType = "NONE";
        boolean detected = false;
        double confidence = 0;

        if (uniquePorts >= 10 && uniqueHosts <= 2) {
            // Vertical scan: many ports, few hosts
            scanType = "VERTICAL";
            detected = true;
            confidence = Math.min(1.0, uniquePorts / 50.0);
        } else if (uniquePorts <= 3 && uniqueHosts >= 5) {
            // Horizontal scan: few ports, many hosts
            scanType = "HORIZONTAL";
            detected = true;
            confidence = Math.min(1.0, uniqueHosts / 20.0);
        } else if (uniquePorts >= 3 && uniquePorts <= 10 && uniqueHosts >= 3) {
            // Strobe scan: specific set of ports across multiple hosts
            scanType = "STROBE";
            detected = true;
            confidence = Math.min(1.0, (uniquePorts * uniqueHosts) / 50.0);
        } else if (uniquePorts >= 5) {
            // Possible slow scan
            scanType = "SLOW";
            detected = true;
            confidence = Math.min(1.0, uniquePorts / 20.0);
        }

        return new ScanResult(detected, scanType, uniquePorts, uniqueHosts, confidence);
    }

    // ── Result Records ────────────────────────────────────────────────

    public record BeaconingResult(
            boolean detected,
            double periodSeconds,
            double jitterPct,
            double confidence,
            String explanation
    ) {}

    public record DgaResult(
            boolean detected,
            double score,
            double entropy,
            double consonantRatio,
            String explanation
    ) {}

    public record DnsTunnelResult(
            boolean detected,
            double score,
            List<String> indicators
    ) {}

    public record ExfilResult(
            boolean detected,
            double score,
            long bytesOut,
            double baselineRatio,
            String explanation
    ) {}

    public record ScanResult(
            boolean detected,
            String scanType,
            int uniquePorts,
            int uniqueHosts,
            double confidence
    ) {}

    // ── Combined Network Analysis ─────────────────────────────────────

    /**
     * Run all detection algorithms on a single network connection event.
     */
    public NetworkAnalysis analyzeConnection(String srcIp, String dstIp, int srcPort, int dstPort,
                                              String protocol, long bytesIn, long bytesOut,
                                              String dnsQuery, Instant timestamp) {
        // Beaconing
        BeaconingResult beaconing = detectBeaconing(srcIp, dstIp, dstPort);

        // DGA (if DNS query present)
        DgaResult dga = (dnsQuery != null && !dnsQuery.isBlank())
                ? detectDga(dnsQuery)
                : new DgaResult(false, 0, 0, 0, "No DNS query");

        // DNS Tunneling
        DnsTunnelResult dnsTunnel = (dnsQuery != null && !dnsQuery.isBlank())
                ? detectDnsTunneling(dnsQuery, "A", dnsQuery.length())
                : new DnsTunnelResult(false, 0, List.of());

        // Exfiltration
        ExfilResult exfil = detectExfiltration(srcIp, dstIp, bytesOut, protocol);

        // Port scan
        ScanResult scan = detectPortScan(srcIp, dstPort, dstIp, timestamp);

        // Calculate overall risk (0-100)
        double risk = 0;
        if (beaconing.detected()) risk += 30 * beaconing.confidence();
        if (dga.detected()) risk += 25 * dga.score();
        if (dnsTunnel.detected()) risk += 20 * dnsTunnel.score();
        if (exfil.detected()) risk += 25 * exfil.score();
        if (scan.detected()) risk += 15 * scan.confidence();
        risk = Math.min(100, risk);

        // Map to MITRE ATT&CK techniques
        List<String> mitreTechniques = mapToMitreTechniques(beaconing, dga, dnsTunnel, exfil, scan);

        log.debug("Network analysis for {}->{}:{} risk={}, techniques={}",
                srcIp, dstIp, dstPort, (int) risk, mitreTechniques);

        return new NetworkAnalysis(risk, beaconing, dga, dnsTunnel, exfil, scan, mitreTechniques);
    }

    public record NetworkAnalysis(
            double overallRisk,
            BeaconingResult beaconing,
            DgaResult dga,
            DnsTunnelResult dnsTunnel,
            ExfilResult exfiltration,
            ScanResult portScan,
            List<String> mitreTechniques
    ) {}

    private List<String> mapToMitreTechniques(BeaconingResult beaconing, DgaResult dga,
                                                DnsTunnelResult dnsTunnel, ExfilResult exfil,
                                                ScanResult scan) {
        List<String> techniques = new ArrayList<>();
        if (beaconing.detected()) {
            techniques.add("T1071.001 - Application Layer Protocol: Web Protocols");
            techniques.add("T1573 - Encrypted Channel");
        }
        if (dga.detected()) {
            techniques.add("T1568.002 - Dynamic Resolution: Domain Generation Algorithms");
        }
        if (dnsTunnel.detected()) {
            techniques.add("T1071.004 - Application Layer Protocol: DNS");
            techniques.add("T1048.003 - Exfiltration Over Alternative Protocol: DNS");
        }
        if (exfil.detected()) {
            techniques.add("T1041 - Exfiltration Over C2 Channel");
            techniques.add("T1030 - Data Transfer Size Limits");
        }
        if (scan.detected()) {
            switch (scan.scanType()) {
                case "VERTICAL" -> techniques.add("T1046 - Network Service Scanning");
                case "HORIZONTAL" -> {
                    techniques.add("T1046 - Network Service Scanning");
                    techniques.add("T1018 - Remote System Discovery");
                }
                case "STROBE" -> techniques.add("T1046 - Network Service Scanning");
                case "SLOW" -> techniques.add("T1046 - Network Service Scanning");
                default -> { /* no mapping */ }
            }
        }
        return techniques;
    }

    // ── Stats ─────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trackedBeaconingPairs", connectionTimestamps.size());
        stats.put("trackedTransferProfiles", transferProfiles.size());
        stats.put("trackedDnsBaseDomains", dnsQueryTimestamps.size());
        stats.put("trackedPortScanSources", portScanTrackers.size());
        return stats;
    }
}
