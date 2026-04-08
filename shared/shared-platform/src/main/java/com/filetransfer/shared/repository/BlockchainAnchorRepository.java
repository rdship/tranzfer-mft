package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.BlockchainAnchor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlockchainAnchorRepository extends JpaRepository<BlockchainAnchor, UUID> {
    Optional<BlockchainAnchor> findByTrackId(String trackId);
    List<BlockchainAnchor> findByMerkleRoot(String merkleRoot);
}
