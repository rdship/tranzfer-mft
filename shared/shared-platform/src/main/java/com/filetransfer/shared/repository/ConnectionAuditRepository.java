package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.security.ConnectionAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ConnectionAuditRepository extends JpaRepository<ConnectionAudit, UUID> {
    List<ConnectionAudit> findByPartnerIdOrderByConnectedAtDesc(UUID partnerId);
    List<ConnectionAudit> findByUsernameOrderByConnectedAtDesc(String username);
    long countByRoutedToAndConnectedAtAfter(String routedTo, Instant since);
    long countByPartnerIdAndRoutedTo(UUID partnerId, String routedTo);

    @Query("SELECT c.routedTo, COUNT(c) FROM ConnectionAudit c WHERE c.connectedAt > :since GROUP BY c.routedTo")
    List<Object[]> countByRoutedToSince(Instant since);

    @Query("SELECT c.partnerId, c.partnerName, c.routedTo, COUNT(c) FROM ConnectionAudit c WHERE c.connectedAt > :since GROUP BY c.partnerId, c.partnerName, c.routedTo")
    List<Object[]> countByPartnerAndRouteSince(Instant since);
}
