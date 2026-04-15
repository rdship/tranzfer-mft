package com.filetransfer.shared.repository.security;

import com.filetransfer.shared.entity.security.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    Optional<LoginAttempt> findByUsername(String username);

    void deleteByUsername(String username);

    List<LoginAttempt> findByLockedUntilAfter(Instant now);
}
