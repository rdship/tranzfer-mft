package com.filetransfer.license.repository;

import com.filetransfer.license.entity.CryptoKeyPair;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CryptoKeyPairRepository extends JpaRepository<CryptoKeyPair, UUID> {
    Optional<CryptoKeyPair> findByKeyName(String keyName);
}
