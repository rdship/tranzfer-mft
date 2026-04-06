package com.filetransfer.ai.repository.edi;

import com.filetransfer.ai.entity.edi.TrainingSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TrainingSampleRepository extends JpaRepository<TrainingSample, UUID> {

    List<TrainingSample> findBySourceFormatAndTargetFormat(String sourceFormat, String targetFormat);

    List<TrainingSample> findBySourceFormatAndSourceTypeAndTargetFormat(
            String sourceFormat, String sourceType, String targetFormat);

    List<TrainingSample> findByPartnerId(String partnerId);

    List<TrainingSample> findBySourceFormatAndSourceTypeAndTargetFormatAndPartnerId(
            String sourceFormat, String sourceType, String targetFormat, String partnerId);

    long countBySourceFormatAndSourceTypeAndTargetFormat(
            String sourceFormat, String sourceType, String targetFormat);

    long countBySourceFormatAndSourceTypeAndTargetFormatAndPartnerId(
            String sourceFormat, String sourceType, String targetFormat, String partnerId);

    @Query("SELECT DISTINCT s.sourceFormat, s.sourceType, s.targetFormat, s.partnerId FROM TrainingSample s")
    List<Object[]> findDistinctMapKeys();

    List<TrainingSample> findByValidatedTrue();

    @Query("SELECT s FROM TrainingSample s WHERE s.sourceFormat = :sourceFormat " +
            "AND (:sourceType IS NULL OR s.sourceType = :sourceType) " +
            "AND s.targetFormat = :targetFormat " +
            "AND (:partnerId IS NULL OR s.partnerId = :partnerId) " +
            "ORDER BY s.qualityScore DESC, s.createdAt DESC")
    List<TrainingSample> findSamplesForTraining(String sourceFormat, String sourceType,
                                                 String targetFormat, String partnerId);
}
