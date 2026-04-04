package com.filetransfer.screening.controller;

import com.filetransfer.screening.entity.ScreeningResult;
import com.filetransfer.screening.loader.SanctionsListLoader;
import com.filetransfer.screening.repository.ScreeningResultRepository;
import com.filetransfer.screening.service.ScreeningEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/screening")
@RequiredArgsConstructor
public class ScreeningController {

    private final ScreeningEngine screeningEngine;
    private final ScreeningResultRepository resultRepository;
    private final SanctionsListLoader listLoader;

    /** Screen a file upload */
    @PostMapping("/scan")
    public ResponseEntity<ScreeningResult> scanFile(
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

    /** Screen inline text/CSV content */
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
        return Map.of(
                "status", "UP",
                "service", "screening-service",
                "sanctionsLists", listLoader.getListCounts(),
                "lastRefresh", listLoader.getLastRefresh() != null ? listLoader.getLastRefresh().toString() : "loading..."
        );
    }
}
