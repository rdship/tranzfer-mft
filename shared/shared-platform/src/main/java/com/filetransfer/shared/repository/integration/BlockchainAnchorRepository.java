package com.filetransfer.shared.repository.integration;

import com.filetransfer.shared.entity.integration.BlockchainAnchor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlockchainAnchorRepository extends JpaRepository<BlockchainAnchor, UUID> {
    Optional<BlockchainAnchor> findByTrackId(String trackId);
    List<BlockchainAnchor> findByMerkleRoot(String merkleRoot);
    List<BlockchainAnchor> findAllByTrackIdIn(Collection<String> trackIds);
}
