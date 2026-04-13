package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.transfer.FabricInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface FabricInstanceRepository extends JpaRepository<FabricInstance, String> {

    List<FabricInstance> findByServiceName(String serviceName);

    List<FabricInstance> findByStatus(String status);

    /**
     * Find instances that haven't heartbeated recently (dead pods).
     */
    @Query("SELECT i FROM FabricInstance i WHERE i.lastHeartbeat < :threshold")
    List<FabricInstance> findDeadInstances(@Param("threshold") Instant threshold);
}
