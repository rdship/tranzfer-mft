package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.core.ClientPresence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientPresenceRepository extends JpaRepository<ClientPresence, UUID> {
    Optional<ClientPresence> findByUsername(String username);
    List<ClientPresence> findByOnlineTrueAndLastSeenAfter(Instant cutoff);
}
