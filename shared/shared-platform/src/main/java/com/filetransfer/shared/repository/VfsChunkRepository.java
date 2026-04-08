package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.VfsChunk;
import com.filetransfer.shared.entity.VfsChunk.ChunkStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface VfsChunkRepository extends JpaRepository<VfsChunk, UUID> {

    /** Get all chunks for a file entry, ordered for reassembly. */
    List<VfsChunk> findByEntryIdOrderByChunkIndex(UUID entryId);

    /** Count chunks in a given status (completion check). */
    long countByEntryIdAndStatus(UUID entryId, ChunkStatus status);

    /** Count chunks referencing a CAS key (orphan detection). */
    long countByStorageKey(String storageKey);

    /** Delete all chunks for an entry (cleanup on delete). */
    @Modifying
    @Query("DELETE FROM VfsChunk c WHERE c.entryId = :entryId")
    int deleteByEntryId(UUID entryId);
}
