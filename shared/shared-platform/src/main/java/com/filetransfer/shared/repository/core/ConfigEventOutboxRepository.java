package com.filetransfer.shared.repository.core;

import com.filetransfer.shared.entity.core.ConfigEventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ConfigEventOutboxRepository extends JpaRepository<ConfigEventOutbox, Long> {

    /** Oldest unpublished events, FIFO — small batches so retries don't starve. */
    @Query("SELECT e FROM ConfigEventOutbox e WHERE e.publishedAt IS NULL ORDER BY e.id ASC")
    List<ConfigEventOutbox> findUnpublished(org.springframework.data.domain.Pageable pageable);

    /** Housekeeping — delete successfully published rows older than a window. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ConfigEventOutbox e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
