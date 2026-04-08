package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FolderMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FolderMappingRepository extends JpaRepository<FolderMapping, UUID> {

    @Query("SELECT fm FROM FolderMapping fm " +
           "JOIN FETCH fm.sourceAccount " +
           "LEFT JOIN FETCH fm.destinationAccount " +
           "LEFT JOIN FETCH fm.externalDestination " +
           "WHERE fm.sourceAccount.id = :accountId AND fm.active = true")
    List<FolderMapping> findActiveBySourceAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT fm FROM FolderMapping fm " +
           "LEFT JOIN FETCH fm.sourceAccount " +
           "LEFT JOIN FETCH fm.destinationAccount " +
           "LEFT JOIN FETCH fm.externalDestination " +
           "WHERE fm.sourceAccount.id = :sourceId OR fm.destinationAccount.id = :destId")
    List<FolderMapping> findBySourceAccountIdOrDestinationAccountId(@Param("sourceId") UUID sourceId, @Param("destId") UUID destId);
}
