package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.ActivityCopilotService;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * R107: Activity Copilot — admin-facing AI endpoints over the per-step
 * semantic detail introduced in R105b. The UI Activity Monitor (R108)
 * calls these to render:
 *
 * <ul>
 *   <li>a plain-English narrative of what this transfer did,</li>
 *   <li>a root-cause diagnosis if it failed or stuck,</li>
 *   <li>ranked next-action suggestions with ready-to-call API paths,</li>
 *   <li>a scoped chat answer ("why did this take so long?").</li>
 * </ul>
 *
 * <p>All endpoints are read-only; write actions still go through
 * {@code /api/flow-executions/*}. The {@code apiPath}/{@code httpMethod}
 * fields in suggestions are hints the UI can turn into one-click buttons.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/activity")
@RequiredArgsConstructor
@PreAuthorize(Roles.VIEWER)
public class ActivityCopilotController {

    private final ActivityCopilotService copilot;

    @GetMapping("/analyze/{trackId}")
    public ResponseEntity<ActivityCopilotService.AnalysisResult> analyze(@PathVariable String trackId) {
        return ResponseEntity.ok(copilot.analyze(trackId));
    }

    @GetMapping("/diagnose/{trackId}")
    public ResponseEntity<ActivityCopilotService.DiagnosisResult> diagnose(@PathVariable String trackId) {
        return ResponseEntity.ok(copilot.diagnose(trackId));
    }

    @GetMapping("/suggest/{trackId}")
    public ResponseEntity<List<ActivityCopilotService.SuggestedAction>> suggest(@PathVariable String trackId) {
        return ResponseEntity.ok(copilot.suggestActions(trackId));
    }

    /**
     * Free-form Q&A scoped to a specific transfer.
     * Body: {@code { "trackId": "TRZ-abc", "message": "why is this stuck?" }}
     */
    @PostMapping("/chat")
    public ResponseEntity<ActivityCopilotService.ChatResult> chat(@RequestBody Map<String, String> body) {
        String trackId = body.getOrDefault("trackId", "");
        String message = body.getOrDefault("message", "");
        if (trackId.isBlank()) {
            return ResponseEntity.badRequest().body(new ActivityCopilotService.ChatResult(
                    "trackId is required", null, java.time.Instant.now()));
        }
        return ResponseEntity.ok(copilot.chat(trackId, message));
    }
}
