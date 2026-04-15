package com.filetransfer.shared.repository.integration;

import com.filetransfer.shared.entity.integration.PartnerAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface PartnerAgreementRepository extends JpaRepository<PartnerAgreement, UUID> {
    @Query("SELECT pa FROM PartnerAgreement pa LEFT JOIN FETCH pa.account WHERE pa.active = true")
    List<PartnerAgreement> findByActiveTrue();
}
