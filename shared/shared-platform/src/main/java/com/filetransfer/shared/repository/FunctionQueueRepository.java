package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FunctionQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FunctionQueueRepository extends JpaRepository<FunctionQueue, UUID> {
    /** Find all queues for a function type (multiple profiles possible) */
    List<FunctionQueue> findByFunctionType(String functionType);
    /** Find the default queue for a function type */
    Optional<FunctionQueue> findByFunctionTypeAndDefaultQueueTrue(String functionType);
    List<FunctionQueue> findByEnabledTrueOrderByCategoryAscFunctionTypeAsc();
    List<FunctionQueue> findByCategoryOrderByFunctionTypeAsc(String category);
    boolean existsByFunctionType(String functionType);
    boolean existsByDisplayName(String displayName);
}
