package com.filetransfer.ai.service.edi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.entity.edi.MappingCorrectionSession;
import com.filetransfer.ai.entity.edi.MappingCorrectionSession.Status;
import com.filetransfer.ai.repository.edi.MappingCorrectionSessionRepository;
import com.filetransfer.ai.service.edi.MappingCorrectionInterpreter.*;
import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the natural language mapping correction lifecycle.
 *
 * Flow: startSession → submitCorrection (repeatable) → approve/reject
 *
 * On each correction:
 *   1. MappingCorrectionInterpreter parses the natural language instruction
 *   2. Changes are applied to the working field mappings
 *   3. EDI Converter tests the updated mappings against the sample input
 *   4. Before/after comparison is computed
 *
 * On approval:
 *   1. Field mappings are stored as a new versioned ConversionMap
 *   2. Partner's FileFlow is updated with CONVERT_EDI step
 *   3. EDI Converter cache is invalidated
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MappingCorrectionService {

    private final MappingCorrectionSessionRepository sessionRepo;
    private final MappingCorrectionInterpreter interpreter;
    private final TrainedMapStore mapStore;
    private final EdiMapTrainingEngine trainingEngine;
    private final FileFlowRepository flowRepository;
    private final ServiceClientProperties serviceProps;
    private final PlatformConfig platformConfig;
    private final ObjectMapper objectMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired(required = false)
    @Nullable
    private SpiffeWorkloadClient spiffeWorkloadClient;

    // ===================================================================
    // Start Session
    // ===================================================================

    @Transactional
    public SessionResponse startSession(StartRequest request) {
        String mapKey = TrainedMapStore.buildMapKey(
                request.getSourceFormat().toUpperCase(),
                request.getSourceType(),
                request.getTargetFormat().toUpperCase(),
                request.getTargetType(),
                request.getPartnerId());

        // Expire any existing active session for this partner + mapKey
        sessionRepo.findByPartnerIdAndMapKeyAndStatus(request.getPartnerId(), mapKey, Status.ACTIVE)
                .ifPresent(existing -> {
                    existing.setStatus(Status.EXPIRED);
                    sessionRepo.save(existing);
                    log.info("Expired previous active session {} for partner {} mapKey {}",
                            existing.getId(), request.getPartnerId(), mapKey);
                });

        // Clone existing map's field mappings as starting point
        String initialMappingsJson = "[]";
        UUID baseMapId = null;
        int baseMapVersion = 0;

        Optional<ConversionMap> existingMap = mapStore.getActiveMap(
                request.getSourceFormat().toUpperCase(),
                request.getSourceType(),
                request.getTargetFormat().toUpperCase(),
                request.getPartnerId());

        if (existingMap.isPresent()) {
            ConversionMap base = existingMap.get();
            initialMappingsJson = base.getFieldMappingsJson();
            baseMapId = base.getId();
            baseMapVersion = base.getVersion();
            log.info("Starting correction session from existing map '{}' v{}", base.getMapKey(), base.getVersion());
        }

        // Create session
        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .partnerId(request.getPartnerId())
                .mapKey(mapKey)
                .baseMapId(baseMapId)
                .baseMapVersion(baseMapVersion)
                .sourceFormat(request.getSourceFormat().toUpperCase())
                .sourceType(request.getSourceType())
                .targetFormat(request.getTargetFormat().toUpperCase())
                .targetType(request.getTargetType())
                .currentFieldMappingsJson(initialMappingsJson)
                .sampleInputContent(request.getSampleInputContent())
                .sampleExpectedOutput(request.getSampleExpectedOutput())
                .flowId(request.getFlowId())
                .build();
        session = sessionRepo.save(session);

        // Run initial test conversion
        String testOutput = runTestConversion(session);
        session.setLatestTestOutput(testOutput);
        session = sessionRepo.save(session);

        List<FieldMappingDto> mappings = deserializeMappings(initialMappingsJson);

        return SessionResponse.builder()
                .sessionId(session.getId())
                .status(session.getStatus().name())
                .mapKey(mapKey)
                .correctionCount(0)
                .currentMappings(mappings)
                .currentTestOutput(testOutput)
                .message(existingMap.isPresent()
                        ? "Session started from existing map (v" + baseMapVersion + "). Describe what you'd like to change."
                        : "Session started with empty mappings. Describe the field mappings you need.")
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .build();
    }

    // ===================================================================
    // Submit Correction
    // ===================================================================

    @Transactional
    public CorrectionResult submitCorrection(UUID sessionId, String partnerId, String instruction) {
        MappingCorrectionSession session = loadSession(sessionId, partnerId);
        verifyActive(session);

        String previousOutput = session.getLatestTestOutput();
        List<FieldMappingDto> currentMappings = deserializeMappings(session.getCurrentFieldMappingsJson());

        // Interpret the correction
        CorrectionInterpretation interpretation = interpreter.interpretCorrection(
                instruction, currentMappings, session.getSourceFormat(),
                session.getSourceType(), session.getSampleInputContent());

        if (!interpretation.isUnderstood() || interpretation.isClarificationNeeded()) {
            return CorrectionResult.builder()
                    .sessionId(sessionId)
                    .applied(false)
                    .clarificationNeeded(true)
                    .clarificationQuestion(interpretation.getClarificationQuestion())
                    .summary(interpretation.getSummary())
                    .newTestOutput(previousOutput)
                    .build();
        }

        // Apply changes to the field mappings
        List<MappingChange> appliedChanges = new ArrayList<>();
        for (MappingChange change : interpretation.getChanges()) {
            boolean applied = applyChange(currentMappings, change);
            if (applied) appliedChanges.add(change);
        }

        if (appliedChanges.isEmpty()) {
            return CorrectionResult.builder()
                    .sessionId(sessionId)
                    .applied(false)
                    .summary("No changes could be applied. " + interpretation.getSummary())
                    .newTestOutput(previousOutput)
                    .build();
        }

        // Serialize updated mappings
        String updatedMappingsJson = serializeMappings(currentMappings);
        session.setCurrentFieldMappingsJson(updatedMappingsJson);

        // Record correction in history
        appendCorrectionHistory(session, instruction, appliedChanges, interpretation.getSummary());
        session.setCorrectionCount(session.getCorrectionCount() + 1);

        // Run test with updated mappings
        String newTestOutput = runTestConversion(session);
        session.setLatestTestOutput(newTestOutput);

        // Compute before/after comparison
        String comparison = computeComparison(previousOutput, newTestOutput);
        session.setLatestTestComparison(comparison);

        session = sessionRepo.save(session);

        return CorrectionResult.builder()
                .sessionId(sessionId)
                .applied(true)
                .summary(interpretation.getSummary())
                .changes(appliedChanges)
                .newTestOutput(newTestOutput)
                .previousTestOutput(previousOutput)
                .comparison(comparison)
                .nextStepHint("Does this look right? You can make more corrections or approve the mapping.")
                .build();
    }

    // ===================================================================
    // Run Test
    // ===================================================================

    public TestResult runTest(UUID sessionId, String partnerId) {
        MappingCorrectionSession session = loadSession(sessionId, partnerId);
        String output = runTestConversion(session);
        session.setLatestTestOutput(output);
        sessionRepo.save(session);

        return TestResult.builder()
                .sessionId(sessionId)
                .testOutput(output)
                .mappingCount(deserializeMappings(session.getCurrentFieldMappingsJson()).size())
                .build();
    }

    // ===================================================================
    // Approve
    // ===================================================================

    @Transactional
    public ApprovalResult approve(UUID sessionId, String partnerId) {
        MappingCorrectionSession session = loadSession(sessionId, partnerId);
        verifyActive(session);

        List<FieldMappingDto> mappingDtos = deserializeMappings(session.getCurrentFieldMappingsJson());

        if (mappingDtos.isEmpty()) {
            throw new IllegalStateException("Cannot approve an empty mapping. Add at least one field mapping first.");
        }

        // Convert DTOs to training engine FieldMappings
        List<EdiMapTrainingEngine.FieldMapping> fieldMappings = mappingDtos.stream()
                .map(dto -> EdiMapTrainingEngine.FieldMapping.builder()
                        .sourceField(dto.getSourceField())
                        .targetField(dto.getTargetField())
                        .transform(dto.getTransform() != null ? dto.getTransform() : "DIRECT")
                        .transformParam(dto.getTransformParam())
                        .confidence(Math.max(dto.getConfidence(), 95)) // partner-verified = high confidence
                        .strategy("PARTNER_CORRECTION")
                        .reasoning("Partner-corrected via natural language")
                        .build())
                .toList();

        // Generate mapping code
        String generatedCode = trainingEngine.generateMappingCode(fieldMappings);

        // Build TrainingResult compatible with TrainedMapStore
        EdiMapTrainingEngine.TrainingResult trainingResult = EdiMapTrainingEngine.TrainingResult.builder()
                .success(true)
                .mapKey(session.getMapKey())
                .fieldMappings(fieldMappings)
                .generatedCode(generatedCode)
                .confidence(95)
                .trainingSampleCount(session.getCorrectionCount())
                .testSampleCount(0)
                .strategiesUsed(List.of("PARTNER_CORRECTION"))
                .unmappedSourceFields(List.of())
                .unmappedTargetFields(List.of())
                .trainingReport("Partner-corrected mapping via " + session.getCorrectionCount()
                        + " natural language corrections")
                .durationMs(0)
                .build();

        // Store as new versioned ConversionMap
        ConversionMap newMap = mapStore.storeTrainingResult(trainingResult,
                "partner-correction:" + partnerId);

        // Update FileFlow if specified
        boolean flowUpdated = false;
        UUID flowId = session.getFlowId();
        if (flowId != null) {
            flowUpdated = updateFileFlow(flowId, session);
        }

        // Invalidate EDI Converter cache
        invalidateConverterCache();

        // Mark session as approved
        session.setStatus(Status.APPROVED);
        sessionRepo.save(session);

        log.info("Partner {} approved mapping correction: map '{}' v{}, flow updated: {}",
                partnerId, newMap.getMapKey(), newMap.getVersion(), flowUpdated);

        return ApprovalResult.builder()
                .newMapId(newMap.getId())
                .mapKey(newMap.getMapKey())
                .newMapVersion(newMap.getVersion())
                .confidence(newMap.getConfidence())
                .flowId(flowId)
                .flowUpdated(flowUpdated)
                .message("Mapping approved and saved as v" + newMap.getVersion()
                        + (flowUpdated ? ". File flow updated with CONVERT_EDI step." : "."))
                .build();
    }

    // ===================================================================
    // Reject
    // ===================================================================

    @Transactional
    public void reject(UUID sessionId, String partnerId) {
        MappingCorrectionSession session = loadSession(sessionId, partnerId);
        session.setStatus(Status.REJECTED);
        sessionRepo.save(session);
        log.info("Partner {} rejected correction session {}", partnerId, sessionId);
    }

    // ===================================================================
    // Read Operations
    // ===================================================================

    public SessionResponse getSession(UUID sessionId, String partnerId) {
        MappingCorrectionSession session = loadSession(sessionId, partnerId);
        return toSessionResponse(session);
    }

    public List<SessionSummary> listSessions(String partnerId) {
        return sessionRepo.findByPartnerIdOrderByCreatedAtDesc(partnerId).stream()
                .map(s -> SessionSummary.builder()
                        .sessionId(s.getId())
                        .mapKey(s.getMapKey())
                        .status(s.getStatus().name())
                        .correctionCount(s.getCorrectionCount())
                        .createdAt(s.getCreatedAt())
                        .build())
                .toList();
    }

    public String getCorrectionHistory(UUID sessionId, String partnerId) {
        MappingCorrectionSession session = loadSession(sessionId, partnerId);
        return session.getCorrectionHistory();
    }

    // ===================================================================
    // Session Expiry
    // ===================================================================

    @Scheduled(fixedDelay = 3600000) // every hour
    @Transactional
    public void expireStaleSessions() {
        List<MappingCorrectionSession> stale = sessionRepo
                .findByStatusAndExpiresAtBefore(Status.ACTIVE, Instant.now());
        for (MappingCorrectionSession session : stale) {
            session.setStatus(Status.EXPIRED);
            sessionRepo.save(session);
        }
        if (!stale.isEmpty()) {
            log.info("Expired {} stale correction sessions", stale.size());
        }
    }

    // ===================================================================
    // Private Helpers
    // ===================================================================

    private MappingCorrectionSession loadSession(UUID sessionId, String partnerId) {
        return sessionRepo.findByIdAndPartnerId(sessionId, partnerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Correction session not found: " + sessionId));
    }

    private void verifyActive(MappingCorrectionSession session) {
        if (session.getStatus() != Status.ACTIVE) {
            throw new IllegalStateException(
                    "Session is " + session.getStatus() + ", not ACTIVE. Cannot modify.");
        }
    }

    private boolean applyChange(List<FieldMappingDto> mappings, MappingChange change) {
        return switch (change.getAction().toUpperCase()) {
            case "MODIFY" -> {
                Optional<FieldMappingDto> existing = mappings.stream()
                        .filter(m -> m.getTargetField().equalsIgnoreCase(change.getTargetField())
                                || (change.getOldSourceField() != null
                                    && m.getSourceField().equalsIgnoreCase(change.getOldSourceField())))
                        .findFirst();
                if (existing.isPresent()) {
                    FieldMappingDto m = existing.get();
                    if (change.getNewSourceField() != null) m.setSourceField(change.getNewSourceField());
                    if (change.getNewTransform() != null) m.setTransform(change.getNewTransform());
                    if (change.getNewTransformParam() != null) m.setTransformParam(change.getNewTransformParam());
                    m.setConfidence(100);
                    yield true;
                }
                yield false;
            }
            case "ADD" -> {
                mappings.add(FieldMappingDto.builder()
                        .sourceField(change.getNewSourceField())
                        .targetField(change.getTargetField())
                        .transform(change.getNewTransform() != null ? change.getNewTransform() : "DIRECT")
                        .transformParam(change.getNewTransformParam())
                        .confidence(100)
                        .build());
                yield true;
            }
            case "REMOVE" -> {
                boolean removed = mappings.removeIf(m ->
                        m.getTargetField().equalsIgnoreCase(change.getTargetField()));
                yield removed;
            }
            case "CHANGE_TRANSFORM" -> {
                Optional<FieldMappingDto> existing = mappings.stream()
                        .filter(m -> m.getTargetField().equalsIgnoreCase(change.getTargetField()))
                        .findFirst();
                if (existing.isPresent()) {
                    FieldMappingDto m = existing.get();
                    if (change.getNewTransform() != null) m.setTransform(change.getNewTransform());
                    if (change.getNewTransformParam() != null) m.setTransformParam(change.getNewTransformParam());
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    private String runTestConversion(MappingCorrectionSession session) {
        try {
            String converterUrl = serviceProps.getEdiConverter().getUrl();
            List<FieldMappingDto> mappings = deserializeMappings(session.getCurrentFieldMappingsJson());

            if (mappings.isEmpty()) {
                return "{}  // No field mappings defined yet";
            }

            // Build request for the test-mappings endpoint
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sourceContent", session.getSampleInputContent());
            body.put("targetFormat", session.getTargetFormat());
            body.put("fieldMappings", mappings);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            addInternalAuth(headers, "edi-converter");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    converterUrl + "/api/v1/convert/test-mappings",
                    HttpMethod.POST, entity,
                    (Class<Map<String, Object>>) (Class<?>) Map.class);

            if (response.getBody() != null && response.getBody().containsKey("output")) {
                return (String) response.getBody().get("output");
            }
            return "{}  // No output from converter";
        } catch (Exception e) {
            log.warn("Test conversion failed: {}", e.getMessage());
            return "// Test conversion error: " + e.getMessage();
        }
    }

    private boolean updateFileFlow(UUID flowId, MappingCorrectionSession session) {
        try {
            Optional<FileFlow> flowOpt = flowRepository.findById(flowId);
            if (flowOpt.isEmpty()) {
                log.warn("FileFlow {} not found, skipping flow update", flowId);
                return false;
            }

            FileFlow flow = flowOpt.get();
            List<FileFlow.FlowStep> steps = flow.getSteps() != null
                    ? new ArrayList<>(flow.getSteps()) : new ArrayList<>();

            // Check if CONVERT_EDI step already exists
            Optional<FileFlow.FlowStep> existingStep = steps.stream()
                    .filter(s -> "CONVERT_EDI".equalsIgnoreCase(s.getType()))
                    .findFirst();

            Map<String, String> convertConfig = new LinkedHashMap<>();
            convertConfig.put("mapKey", session.getMapKey());
            convertConfig.put("partnerId", session.getPartnerId());
            convertConfig.put("targetFormat", session.getTargetFormat());

            if (existingStep.isPresent()) {
                existingStep.get().setConfig(convertConfig);
            } else {
                // Insert CONVERT_EDI after decrypt/decompress steps, before delivery
                int insertAt = 0;
                for (int i = 0; i < steps.size(); i++) {
                    String type = steps.get(i).getType().toUpperCase();
                    if (type.startsWith("DECRYPT") || type.startsWith("DECOMPRESS")) {
                        insertAt = i + 1;
                    }
                }
                FileFlow.FlowStep newStep = new FileFlow.FlowStep();
                newStep.setType("CONVERT_EDI");
                newStep.setConfig(convertConfig);
                newStep.setOrder(insertAt);
                steps.add(insertAt, newStep);

                // Reorder remaining steps
                for (int i = insertAt + 1; i < steps.size(); i++) {
                    steps.get(i).setOrder(i);
                }
            }

            flow.setSteps(steps);
            flowRepository.save(flow);
            log.info("Updated FileFlow {} with CONVERT_EDI step (mapKey={})", flowId, session.getMapKey());
            return true;
        } catch (Exception e) {
            log.error("Failed to update FileFlow {}: {}", flowId, e.getMessage());
            return false;
        }
    }

    private void addInternalAuth(HttpHeaders headers, String targetService) {
        if (spiffeWorkloadClient != null && spiffeWorkloadClient.isAvailable()) {
            String token = spiffeWorkloadClient.getJwtSvidFor(targetService);
            if (token != null) headers.setBearerAuth(token);
        }
    }

    private void invalidateConverterCache() {
        try {
            String converterUrl = serviceProps.getEdiConverter().getUrl();
            HttpHeaders headers = new HttpHeaders();
            addInternalAuth(headers, "edi-converter");
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            restTemplate.postForEntity(
                    converterUrl + "/api/v1/convert/trained/invalidate-cache",
                    entity, Map.class);
            log.debug("EDI Converter cache invalidated");
        } catch (Exception e) {
            log.warn("Failed to invalidate EDI Converter cache: {}", e.getMessage());
        }
    }

    private void appendCorrectionHistory(MappingCorrectionSession session, String instruction,
                                          List<MappingChange> changes, String summary) {
        try {
            List<Map<String, Object>> history = objectMapper.readValue(
                    session.getCorrectionHistory(),
                    new TypeReference<List<Map<String, Object>>>() {});

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("instruction", instruction);
            entry.put("summary", summary);
            entry.put("changesApplied", changes.size());
            entry.put("changes", changes);

            history.add(entry);
            session.setCorrectionHistory(objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            log.warn("Failed to append correction history: {}", e.getMessage());
        }
    }

    private String computeComparison(String before, String after) {
        if (before == null) before = "";
        if (after == null) after = "";

        try {
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("beforeLength", before.length());
            comparison.put("afterLength", after.length());
            comparison.put("changed", !before.equals(after));

            // Simple line-level diff
            String[] beforeLines = before.split("\n");
            String[] afterLines = after.split("\n");
            List<String> diffs = new ArrayList<>();

            int maxLines = Math.max(beforeLines.length, afterLines.length);
            for (int i = 0; i < maxLines; i++) {
                String bl = i < beforeLines.length ? beforeLines[i].trim() : "";
                String al = i < afterLines.length ? afterLines[i].trim() : "";
                if (!bl.equals(al)) {
                    diffs.add("Line " + (i + 1) + ": \"" + bl + "\" -> \"" + al + "\"");
                }
            }
            comparison.put("diffs", diffs);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(comparison);
        } catch (Exception e) {
            return "{\"error\": \"Could not compute comparison\"}";
        }
    }

    private List<FieldMappingDto> deserializeMappings(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<FieldMappingDto>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize field mappings: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String serializeMappings(List<FieldMappingDto> mappings) {
        try {
            return objectMapper.writeValueAsString(mappings);
        } catch (Exception e) {
            return "[]";
        }
    }

    private SessionResponse toSessionResponse(MappingCorrectionSession session) {
        return SessionResponse.builder()
                .sessionId(session.getId())
                .status(session.getStatus().name())
                .mapKey(session.getMapKey())
                .correctionCount(session.getCorrectionCount())
                .currentMappings(deserializeMappings(session.getCurrentFieldMappingsJson()))
                .currentTestOutput(session.getLatestTestOutput())
                .message("Session " + session.getStatus().name().toLowerCase())
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .build();
    }

    // ===================================================================
    // DTOs
    // ===================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StartRequest {
        private String partnerId;
        private String sourceFormat;
        private String sourceType;
        private String targetFormat;
        private String targetType;
        private String sampleInputContent;
        private String sampleExpectedOutput;
        private UUID flowId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SessionResponse {
        private UUID sessionId;
        private String status;
        private String mapKey;
        private int correctionCount;
        private List<FieldMappingDto> currentMappings;
        private String currentTestOutput;
        private String message;
        private Instant createdAt;
        private Instant expiresAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CorrectionResult {
        private UUID sessionId;
        private boolean applied;
        private String summary;
        @Builder.Default
        private List<MappingChange> changes = new ArrayList<>();
        private String newTestOutput;
        private String previousTestOutput;
        private String comparison;
        private boolean clarificationNeeded;
        private String clarificationQuestion;
        private String nextStepHint;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestResult {
        private UUID sessionId;
        private String testOutput;
        private int mappingCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ApprovalResult {
        private UUID newMapId;
        private String mapKey;
        private int newMapVersion;
        private int confidence;
        private UUID flowId;
        private boolean flowUpdated;
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SessionSummary {
        private UUID sessionId;
        private String mapKey;
        private String status;
        private int correctionCount;
        private Instant createdAt;
    }
}
