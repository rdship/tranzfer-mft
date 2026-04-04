package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.ScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, UUID> {
    List<ScheduledTask> findByEnabledTrueOrderByNextRunAsc();
    Optional<ScheduledTask> findByName(String name);
}
