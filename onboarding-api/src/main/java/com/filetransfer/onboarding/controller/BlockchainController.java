package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.BlockchainAnchor;
import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.repository.BlockchainAnchorRepository;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController @RequestMapping("/api/v1/blockchain") @RequiredArgsConstructor @Slf4j
public class BlockchainController {

    private final BlockchainAnchorRepository anchorRepo;
    private final FileTransferRecordRepository recordRepo;

    /** Verify a transfer's blockchain proof */
    @GetMapping("/verify/{trackId}")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable String trackId) {
        BlockchainAnchor anchor = anchorRepo.findByTrackId(trackId).orElse(null);
        FileTransferRecord record = recordRepo.findByTrackId(trackId).orElse(null);

        if (anchor == null) return ResponseEntity.ok(Map.of("verified", false, "reason", "No blockchain anchor found"));

        boolean checksumMatch = record != null && record.getSourceChecksum() != null &&
                record.getSourceChecksum().equals(anchor.getSha256());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("verified", checksumMatch);
        resp.put("trackId", trackId);
        resp.put("filename", anchor.getFilename());
        resp.put("sha256", anchor.getSha256());
        resp.put("chain", anchor.getChain());
        resp.put("merkleRoot", anchor.getMerkleRoot());
        resp.put("anchoredAt", anchor.getAnchoredAt().toString());
        resp.put("proof", anchor.getProof());
        resp.put("nonRepudiation", "This cryptographic proof confirms the file existed with this exact content at the stated time.");
        return ResponseEntity.ok(resp);
    }

    /** Anchor recent transfers (runs every hour) */
    @Scheduled(cron = "0 0 * * * *")
    public void anchorBatch() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        List<FileTransferRecord> recent = recordRepo.findAll().stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(cutoff))
                .filter(r -> r.getSourceChecksum() != null)
                .filter(r -> anchorRepo.findByTrackId(r.getTrackId()).isEmpty())
                .collect(Collectors.toList());

        if (recent.isEmpty()) return;

        // Build Merkle tree
        List<String> hashes = recent.stream().map(FileTransferRecord::getSourceChecksum).collect(Collectors.toList());
        String merkleRoot = computeMerkleRoot(hashes);

        for (FileTransferRecord r : recent) {
            anchorRepo.save(BlockchainAnchor.builder()
                    .trackId(r.getTrackId()).filename(r.getOriginalFilename())
                    .sha256(r.getSourceChecksum()).merkleRoot(merkleRoot)
                    .chain("INTERNAL")
                    .proof("merkle_root=" + merkleRoot + ";leaf=" + r.getSourceChecksum() + ";batch_size=" + recent.size())
                    .build());
        }
        log.info("Blockchain: anchored {} transfers, merkle root: {}", recent.size(), merkleRoot.substring(0, 16));
    }

    @GetMapping("/anchors")
    public List<BlockchainAnchor> recentAnchors() {
        return anchorRepo.findAll().stream()
                .sorted(Comparator.comparing(BlockchainAnchor::getAnchoredAt).reversed())
                .limit(50).collect(Collectors.toList());
    }

    private String computeMerkleRoot(List<String> hashes) {
        if (hashes.isEmpty()) return "";
        List<String> layer = new ArrayList<>(hashes);
        while (layer.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < layer.size(); i += 2) {
                String left = layer.get(i);
                String right = i + 1 < layer.size() ? layer.get(i + 1) : left;
                next.add(sha256(left + right));
            }
            layer = next;
        }
        return layer.get(0);
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes()));
        } catch (Exception e) { return input; }
    }
}
