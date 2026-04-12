package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FunctionQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FunctionQueueRepository extends JpaRepository<FunctionQueue, UUID> {
    Optional<FunctionQueue> findByFunctionType(String functionType);
    List<FunctionQueue> findByEnabledTrueOrderByCategoryAscFunctionTypeAsc();
    List<FunctionQueue> findByCategoryOrderByFunctionTypeAsc(String category);
    boolean existsByFunctionType(String functionType);
}
