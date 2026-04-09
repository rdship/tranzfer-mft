package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.PartnerWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PartnerWebhookRepository extends JpaRepository<PartnerWebhook, UUID> {
    List<PartnerWebhook> findByActiveTrue();
    List<PartnerWebhook> findByPartnerNameIgnoreCaseAndActiveTrue(String partnerName);

    /**
     * Atomic counter increment — updates only totalCalls + lastTriggered.
     * Avoids the SELECT + full-entity UPDATE cycle of repository.save().
     */
    @Transactional
    @Modifying
    @Query("UPDATE PartnerWebhook w SET w.totalCalls = w.totalCalls + 1, w.lastTriggered = :now WHERE w.id = :id")
    void incrementTotalCalls(@Param("id") UUID id, @Param("now") Instant now);

    /** Atomic failed-call increment. */
    @Transactional
    @Modifying
    @Query("UPDATE PartnerWebhook w SET w.failedCalls = w.failedCalls + 1 WHERE w.id = :id")
    void incrementFailedCalls(@Param("id") UUID id);
}
