package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.core.ExternalDestination;
import com.filetransfer.shared.enums.ExternalDestinationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExternalDestinationRepository extends JpaRepository<ExternalDestination, UUID> {
    List<ExternalDestination> findByTypeAndActiveTrue(ExternalDestinationType type);
    List<ExternalDestination> findByActiveTrue();
}
