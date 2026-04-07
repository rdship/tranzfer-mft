package com.filetransfer.screening.controller;

import com.filetransfer.screening.entity.ScreeningResult;
import com.filetransfer.screening.loader.SanctionsListLoader;
import com.filetransfer.screening.repository.ScreeningResultRepository;
import com.filetransfer.screening.service.ScreeningEngine;
import com.filetransfer.screening.service.ScreeningPipeline;
import com.filetransfer.screening.client.ClamAvClient;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/screening")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class ScreeningController {

    private final ScreeningEngine screeningEngine;
    private final ScreeningPipeline screeningPipeline;
    private final ScreeningResultRepository resultRepository;
    private final SanctionsListLoader listLoader;
    private final ClamAvClient clamAvClient;

    /**
     * Full screening pipeline: AV scan -> DLP scan -> Sanctions screening.
     * This is the primary endpoint for file screening.
     */
    @PostMapping("/scan")
    public ResponseEntity<ScreeningPipeline.PipelineResult> scanFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) List<String> columns) throws Exception {
        Path tempFile = Files.createTempFile("screen_", "_" + file.getOriginalFilename());
        file.transferTo(tempFile.toFile());
        try {
            return ResponseEntity.ok(screeningPipeline.screenFile(tempFile,
                    trackId != null ? trackId : "MANUAL",
                    account, columns));
        } finally { Files.deleteIfExists(tempFile); }
    }

    /** Sanctions-only scan (legacy endpoint for backwards compatibility) */
    @PostMapping("/scan/sanctions")
    public ResponseEntity<ScreeningResult> scanSanctionsOnly(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) List<String> columns) throws Exception {
        Path tempFile = Files.createTempFile("screen_", "_" + file.getOriginalFilename());
        file.transferTo(tempFile.toFile());
        try {
            return ResponseEntity.ok(screeningEngine.screenFile(tempFile,
                    trackId != null ? trackId : "MANUAL",
                    account, columns));
        } finally { Files.deleteIfExists(tempFile); }
    }

    /** Screen inline text/CSV content (sanctions only) */
    @PostMapping("/scan/text")
    public ResponseEntity<ScreeningResult> scanText(@RequestBody Map<String, Object> body) throws Exception {
        String content = (String) body.get("content");
        String filename = (String) body.getOrDefault("filename", "inline.csv");
        String trackId = (String) body.getOrDefault("trackId", "MANUAL");
        String account = (String) body.get("account");
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) body.get("columns");

        Path tempFile = Files.createTempFile("screen_", "_" + filename);
        Files.writeString(tempFile, content);
        try {
            return ResponseEntity.ok(screeningEngine.screenFile(tempFile, trackId, account, columns));
        } finally { Files.deleteIfExists(tempFile); }
    }

    /** Get screening result by track ID */
    @GetMapping("/results/{trackId}")
    public ResponseEntity<ScreeningResult> getResult(@PathVariable String trackId) {
        return resultRepository.findByTrackId(trackId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Recent screening results */
    @GetMapping("/results")
    public List<ScreeningResult> recentResults() {
        return resultRepository.findTop50ByOrderByScreenedAtDesc();
    }

    /** Get all hits (blocked/flagged) */
    @GetMapping("/hits")
    public List<ScreeningResult> getHits() {
        List<ScreeningResult> hits = new ArrayList<>();
        hits.addAll(resultRepository.findByOutcomeOrderByScreenedAtDesc("HIT"));
        hits.addAll(resultRepository.findByOutcomeOrderByScreenedAtDesc("POSSIBLE_HIT"));
        return hits;
    }

    /** Refresh sanctions lists now */
    @PostMapping("/lists/refresh")
    public ResponseEntity<Map<String, Object>> refreshLists() {
        listLoader.refreshAllLists();
        return ResponseEntity.ok(Map.of(
                "status", "refreshed",
                "lists", listLoader.getListCounts(),
                "lastRefresh", listLoader.getLastRefresh().toString()
        ));
    }

    /** Sanctions list status */
    @GetMapping("/lists")
    public Map<String, Object> listStatus() {
        return Map.of(
                "lists", listLoader.getListCounts(),
                "lastRefresh", listLoader.getLastRefresh() != null ? listLoader.getLastRefresh().toString() : "never",
                "autoRefresh", "every 6 hours"
        );
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", "UP");
        h.put("service", "screening-service");
        h.put("pipeline", List.of("ANTIVIRUS", "DLP", "SANCTIONS"));
        h.put("clamav", clamAvClient.ping() ? "CONNECTED" : "UNREACHABLE (fail-closed)");
        h.put("sanctionsLists", listLoader.getListCounts());
        h.put("lastRefresh", listLoader.getLastRefresh() != null ? listLoader.getLastRefresh().toString() : "loading...");
        return h;
    }
}
