package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.entity.TransferAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileFlowRepository extends JpaRepository<FileFlow, UUID> {
    List<FileFlow> findByActiveTrueOrderByPriorityAsc();
    Optional<FileFlow> findByNameAndActiveTrue(String name);
    List<FileFlow> findBySourceAccountAndActiveTrueOrderByPriorityAsc(TransferAccount sourceAccount);
    boolean existsByName(String name);

    @Query("SELECT f FROM FileFlow f WHERE f.active = true AND " +
           "(f.sourceAccount IS NULL OR f.sourceAccount = :account) " +
           "ORDER BY f.priority ASC")
    List<FileFlow> findMatchingFlows(@Param("account") TransferAccount account);
}
