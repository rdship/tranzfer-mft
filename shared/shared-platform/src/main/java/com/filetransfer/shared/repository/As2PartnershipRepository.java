package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.integration.As2Partnership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface As2PartnershipRepository extends JpaRepository<As2Partnership, UUID> {

    List<As2Partnership> findByActiveTrue();

    Optional<As2Partnership> findByPartnerAs2IdAndActiveTrue(String partnerAs2Id);

    List<As2Partnership> findByProtocolAndActiveTrue(String protocol);

    Optional<As2Partnership> findByPartnerNameAndActiveTrue(String partnerName);
}
