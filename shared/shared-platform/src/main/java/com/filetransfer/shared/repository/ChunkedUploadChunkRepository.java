package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.vfs.ChunkedUploadChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChunkedUploadChunkRepository extends JpaRepository<ChunkedUploadChunk, UUID> {

    List<ChunkedUploadChunk> findByUploadIdOrderByChunkNumberAsc(UUID uploadId);

    Optional<ChunkedUploadChunk> findByUploadIdAndChunkNumber(UUID uploadId, int chunkNumber);

    long countByUploadId(UUID uploadId);

    void deleteByUploadId(UUID uploadId);
}
