package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.ChunkedUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChunkedUploadRepository extends JpaRepository<ChunkedUpload, UUID> {

    List<ChunkedUpload> findByStatusAndExpiresAtBefore(String status, Instant now);

    List<ChunkedUpload> findByAccountUsernameOrderByCreatedAtDesc(String accountUsername);

    List<ChunkedUpload> findByStatus(String status);
}
