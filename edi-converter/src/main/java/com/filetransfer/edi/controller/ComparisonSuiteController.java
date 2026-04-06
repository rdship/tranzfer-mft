package com.filetransfer.edi.controller;

import com.filetransfer.edi.service.ComparisonSuiteService;
import com.filetransfer.edi.service.ComparisonSuiteService.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Compare Suite REST API — batch comparison of conversion outputs between two systems.
 *
 * Flow:
 *   1. POST /compare/prepare          → scan dirs, match files, return preview
 *   2. POST /compare/prepare/upload   → upload CSV with explicit file pairs
 *   3. POST /compare/execute/{id}     → user confirms, run all comparisons
 *   4. GET  /compare/reports/{id}     → retrieve report
 *   5. GET  /compare/reports/{id}/summary → human-readable summary text
 */
@RestController @RequestMapping("/api/v1/convert/compare") @RequiredArgsConstructor
public class ComparisonSuiteController {

    private final ComparisonSuiteService comparisonSuiteService;

    /**
     * Step 1: Prepare comparison from 4 directory paths.
     * Scans directories, matches files by name, returns preview for confirmation.
     */
    @PostMapping("/prepare")
    public ResponseEntity<?> prepareComparison(@RequestBody CompareRequest request) {
        // Validate required fields
        if (request.getMappingFilePath() == null || request.getMappingFilePath().isBlank()) {
            // Directory mode — need all 4 paths
            if (isBlank(request.getSourceInputPath()) || isBlank(request.getSourceOutputPath())
                    || isBlank(request.getTargetInputPath()) || isBlank(request.getTargetOutputPath())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Provide all 4 paths (sourceInputPath, sourceOutputPath, targetInputPath, targetOutputPath) or a mappingFilePath with a CSV mapping file"));
            }
        }

        ComparePreview preview = comparisonSuiteService.prepareComparison(request);

        if ("ERROR".equals(preview.getStatus())) {
            return ResponseEntity.badRequest().body(preview);
        }

        return ResponseEntity.ok(preview);
    }

    /**
     * Step 1 (alt): Prepare comparison from uploaded CSV mapping file.
     * CSV format: sourceInputFile,sourceOutputFile,targetInputFile,targetOutputFile[,mapLabel]
     */
    @PostMapping(value = "/prepare/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> prepareFromUpload(
            @RequestPart("mappingFile") MultipartFile mappingFile,
            @RequestParam(required = false) String reportOutputPath) {
        try {
            String csvContent = new String(mappingFile.getBytes(), StandardCharsets.UTF_8);
            ComparePreview preview = comparisonSuiteService.prepareFromCsvContent(csvContent, reportOutputPath);

            if ("ERROR".equals(preview.getStatus())) {
                return ResponseEntity.badRequest().body(preview);
            }
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to read mapping file: " + e.getMessage()));
        }
    }

    /**
     * Step 2: Execute the comparison (user confirms after reviewing preview).
     * Runs field-level diffs on all matched pairs, generates report.
     */
    @PostMapping("/execute/{sessionId}")
    public ResponseEntity<?> executeComparison(@PathVariable UUID sessionId) {
        try {
            CompareReport report = comparisonSuiteService.executeComparison(sessionId);
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Retrieve a previously generated comparison report.
     */
    @GetMapping("/reports/{sessionId}")
    public ResponseEntity<?> getReport(@PathVariable UUID sessionId) {
        CompareReport report = comparisonSuiteService.getReport(sessionId);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Report not found. Either the session has not been executed yet or has expired."));
        }
        return ResponseEntity.ok(report);
    }

    /**
     * Get a human-readable text summary of the comparison report.
     */
    @GetMapping("/reports/{sessionId}/summary")
    public ResponseEntity<?> getReportSummary(@PathVariable UUID sessionId) {
        CompareReport report = comparisonSuiteService.getReport(sessionId);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Report not found."));
        }
        String summary = comparisonSuiteService.buildHumanReadableSummary(report);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(summary);
    }

    /**
     * Get the preview (matched file pairs) for an existing session.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable UUID sessionId) {
        ComparePreview preview = comparisonSuiteService.getPreview(sessionId);
        if (preview == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found or expired."));
        }
        return ResponseEntity.ok(preview);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
