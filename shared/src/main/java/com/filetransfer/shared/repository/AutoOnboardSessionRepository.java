package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.AutoOnboardSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface AutoOnboardSessionRepository extends JpaRepository<AutoOnboardSession, UUID> {
    Optional<AutoOnboardSession> findBySourceIpAndPhaseNot(String ip, String phase);
    Optional<AutoOnboardSession> findByGeneratedUsername(String username);
    List<AutoOnboardSession> findByPhaseOrderByCreatedAtDesc(String phase);
}
