package com.filetransfer.license.repository;

import com.filetransfer.license.entity.InstallationFingerprint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface InstallationFingerprintRepository extends JpaRepository<InstallationFingerprint, UUID> {
    Optional<InstallationFingerprint> findByFingerprintAndActiveTrue(String fingerprint);
}
