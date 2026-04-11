package com.filetransfer.ai.service.edi;

import com.filetransfer.ai.entity.edi.TrainingSample;
import com.filetransfer.ai.repository.edi.TrainingSampleRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Training data management service.
 * Handles CRUD for training samples + bulk ingestion + quality scoring.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EdiTrainingDataService {

    private final TrainingSampleRepository sampleRepo;
    private static final int MAX_BULK_SIZE = 500;

    /**
     * Add a single training sample.
     */
    @Transactional
    public TrainingSample addSample(AddSampleRequest request) {
        TrainingSample sample = TrainingSample.builder()
                .sourceFormat(request.getSourceFormat().toUpperCase())
                .sourceType(request.getSourceType())
                .sourceVersion(request.getSourceVersion())
                .targetFormat(request.getTargetFormat().toUpperCase())
                .targetType(request.getTargetType())
                .partnerId(request.getPartnerId())
                .inputContent(request.getInputContent())
                .outputContent(request.getOutputContent())
                .notes(request.getNotes())
                .qualityScore(assessQuality(request.getInputContent(), request.getOutputContent()))
                .validated(false)
                .build();

        sample = sampleRepo.save(sample);
        log.info("Added training sample {} (mapKey={})", sample.getId(), sample.getMapKey());
        return sample;
    }

    /**
     * Bulk add training samples.
     */
    @Transactional
    public BulkIngestionResult addSamplesBulk(List<AddSampleRequest> requests) {
        if (requests.size() > MAX_BULK_SIZE) {
            return BulkIngestionResult.builder()
                    .success(false)
                    .error("Batch size " + requests.size() + " exceeds maximum " + MAX_BULK_SIZE)
                    .build();
        }

        int added = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            try {
                addSample(requests.get(i));
                added++;
            } catch (Exception e) {
                skipped++;
                errors.add("Sample " + i + ": " + e.getMessage());
            }
        }

        return BulkIngestionResult.builder()
                .success(errors.isEmpty())
                .added(added)
                .skipped(skipped)
                .errors(errors)
                .build();
    }

    /**
     * Get samples for a specific map key (for training).
     */
    public List<TrainingSample> getSamplesForTraining(String sourceFormat, String sourceType,
                                                       String targetFormat, String partnerId) {
        return sampleRepo.findSamplesForTraining(
                sourceFormat.toUpperCase(),
                sourceType,
                targetFormat.toUpperCase(),
                partnerId);
    }

    /**
     * Get all samples for a partner.
     */
    public List<TrainingSample> getSamplesByPartner(String partnerId) {
        return sampleRepo.findByPartnerId(partnerId);
    }

    /**
     * List all samples with optional format and partner filters — backs
     * the admin UI's Samples table which wants a single endpoint that
     * returns a flat, filterable list. Caller passes null to skip a filter.
     */
    public List<TrainingSample> listSamples(String format, String partnerId) {
        Iterable<TrainingSample> all = sampleRepo.findAll();
        final String fmt = format == null ? null : format.toUpperCase();
        List<TrainingSample> result = new ArrayList<>();
        for (TrainingSample s : all) {
            if (fmt != null
                    && !fmt.equalsIgnoreCase(s.getSourceFormat())
                    && !fmt.equalsIgnoreCase(s.getTargetFormat())) {
                continue;
            }
            if (partnerId != null && !partnerId.equals(s.getPartnerId())) {
                continue;
            }
            result.add(s);
        }
        return result;
    }

    /**
     * Get a single sample by ID.
     */
    public Optional<TrainingSample> getSample(UUID id) {
        return sampleRepo.findById(id);
    }

    /**
     * Delete a sample.
     */
    @Transactional
    public boolean deleteSample(UUID id) {
        if (sampleRepo.existsById(id)) {
            sampleRepo.deleteById(id);
            return true;
        }
        return false;
    }

    /**
     * Mark a sample as human-validated.
     */
    @Transactional
    public Optional<TrainingSample> validateSample(UUID id) {
        return sampleRepo.findById(id).map(sample -> {
            sample.setValidated(true);
            sample.setQualityScore(Math.max(sample.getQualityScore(), 80));
            return sampleRepo.save(sample);
        });
    }

    /**
     * Get sample counts grouped by map key (overview dashboard).
     */
    public List<MapKeySampleCount> getSampleCounts() {
        List<Object[]> raw = sampleRepo.findDistinctMapKeys();
        return raw.stream().map(row -> {
            String sourceFormat = (String) row[0];
            String sourceType = (String) row[1];
            String targetFormat = (String) row[2];
            String partnerId = (String) row[3];

            long count = sampleRepo.countBySourceFormatAndSourceTypeAndTargetFormatAndPartnerId(
                    sourceFormat, sourceType, targetFormat, partnerId);

            String mapKey = TrainedMapStore.buildMapKey(sourceFormat, sourceType, targetFormat, null, partnerId);
            return new MapKeySampleCount(mapKey, sourceFormat, sourceType, targetFormat, partnerId, count);
        }).toList();
    }

    /**
     * Auto-assess quality of a training sample (0-100).
     * Higher quality = more valuable for training.
     */
    private int assessQuality(String input, String output) {
        int score = 50; // baseline

        if (input == null || output == null) return 0;

        // Bonus for non-trivial content
        if (input.length() > 100) score += 10;
        if (output.length() > 50) score += 10;

        // Bonus for structured input (detectable format)
        if (input.contains("ISA*") || input.contains("UNB+") || input.contains("MSH|")) score += 10;

        // Bonus for structured output
        if (output.trim().startsWith("{") || output.trim().startsWith("<") || output.contains(",")) score += 10;

        // Penalty for very short samples
        if (input.length() < 20) score -= 20;
        if (output.length() < 10) score -= 20;

        return Math.max(0, Math.min(100, score));
    }

    // === DTOs ===

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AddSampleRequest {
        private String sourceFormat;
        private String sourceType;
        private String sourceVersion;
        private String targetFormat;
        private String targetType;
        private String partnerId;
        private String inputContent;
        private String outputContent;
        private String notes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BulkIngestionResult {
        private boolean success;
        private String error;
        private int added;
        private int skipped;
        private List<String> errors;
    }

    public record MapKeySampleCount(String mapKey, String sourceFormat, String sourceType,
                                     String targetFormat, String partnerId, long sampleCount) {}
}
