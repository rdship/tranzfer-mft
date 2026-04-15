package com.filetransfer.shared.repository.core;

import com.filetransfer.shared.entity.core.Partner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerRepository extends JpaRepository<Partner, UUID> {

    Optional<Partner> findBySlug(String slug);

    List<Partner> findByStatus(String status);

    List<Partner> findByPartnerType(String type);

    boolean existsBySlug(String slug);

    List<Partner> findByStatusIn(List<String> statuses);
}
