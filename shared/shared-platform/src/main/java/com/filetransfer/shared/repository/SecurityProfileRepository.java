package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.SecurityProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SecurityProfileRepository extends JpaRepository<SecurityProfile, UUID> {
    List<SecurityProfile> findByActiveTrue();
    Optional<SecurityProfile> findByNameAndActiveTrue(String name);
    boolean existsByName(String name);
}
