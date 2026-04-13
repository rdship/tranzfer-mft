package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.core.TransferAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileFlowRepository extends JpaRepository<FileFlow, UUID> {

    @Query("SELECT f FROM FileFlow f " +
           "LEFT JOIN FETCH f.sourceAccount " +
           "LEFT JOIN FETCH f.destinationAccount " +
           "LEFT JOIN FETCH f.externalDestination " +
           "WHERE f.active = true ORDER BY f.priority ASC")
    List<FileFlow> findByActiveTrueOrderByPriorityAsc();

    Optional<FileFlow> findByNameAndActiveTrue(String name);

    @Query("SELECT f FROM FileFlow f " +
           "LEFT JOIN FETCH f.sourceAccount " +
           "LEFT JOIN FETCH f.destinationAccount " +
           "LEFT JOIN FETCH f.externalDestination " +
           "WHERE f.sourceAccount = :sourceAccount AND f.active = true " +
           "ORDER BY f.priority ASC")
    List<FileFlow> findBySourceAccountAndActiveTrueOrderByPriorityAsc(@Param("sourceAccount") TransferAccount sourceAccount);

    boolean existsByName(String name);

    /** Phase 3.4: cheap count check — used to skip full reload when unchanged. */
    long countByActiveTrue();

    // Partner-scoped queries
    List<FileFlow> findByPartnerId(UUID partnerId);

    long countByPartnerId(UUID partnerId);

    /** @deprecated Use findByActiveTrueOrderByPriorityAsc() + FlowMatchEngine instead */
    @Deprecated
    @Query("SELECT f FROM FileFlow f " +
           "LEFT JOIN FETCH f.sourceAccount " +
           "LEFT JOIN FETCH f.destinationAccount " +
           "LEFT JOIN FETCH f.externalDestination " +
           "WHERE f.active = true AND " +
           "(f.sourceAccount IS NULL OR f.sourceAccount = :account) " +
           "ORDER BY f.priority ASC")
    List<FileFlow> findMatchingFlows(@Param("account") TransferAccount account);
}
