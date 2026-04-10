package com.filetransfer.storage.repository;

import com.filetransfer.storage.entity.WriteIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WriteIntentRepository extends JpaRepository<WriteIntent, UUID> {
    List<WriteIntent> findByStatus(String status);
    List<WriteIntent> findByStatusAndCreatedAtBefore(String status, Instant before);
}
