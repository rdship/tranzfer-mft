package com.filetransfer.shared.repository.security;

import com.filetransfer.shared.entity.security.ComplianceViolation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ComplianceViolationRepository extends JpaRepository<ComplianceViolation, UUID> {

    List<ComplianceViolation> findByTrackId(String trackId);

    List<ComplianceViolation> findByProfileIdOrderByCreatedAtDesc(UUID profileId);

    List<ComplianceViolation> findByResolvedFalseOrderByCreatedAtDesc();

    List<ComplianceViolation> findBySeverityAndResolvedFalseOrderByCreatedAtDesc(String severity);

    List<ComplianceViolation> findByServerInstanceIdOrderByCreatedAtDesc(UUID serverInstanceId);

    List<ComplianceViolation> findByUsernameOrderByCreatedAtDesc(String username);

    long countByResolvedFalse();

    long countByProfileId(UUID profileId);
}
