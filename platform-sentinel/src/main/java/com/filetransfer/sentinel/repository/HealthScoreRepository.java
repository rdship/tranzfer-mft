package com.filetransfer.sentinel.repository;

import com.filetransfer.sentinel.entity.HealthScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthScoreRepository extends JpaRepository<HealthScore, UUID> {

    Optional<HealthScore> findTopByOrderByRecordedAtDesc();

    List<HealthScore> findByRecordedAtAfterOrderByRecordedAtAsc(Instant after);
}
