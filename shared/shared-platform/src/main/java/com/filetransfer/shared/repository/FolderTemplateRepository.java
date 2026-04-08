package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FolderTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FolderTemplateRepository extends JpaRepository<FolderTemplate, UUID> {
    Optional<FolderTemplate> findByName(String name);
    List<FolderTemplate> findByActiveTrue();
    List<FolderTemplate> findByBuiltInTrue();
    boolean existsByName(String name);
}
