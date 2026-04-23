package com.filetransfer.storage.coordination;

import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST face of {@link StorageCoordinationService} for external callers
 * (listener services, flow engine). In-process callers use the service
 * directly.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST   /api/v1/coordination/locks/{key}/acquire}</li>
 *   <li>{@code POST   /api/v1/coordination/locks/{key}/extend}</li>
 *   <li>{@code DELETE /api/v1/coordination/locks/{key}}</li>
 *   <li>{@code GET    /api/v1/coordination/health}</li>
 * </ul>
 *
 * <p><b>R134AL — auth posture narrowed.</b> Class-level default is
 * {@link Roles#INTERNAL} only: lock acquire/extend are machine-to-machine
 * lease operations, never UI-invoked, so admitting ADMIN/OPERATOR widened
 * the blast radius with no practical use case (a human flipping a lock
 * underneath a running flow-engine step would cause chaos). {@code release}
 * keeps {@link Roles#INTERNAL_OR_OPERATOR} as a break-glass path for ops
 * to force-unstick a dead-pod lease during incident response.
 * {@code /health} stays {@code permitAll()} for container probes.
 *
 * <p>Callers: inter-service clients present a SPIFFE JWT-SVID which
 * {@code PlatformJwtAuthFilter} (Path 1) maps to ROLE_INTERNAL.
 *
 * <p>Lock-key encoding: path segment — a caller-chosen string like
 * {@code vfs:write:550e8400-...:/inbox/x.xml}. Colons are safe in path
 * segments per RFC 3986; slashes ARE NOT, so callers must URL-encode any
 * slashes in the key. The Spring path-matcher uses {@code {key:.+}} to
 * capture the whole segment including colons.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/coordination")
@RequiredArgsConstructor
@PreAuthorize(Roles.INTERNAL)
public class StorageCoordinationController {

    private final StorageCoordinationService service;

    /** Default lease TTL when the caller doesn't specify one. */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    /** Maximum lease TTL — caller bugs that request hours of TTL get clamped. */
    private static final Duration MAX_TTL = Duration.ofMinutes(30);

    @PostMapping("/locks/{lockKey:.+}/acquire")
    public ResponseEntity<Map<String, Object>> acquire(
            @PathVariable String lockKey,
            @RequestBody AcquireRequest req) {
        Duration ttl = clampTtl(req.ttlSeconds());
        boolean held = service.tryAcquire(lockKey, req.holderId(), ttl);
        Map<String, Object> body = buildLeaseBody(lockKey, req.holderId(), ttl, held);
        return held ? ResponseEntity.ok(body) : ResponseEntity.status(409).body(body);
    }

    @PostMapping("/locks/{lockKey:.+}/extend")
    public ResponseEntity<Map<String, Object>> extend(
            @PathVariable String lockKey,
            @RequestBody AcquireRequest req) {
        Duration ttl = clampTtl(req.ttlSeconds());
        boolean extended = service.extend(lockKey, req.holderId(), ttl);
        Map<String, Object> body = buildLeaseBody(lockKey, req.holderId(), ttl, extended);
        return extended ? ResponseEntity.ok(body) : ResponseEntity.status(409).body(body);
    }

    /**
     * R134AL — {@code release} keeps the broader {@link Roles#INTERNAL_OR_OPERATOR}
     * posture as a break-glass path. A stuck lease from a crashed pod needs a
     * human operator's escape hatch; acquire/extend do not.
     */
    @DeleteMapping("/locks/{lockKey:.+}")
    @PreAuthorize(Roles.INTERNAL_OR_OPERATOR)
    public ResponseEntity<Map<String, Object>> release(
            @PathVariable String lockKey,
            @RequestParam String holderId) {
        boolean released = service.release(lockKey, holderId);
        return released ? ResponseEntity.noContent().build()
                        : ResponseEntity.status(409).body(Map.of(
                            "released", false,
                            "reason", "not held by " + holderId + " (or already expired)"));
    }

    @GetMapping("/health")
    @PreAuthorize("permitAll()")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "storage-manager.coordination");
    }

    private static Duration clampTtl(Long ttlSeconds) {
        if (ttlSeconds == null || ttlSeconds <= 0) return DEFAULT_TTL;
        Duration requested = Duration.ofSeconds(ttlSeconds);
        if (requested.compareTo(MAX_TTL) > 0) {
            log.warn("[StorageCoordination] clamping TTL {} to max {}", requested, MAX_TTL);
            return MAX_TTL;
        }
        return requested;
    }

    private static Map<String, Object> buildLeaseBody(String lockKey, String holderId,
                                                        Duration ttl, boolean success) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lockKey", lockKey);
        body.put("holderId", holderId);
        body.put("ttlSeconds", ttl.toSeconds());
        body.put("held", success);
        return body;
    }

    /** Request body. {@code ttlSeconds} is optional (defaults to 30s). */
    public record AcquireRequest(String holderId, Long ttlSeconds) {}
}
