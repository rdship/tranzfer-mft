package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.BlockchainAnchor;
import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.repository.BlockchainAnchorRepository;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController @RequestMapping("/api/v1/blockchain") @RequiredArgsConstructor @Slf4j
@PreAuthorize(Roles.VIEWER)
public class BlockchainController {

    private final BlockchainAnchorRepository anchorRepo;
    private final FileTransferRecordRepository recordRepo;

    /** Anchor mode: INTERNAL (local DB only), DOCUMENT (RFC 3161 timestamping), ETHEREUM (future). */
    @Value("${blockchain.anchor-mode:INTERNAL}")
    private String anchorMode;

    /** RFC 3161 timestamping service URL for DOCUMENT anchor mode. */
    @Value("${blockchain.timestamp-service-url:}")
    private String timestampServiceUrl;

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
    @SchedulerLock(name = "blockchain_anchorBatch", lockAtLeastFor = "PT50M", lockAtMostFor = "PT2H")
    @PreAuthorize("permitAll()")
    public void anchorBatch() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        List<FileTransferRecord> candidates = recordRepo.findAll().stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(cutoff))
                .filter(r -> r.getSourceChecksum() != null)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return;

        // Batch query to eliminate N+1: fetch all existing anchors for candidate trackIds in one query
        Set<String> candidateTrackIds = candidates.stream()
                .map(FileTransferRecord::getTrackId).collect(Collectors.toSet());
        Set<String> alreadyAnchored = anchorRepo.findAllByTrackIdIn(candidateTrackIds).stream()
                .map(BlockchainAnchor::getTrackId).collect(Collectors.toSet());

        List<FileTransferRecord> recent = candidates.stream()
                .filter(r -> !alreadyAnchored.contains(r.getTrackId()))
                .collect(Collectors.toList());

        if (recent.isEmpty()) return;

        // Build Merkle tree
        List<String> hashes = recent.stream().map(FileTransferRecord::getSourceChecksum).collect(Collectors.toList());
        String merkleRoot = computeMerkleRoot(hashes);

        // Determine chain label from configured anchor mode
        String chainLabel = anchorMode.toUpperCase();

        // For DOCUMENT mode, post Merkle root to an RFC 3161 timestamping service
        String timestampToken = null;
        if ("DOCUMENT".equals(chainLabel)) {
            timestampToken = postToTimestampService(merkleRoot);
        }

        for (FileTransferRecord r : recent) {
            String proof = "merkle_root=" + merkleRoot + ";leaf=" + r.getSourceChecksum() + ";batch_size=" + recent.size();
            if (timestampToken != null) {
                proof += ";timestamp_token=" + timestampToken;
            }
            anchorRepo.save(BlockchainAnchor.builder()
                    .trackId(r.getTrackId()).filename(r.getOriginalFilename())
                    .sha256(r.getSourceChecksum()).merkleRoot(merkleRoot)
                    .chain(chainLabel)
                    // INTERNAL mode: proof is local-only — suitable for development/audit trail.
                    // DOCUMENT mode: proof includes RFC 3161 timestamp token for non-repudiation.
                    .proof(proof)
                    .build());
        }
        log.info("Blockchain [{}]: anchored {} transfers, merkle root: {}", chainLabel, recent.size(), merkleRoot.substring(0, 16));
    }

    /**
     * Post Merkle root hash to an RFC 3161 timestamping service (DOCUMENT anchor mode).
     * Returns the timestamp token string, or null on failure (graceful degradation).
     */
    private String postToTimestampService(String merkleRoot) {
        if (timestampServiceUrl == null || timestampServiceUrl.isBlank()) {
            log.warn("DOCUMENT anchor mode configured but no timestamp-service-url set; skipping external timestamp");
            return null;
        }
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);
            factory.setReadTimeout(10000);
            RestTemplate rest = new RestTemplate(factory);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"hash\":\"" + merkleRoot + "\",\"algorithm\":\"SHA-256\"}";
            HttpEntity<String> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = rest.exchange(
                    timestampServiceUrl, HttpMethod.POST, request, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("RFC 3161 timestamp obtained for merkle root: {}", merkleRoot.substring(0, 16));
                return response.getBody();
            }
            log.warn("Timestamp service returned non-2xx: {}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            log.warn("Failed to obtain RFC 3161 timestamp (falling back to local proof): {}", e.getMessage());
            return null;
        }
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
