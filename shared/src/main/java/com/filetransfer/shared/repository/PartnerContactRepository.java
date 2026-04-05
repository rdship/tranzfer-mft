package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.PartnerContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerContactRepository extends JpaRepository<PartnerContact, UUID> {

    List<PartnerContact> findByPartnerId(UUID partnerId);

    Optional<PartnerContact> findByPartnerIdAndIsPrimaryTrue(UUID partnerId);

    @Modifying
    @Transactional
    void deleteByPartnerId(UUID partnerId);
}
