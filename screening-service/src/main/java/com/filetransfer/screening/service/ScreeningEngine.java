package com.filetransfer.screening.service;

import com.filetransfer.screening.entity.SanctionsEntry;
import com.filetransfer.screening.entity.ScreeningResult;
import com.filetransfer.screening.loader.SanctionsListLoader;
import com.filetransfer.screening.repository.SanctionsEntryRepository;
import com.filetransfer.screening.repository.ScreeningResultRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core screening engine. Scans file contents against loaded sanctions lists.
 *
 * Supports:
 * - CSV/TSV files: scans configurable columns (or all text fields)
 * - Plain text: scans every line
 * - Fuzzy name matching using Levenshtein distance / Jaro-Winkler similarity
 * - Configurable match threshold (default 0.82 = 82% match)
 *
 * Returns: CLEAR (no hits), HIT (confirmed match), POSSIBLE_HIT (fuzzy match above threshold)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScreeningEngine {

    private final SanctionsEntryRepository sanctionsRepository;
    private final ScreeningResultRepository resultRepository;

    @Value("${screening.match-threshold:0.82}")
    private double matchThreshold;

    @Value("${screening.default-action:BLOCK}")
    private String defaultAction;

    /**
     * Screen a file against all loaded sanctions lists.
     */
    public ScreeningResult screenFile(Path filePath, String trackId, String accountUsername,
                                       List<String> columnsToScreen) {
        long start = System.currentTimeMillis();
        String filename = filePath.getFileName().toString();
        List<ScreeningResult.HitDetail> hits = new ArrayList<>();
        int recordsScanned = 0;

        try {
            // Load all sanctions entries into memory for fast matching
            List<SanctionsEntry> allEntries = sanctionsRepository.findAll();
            if (allEntries.isEmpty()) {
                log.warn("No sanctions entries loaded — screening skipped for {}", filename);
                return saveResult(trackId, filename, accountUsername, "CLEAR", 0, 0,
                        List.of(), "PASSED", System.currentTimeMillis() - start);
            }

            // Parse file
            List<String> lines = Files.readAllLines(filePath);
            if (lines.isEmpty()) {
                return saveResult(trackId, filename, accountUsername, "CLEAR", 0, 0,
                        List.of(), "PASSED", System.currentTimeMillis() - start);
            }

            // Detect if CSV
            boolean isCsv = filename.endsWith(".csv") || filename.endsWith(".tsv")
                    || lines.get(0).contains(",") || lines.get(0).contains("\t");
            String delimiter = filename.endsWith(".tsv") || lines.get(0).contains("\t") ? "\t" : ",";

            String[] headers = isCsv ? parseLine(lines.get(0), delimiter) : null;
            int startLine = isCsv ? 1 : 0;

            // Determine which column indices to screen
            Set<Integer> screenCols = new HashSet<>();
            if (headers != null && columnsToScreen != null && !columnsToScreen.isEmpty()) {
                for (int i = 0; i < headers.length; i++) {
                    if (columnsToScreen.contains(headers[i].trim())) screenCols.add(i);
                }
            }

            // Screen each line
            for (int lineNum = startLine; lineNum < lines.size(); lineNum++) {
                String line = lines.get(lineNum).trim();
                if (line.isEmpty()) continue;
                recordsScanned++;

                List<String> valuesToCheck;
                if (isCsv) {
                    String[] fields = parseLine(line, delimiter);
                    if (screenCols.isEmpty()) {
                        // Screen all fields
                        valuesToCheck = Arrays.asList(fields);
                    } else {
                        valuesToCheck = new ArrayList<>();
                        for (int col : screenCols) {
                            if (col < fields.length) valuesToCheck.add(fields[col]);
                        }
                    }
                } else {
                    valuesToCheck = List.of(line);
                }

                // Check each value against sanctions
                for (String value : valuesToCheck) {
                    String normalized = SanctionsListLoader.normalize(value);
                    if (normalized.length() < 3) continue; // skip very short values

                    for (SanctionsEntry entry : allEntries) {
                        double score = jaroWinklerSimilarity(normalized, entry.getNameLower());

                        // Also check aliases
                        if (score < matchThreshold && entry.getAliases() != null) {
                            for (String alias : entry.getAliases().split(";")) {
                                double aliasScore = jaroWinklerSimilarity(normalized,
                                        SanctionsListLoader.normalize(alias));
                                score = Math.max(score, aliasScore);
                            }
                        }

                        if (score >= matchThreshold) {
                            hits.add(ScreeningResult.HitDetail.builder()
                                    .matchedName(entry.getName())
                                    .sanctionsListName(entry.getProgram())
                                    .sanctionsListSource(entry.getSource())
                                    .matchScore(Math.round(score * 1000) / 1000.0)
                                    .fileField(headers != null && valuesToCheck.indexOf(value) < headers.length
                                            ? headers[valuesToCheck.indexOf(value)] : "line")
                                    .fileValue(value.length() > 50 ? value.substring(0, 50) + "..." : value)
                                    .lineNumber(lineNum + 1)
                                    .build());
                        }
                    }
                }
            }

            long duration = System.currentTimeMillis() - start;
            String outcome = hits.isEmpty() ? "CLEAR" : (hits.stream().anyMatch(h -> h.getMatchScore() >= 0.95) ? "HIT" : "POSSIBLE_HIT");
            String action = "CLEAR".equals(outcome) ? "PASSED" : defaultAction.equals("BLOCK") ? "BLOCKED" : "FLAGGED";

            log.info("[{}] Screening complete: {} — {} ({} records, {} hits, {}ms)",
                    trackId, filename, outcome, recordsScanned, hits.size(), duration);

            return saveResult(trackId, filename, accountUsername, outcome, recordsScanned,
                    hits.size(), hits, action, duration);

        } catch (Exception e) {
            log.error("Screening error for {}: {}", filename, e.getMessage());
            return saveResult(trackId, filename, accountUsername, "ERROR", recordsScanned,
                    0, List.of(), "PASSED", System.currentTimeMillis() - start);
        }
    }

    private ScreeningResult saveResult(String trackId, String filename, String account,
                                        String outcome, int scanned, int hitsFound,
                                        List<ScreeningResult.HitDetail> hits, String action, long duration) {
        ScreeningResult result = ScreeningResult.builder()
                .trackId(trackId).filename(filename).accountUsername(account)
                .outcome(outcome).recordsScanned(scanned).hitsFound(hitsFound)
                .hits(hits).actionTaken(action).durationMs(duration)
                .build();
        return resultRepository.save(result);
    }

    /**
     * Jaro-Winkler similarity — industry standard for name matching in AML/sanctions.
     * Returns 0.0 (no match) to 1.0 (exact match).
     */
    public static double jaroWinklerSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0;
        if (s1.equals(s2)) return 1.0;

        int len1 = s1.length(), len2 = s2.length();
        int matchWindow = Math.max(len1, len2) / 2 - 1;
        if (matchWindow < 0) matchWindow = 0;

        boolean[] matched1 = new boolean[len1];
        boolean[] matched2 = new boolean[len2];
        int matches = 0, transpositions = 0;

        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchWindow);
            int end = Math.min(i + matchWindow + 1, len2);
            for (int j = start; j < end; j++) {
                if (matched2[j] || s1.charAt(i) != s2.charAt(j)) continue;
                matched1[i] = matched2[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) return 0.0;

        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!matched1[i]) continue;
            while (!matched2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        double jaro = (matches / (double) len1 + matches / (double) len2
                + (matches - transpositions / 2.0) / matches) / 3.0;

        // Winkler boost for common prefix (up to 4 chars)
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }

        return jaro + prefix * 0.1 * (1 - jaro);
    }

    private String[] parseLine(String line, String delimiter) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            else if (String.valueOf(c).equals(delimiter) && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else current.append(c);
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }
}
