package com.filetransfer.ai.repository.edi;

import com.filetransfer.ai.entity.edi.TrainingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TrainingSessionRepository extends JpaRepository<TrainingSession, UUID> {

    List<TrainingSession> findByMapKeyOrderByStartedAtDesc(String mapKey);

    List<TrainingSession> findByStatusOrderByStartedAtDesc(TrainingSession.Status status);

    List<TrainingSession> findTop20ByOrderByStartedAtDesc();
}
