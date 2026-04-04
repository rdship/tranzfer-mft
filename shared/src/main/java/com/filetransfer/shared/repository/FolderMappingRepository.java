package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FolderMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface FolderMappingRepository extends JpaRepository<FolderMapping, UUID> {

    @Query("SELECT fm FROM FolderMapping fm JOIN FETCH fm.sourceAccount sa " +
           "JOIN FETCH fm.destinationAccount da " +
           "WHERE sa.id = :accountId AND fm.active = true")
    List<FolderMapping> findActiveBySourceAccountId(UUID accountId);

    List<FolderMapping> findBySourceAccountIdOrDestinationAccountId(UUID sourceId, UUID destId);
}
