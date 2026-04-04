package com.filetransfer.screening.repository;

import com.filetransfer.screening.entity.ScreeningResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreeningResultRepository extends JpaRepository<ScreeningResult, UUID> {
    Optional<ScreeningResult> findByTrackId(String trackId);
    List<ScreeningResult> findByOutcomeOrderByScreenedAtDesc(String outcome);
    List<ScreeningResult> findTop50ByOrderByScreenedAtDesc();
}
