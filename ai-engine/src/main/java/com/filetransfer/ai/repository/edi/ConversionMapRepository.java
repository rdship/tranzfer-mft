package com.filetransfer.ai.repository.edi;

import com.filetransfer.ai.entity.edi.ConversionMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversionMapRepository extends JpaRepository<ConversionMap, UUID> {

    Optional<ConversionMap> findByMapKeyAndActiveTrue(String mapKey);

    List<ConversionMap> findByMapKeyOrderByVersionDesc(String mapKey);

    List<ConversionMap> findByActiveTrue();

    List<ConversionMap> findByPartnerId(String partnerId);

    List<ConversionMap> findByPartnerIdAndStatus(String partnerId, String status);

    List<ConversionMap> findByPartnerIdAndSourceFormatAndTargetFormatAndStatus(
            String partnerId, String sourceFormat, String targetFormat, String status);

    List<ConversionMap> findBySourceFormatAndTargetFormat(String sourceFormat, String targetFormat);

    Optional<ConversionMap> findByMapKeyAndVersion(String mapKey, int version);

    @Query("SELECT MAX(m.version) FROM ConversionMap m WHERE m.mapKey = :mapKey")
    Optional<Integer> findMaxVersionByMapKey(String mapKey);

    @Modifying
    @Query("UPDATE ConversionMap m SET m.active = false WHERE m.mapKey = :mapKey")
    void deactivateAllByMapKey(String mapKey);

    @Query("SELECT DISTINCT m.mapKey FROM ConversionMap m WHERE m.active = true")
    List<String> findDistinctActiveMapKeys();
}
