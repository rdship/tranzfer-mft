package com.filetransfer.sentinel.repository;

import com.filetransfer.sentinel.entity.SentinelFinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SentinelFindingRepository extends JpaRepository<SentinelFinding, UUID>, JpaSpecificationExecutor<SentinelFinding> {

    Page<SentinelFinding> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<SentinelFinding> findByAnalyzerOrderByCreatedAtDesc(String analyzer, Pageable pageable);

    List<SentinelFinding> findByStatusAndCreatedAtAfterOrderByCreatedAtDesc(String status, Instant after);

    List<SentinelFinding> findByCorrelationGroupId(UUID groupId);

    long countByStatusAndSeverity(String status, String severity);

    long countByStatus(String status);

    long countByCreatedAtAfter(Instant after);

    boolean existsByAnalyzerAndRuleNameAndAffectedServiceAndTrackId(
            String analyzer, String ruleName, String affectedService, String trackId);

    List<SentinelFinding> findTop10ByOrderByCreatedAtDesc();
}
