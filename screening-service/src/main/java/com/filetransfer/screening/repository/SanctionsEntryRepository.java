package com.filetransfer.screening.repository;

import com.filetransfer.screening.entity.SanctionsEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface SanctionsEntryRepository extends JpaRepository<SanctionsEntry, UUID> {
    long countBySource(String source);
    void deleteBySource(String source);
    @Query("SELECT s FROM SanctionsEntry s WHERE s.nameLower LIKE %:fragment%")
    List<SanctionsEntry> searchByNameFragment(String fragment);
}
