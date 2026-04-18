package com.filetransfer.ai.repository.edi;

import com.filetransfer.ai.entity.edi.MappingCorrectionSession;
import com.filetransfer.ai.entity.edi.MappingCorrectionSession.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MappingCorrectionSessionRepository extends JpaRepository<MappingCorrectionSession, UUID> {

    Optional<MappingCorrectionSession> findByIdAndPartnerId(UUID id, String partnerId);

    List<MappingCorrectionSession> findByPartnerIdOrderByCreatedAtDesc(String partnerId);

    /** R127: admin-side "all sessions" view — no partnerId filter. */
    List<MappingCorrectionSession> findAllByOrderByCreatedAtDesc();

    List<MappingCorrectionSession> findByPartnerIdAndStatusOrderByCreatedAtDesc(String partnerId, Status status);

    /** Find stale sessions that should be expired */
    List<MappingCorrectionSession> findByStatusAndExpiresAtBefore(Status status, Instant cutoff);

    /** Check for existing active session on the same conversion path for a partner */
    Optional<MappingCorrectionSession> findByPartnerIdAndMapKeyAndStatus(String partnerId, String mapKey, Status status);
}
