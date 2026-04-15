package com.filetransfer.shared.repository.transfer;

import com.filetransfer.shared.entity.transfer.TransferActivityView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only repository for the Activity Monitor materialized view.
 * All Activity Monitor queries go through this — zero joins, pre-computed fields.
 */
public interface TransferActivityViewRepository
        extends JpaRepository<TransferActivityView, UUID>,
                JpaSpecificationExecutor<TransferActivityView> {

    /** Refresh the materialized view. Call every 30s or on-demand. */
    @Modifying
    @Transactional
    @Query(value = "REFRESH MATERIALIZED VIEW CONCURRENTLY transfer_activity_view", nativeQuery = true)
    void refresh();
}
