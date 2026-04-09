package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.PartnerWebhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PartnerWebhookRepository extends JpaRepository<PartnerWebhook, UUID> {
    List<PartnerWebhook> findByActiveTrue();
    List<PartnerWebhook> findByPartnerNameIgnoreCaseAndActiveTrue(String partnerName);
}
