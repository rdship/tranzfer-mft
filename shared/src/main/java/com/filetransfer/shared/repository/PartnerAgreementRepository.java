package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.PartnerAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PartnerAgreementRepository extends JpaRepository<PartnerAgreement, UUID> {
    List<PartnerAgreement> findByActiveTrue();
}
