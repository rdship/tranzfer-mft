package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.WebhookConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface WebhookConnectorRepository extends JpaRepository<WebhookConnector, UUID> {
    List<WebhookConnector> findByActiveTrue();
}
