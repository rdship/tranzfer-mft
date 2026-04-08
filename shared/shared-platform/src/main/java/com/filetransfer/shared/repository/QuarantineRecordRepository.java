package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.QuarantineRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuarantineRecordRepository extends JpaRepository<QuarantineRecord, UUID> {

    List<QuarantineRecord> findByStatusOrderByQuarantinedAtDesc(String status);

    List<QuarantineRecord> findAllByOrderByQuarantinedAtDesc();

    Optional<QuarantineRecord> findByTrackId(String trackId);

    long countByStatus(String status);
}
