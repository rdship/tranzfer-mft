package com.filetransfer.sentinel.repository;

import com.filetransfer.sentinel.entity.SentinelFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SentinelFindingRepository extends JpaRepository<SentinelFinding, UUID> {

    Page<SentinelFinding> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<SentinelFinding> findByAnalyzerOrderByCreatedAtDesc(String analyzer, Pageable pageable);

    List<SentinelFinding> findByStatusAndCreatedAtAfterOrderByCreatedAtDesc(String status, Instant after);

    List<SentinelFinding> findByCorrelationGroupId(UUID groupId);

    long countByStatusAndSeverity(String status, String severity);

    long countByStatus(String status);

    long countByCreatedAtAfter(Instant after);

    boolean existsByAnalyzerAndRuleNameAndAffectedServiceAndTrackId(
            String analyzer, String ruleName, String affectedService, String trackId);

    @Query("SELECT f FROM SentinelFinding f WHERE " +
           "(:status IS NULL OR f.status = :status) AND " +
           "(:severity IS NULL OR f.severity = :severity) AND " +
           "(:analyzer IS NULL OR f.analyzer = :analyzer) AND " +
           "(:service IS NULL OR f.affectedService = :service) " +
           "ORDER BY f.createdAt DESC")
    Page<SentinelFinding> search(String status, String severity, String analyzer, String service, Pageable pageable);

    List<SentinelFinding> findTop10ByOrderByCreatedAtDesc();
}
