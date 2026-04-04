package com.filetransfer.analytics.repository;

import com.filetransfer.analytics.entity.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, UUID> {
    List<MetricSnapshot> findBySnapshotTimeBetweenAndServiceTypeOrderBySnapshotTimeAsc(
            Instant from, Instant to, String serviceType);
    List<MetricSnapshot> findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(Instant from, Instant to);
    List<MetricSnapshot> findTop48ByServiceTypeOrderBySnapshotTimeDesc(String serviceType);

    @Query("SELECT COALESCE(SUM(m.totalTransfers),0) FROM MetricSnapshot m WHERE m.snapshotTime >= :from")
    Long sumTotalTransfersSince(@Param("from") Instant from);

    @Query("SELECT COALESCE(SUM(m.totalBytesTransferred),0) FROM MetricSnapshot m WHERE m.snapshotTime >= :from")
    Long sumBytesSince(@Param("from") Instant from);

    @Query("SELECT COALESCE(SUM(m.successfulTransfers),0) FROM MetricSnapshot m WHERE m.snapshotTime >= :from")
    Long sumSuccessSince(@Param("from") Instant from);
}
