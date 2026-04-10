package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FlowEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FlowEventRepository extends JpaRepository<FlowEvent, UUID> {
    List<FlowEvent> findByTrackIdOrderByCreatedAtAsc(String trackId);
    List<FlowEvent> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);
    List<FlowEvent> findByTrackIdAndEventTypeOrderByCreatedAtAsc(String trackId, String eventType);
    List<FlowEvent> findByCreatedAtAfterOrderByCreatedAtAsc(Instant after);
    long countByTrackId(String trackId);
}
