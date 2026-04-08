package com.filetransfer.sentinel.repository;

import com.filetransfer.sentinel.entity.CorrelationGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CorrelationGroupRepository extends JpaRepository<CorrelationGroup, UUID> {
}
