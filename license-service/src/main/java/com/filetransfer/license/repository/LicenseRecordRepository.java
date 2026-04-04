package com.filetransfer.license.repository;

import com.filetransfer.license.entity.LicenseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LicenseRecordRepository extends JpaRepository<LicenseRecord, UUID> {
    Optional<LicenseRecord> findByLicenseIdAndActiveTrue(String licenseId);
    Optional<LicenseRecord> findByInstallationFingerprintAndActiveTrue(String fingerprint);
    boolean existsByLicenseId(String licenseId);
}
