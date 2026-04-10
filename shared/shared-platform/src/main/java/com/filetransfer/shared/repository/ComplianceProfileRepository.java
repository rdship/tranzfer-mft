package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.ComplianceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComplianceProfileRepository extends JpaRepository<ComplianceProfile, UUID> {

    Optional<ComplianceProfile> findByName(String name);

    List<ComplianceProfile> findByActiveTrue();
}
