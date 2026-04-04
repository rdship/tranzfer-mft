package com.filetransfer.license.repository;

import com.filetransfer.license.entity.LicenseActivation;
import com.filetransfer.license.entity.LicenseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LicenseActivationRepository extends JpaRepository<LicenseActivation, UUID> {
    List<LicenseActivation> findByLicenseRecord(LicenseRecord licenseRecord);
    Optional<LicenseActivation> findByLicenseRecordAndServiceTypeAndHostId(
            LicenseRecord licenseRecord, String serviceType, String hostId);
    List<LicenseActivation> findByLicenseRecordAndActiveTrueAndServiceType(
            LicenseRecord licenseRecord, String serviceType);
}
