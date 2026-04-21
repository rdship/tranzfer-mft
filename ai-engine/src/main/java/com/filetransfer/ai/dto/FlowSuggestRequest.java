package com.filetransfer.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/ai/nlp/suggest-flow}.
 *
 * <p>Two shapes are accepted, both via this one DTO:
 * <ol>
 *   <li><b>Legacy freetext</b> — {@code { "description": "..." }}. Used by
 *       {@code ui-service/src/api/ai.js} and any external caller that only
 *       has natural-language intent.</li>
 *   <li><b>Structured form context</b> — {@code sourceAccountId},
 *       {@code filenamePattern}, {@code direction}, {@code existingSteps}
 *       (an array). Sent by the Processing-Flows "AI Suggest" button
 *       ({@code ui-service/src/pages/Flows.jsx}) when the admin has
 *       already started filling the form and wants suggestions in
 *       context.</li>
 * </ol>
 *
 * <p>Missing fields are null. Jackson tolerates unknown properties so
 * future UI additions don't break backward-compat. The controller
 * decides which shape it received and synthesises a description for the
 * LLM accordingly.
 *
 * <p><b>R134Q:</b> introduced to close the tester-filed R134O UI bug —
 * the old {@code Map<String, String>} signature 400'd on the structured
 * payload because {@code existingSteps} is a JSON array, not a string.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowSuggestRequest(
        String description,
        String sourceAccountId,
        String filenamePattern,
        String direction,
        List<String> existingSteps
) {
}
