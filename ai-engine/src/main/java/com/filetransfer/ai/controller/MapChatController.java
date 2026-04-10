package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.edi.*;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Conversational Map Builder API.
 *
 * Three workflows:
 * 1. Build from samples: upload file pairs -> get map -> approve/feedback
 * 2. Chat edit: natural language commands to modify a map
 * 3. Feedback loop: approve or give comments on a generated map
 */
@RestController
@RequestMapping("/api/v1/edi/maps")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
@Slf4j
public class MapChatController {

    private final SampleMapBuilder sampleMapBuilder;
    private final MapConversationEngine conversationEngine;
    private final MapFeedbackProcessor feedbackProcessor;

    // ===================================================================
    // BUILD FROM SAMPLES
    // ===================================================================

    /**
     * Build a map from sample file pairs.
     * Partner uploads 2-5 input/output pairs, gets a map back.
     */
    @PostMapping("/build-from-samples")
    public ResponseEntity<Map<String, Object>> buildFromSamples(@RequestBody Map<String, Object> request) {
        String partnerId = (String) request.get("partnerId");
        String name = (String) request.getOrDefault("name", "Auto-generated map");

        if (partnerId == null || partnerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerId is required");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rawSamples = (List<Map<String, String>>) request.get("samples");
        if (rawSamples == null || rawSamples.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "samples is required (provide 2-5 input/output pairs)");
        }
        if (rawSamples.size() < 2 || rawSamples.size() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide between 2 and 5 sample pairs (got " + rawSamples.size() + ")");
        }

        List<SampleMapBuilder.SamplePair> samples = rawSamples.stream()
                .map(s -> new SampleMapBuilder.SamplePair(s.get("input"), s.get("output")))
                .toList();

        SampleMapBuilder.BuildResult result = sampleMapBuilder.buildFromSamples(samples, partnerId, name);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mapId", result.getMapId());
        response.put("name", name);
        response.put("confidence", result.getOverallConfidence());
        response.put("totalFields", result.getTotalFields());
        response.put("preview", result.getPreview());
        response.put("unmappedSourceFields", result.getUnmappedSourceFields());
        response.put("unmappedTargetFields", result.getUnmappedTargetFields());
        response.put("lowConfidenceFields", result.getLowConfidenceFields());
        response.put("status", "PENDING_APPROVAL");
        response.put("message", buildSummaryMessage(result));

        return ResponseEntity.ok(response);
    }

    // ===================================================================
    // CHAT
    // ===================================================================

    /**
     * Chat with the map editor -- natural language commands.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        String mapId = (String) request.get("mapId");
        String message = (String) request.get("message");

        if (mapId == null || mapId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mapId is required");
        }
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) request.getOrDefault("context", Map.of());

        MapConversationEngine.ChatRequest chatReq = MapConversationEngine.ChatRequest.builder()
                .mapId(mapId)
                .message(message)
                .currentMappings(context)
                .build();

        MapConversationEngine.ChatResponse chatResp = conversationEngine.processMessage(chatReq);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", chatResp.getReply());
        response.put("actions", chatResp.getActions());
        response.put("preview", chatResp.getPreview());
        response.put("confidence", chatResp.getConfidence());
        response.put("suggestedFollowUp", chatResp.getSuggestedFollowUp());

        return ResponseEntity.ok(response);
    }

    // ===================================================================
    // FEEDBACK
    // ===================================================================

    /**
     * Provide feedback on a generated/edited map.
     * Approve or give comments for the AI to improve.
     */
    @PostMapping("/{mapId}/feedback")
    public ResponseEntity<Map<String, Object>> feedback(
            @PathVariable String mapId,
            @RequestBody Map<String, Object> request) {

        boolean approved = Boolean.TRUE.equals(request.get("approved"));
        String comments = (String) request.getOrDefault("comments", "");

        if (approved) {
            log.info("Map {} approved — activating for production use", mapId);
            return ResponseEntity.ok(Map.of(
                    "status", "ACTIVATED",
                    "message", "Map approved and activated for production use."));
        }

        // Process feedback and rebuild
        @SuppressWarnings("unchecked")
        List<Map<String, String>> corrections =
                (List<Map<String, String>>) request.getOrDefault("corrections", List.of());

        MapFeedbackProcessor.FeedbackResult result =
                feedbackProcessor.processFeedback(mapId, comments, corrections);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", result.getReply());
        response.put("updatedMap", result.getUpdatedMap());
        response.put("preview", result.getPreview());
        response.put("remainingIssues", result.getRemainingIssues());
        response.put("status", "UPDATED");

        return ResponseEntity.ok(response);
    }

    // ===================================================================
    // HELPERS
    // ===================================================================

    private String buildSummaryMessage(SampleMapBuilder.BuildResult result) {
        int total = result.getTotalFields();
        int low = result.getLowConfidenceFields().size();
        int unmappedTarget = result.getUnmappedTargetFields().size();
        double conf = result.getOverallConfidence() * 100;

        if (conf >= 95 && low == 0) {
            return String.format(
                    "Map built with high confidence (%.0f%%). All %d fields mapped. Ready for approval.",
                    conf, total);
        } else if (low > 0) {
            return String.format(
                    "Map built with %.0f%% confidence. %d fields mapped, %d need review.",
                    conf, total, low);
        } else {
            return String.format(
                    "Map built with %.0f%% confidence. %d of %d target fields mapped.",
                    conf, total, total + unmappedTarget);
        }
    }
}
