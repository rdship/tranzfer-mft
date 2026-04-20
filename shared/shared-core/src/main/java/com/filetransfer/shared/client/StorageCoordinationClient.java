package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.lang.management.ManagementFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-service client for {@code StorageCoordinationService} in
 * storage-manager. Replaces the Redis-backed {@code DistributedVfsLock}
 * pattern with a Postgres-backed lease hosted by the file-lifecycle
 * authority. See
 * {@code docs/rd/2026-04-R134-external-dep-retirement/03-storage-manager-evolution.md}
 * for the full design.
 *
 * <p><b>Usage — try-with-resources pattern:</b>
 * <pre>{@code
 * try (PlatformLease lease = coordClient.tryAcquire("vfs:write:" + accountId + ":" + path,
 *                                                    Duration.ofSeconds(30))) {
 *     if (lease == null) throw new VfsConcurrentWriteException(path);
 *     vfs.writeFile(accountId, path, storageKey, sizeBytes, trackId, contentType);
 * }
 * }</pre>
 *
 * <p>{@link PlatformLease#close()} releases the lease automatically. If
 * the upload takes longer than the TTL, call {@link PlatformLease#extend}
 * periodically.
 *
 * <p>This client follows the standard {@link BaseServiceClient} SPIFFE
 * JWT-SVID auth pattern, including the X-Forwarded-Authorization fallback
 * for user-initiated calls (R134 fix).
 *
 * <p>Sprint 0 scope: class exists, no callers switch over until Sprint 5.
 * The bean can be injected anywhere (null-safe @Autowired) without
 * changing runtime behaviour.
 */
@Slf4j
@Component
public class StorageCoordinationClient extends BaseServiceClient {

    private static final String HOLDER_PREFIX = inferHolderPrefix();

    public StorageCoordinationClient(RestTemplate restTemplate,
                                      PlatformConfig platformConfig,
                                      ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getStorageManager(), "storage-manager.coordination");
    }

    /**
     * Try to acquire a lease on {@code lockKey} for {@code ttl}. Returns
     * a {@link PlatformLease} that auto-releases on close, or {@code null}
     * when a different holder has a live lease on the key.
     */
    public PlatformLease tryAcquire(String lockKey, Duration ttl) {
        Objects.requireNonNull(lockKey, "lockKey");
        Objects.requireNonNull(ttl, "ttl");
        String holderId = HOLDER_PREFIX + ":" + UUID.randomUUID();
        String path = "/api/v1/coordination/locks/" + encode(lockKey) + "/acquire";
        Map<String, Object> body = Map.of("holderId", holderId, "ttlSeconds", ttl.toSeconds());
        try {
            post(path, body, Map.class);
            return new PlatformLease(lockKey, holderId, ttl, this);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(409)) {
                return null; // someone else holds it
            }
            throw e; // 5xx / 401 / 403 etc — bubble up
        }
    }

    /** Extend a lease held by this caller. */
    public boolean extend(String lockKey, String holderId, Duration newTtl) {
        String path = "/api/v1/coordination/locks/" + encode(lockKey) + "/extend";
        Map<String, Object> body = Map.of("holderId", holderId, "ttlSeconds", newTtl.toSeconds());
        try {
            post(path, body, Map.class);
            return true;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(409)) {
                log.warn("[StorageCoord] extend 409 — key={} holder={}", lockKey, holderId);
                return false;
            }
            throw e;
        }
    }

    /** Release a lease — only the holder may release. */
    public boolean release(String lockKey, String holderId) {
        String path = "/api/v1/coordination/locks/" + encode(lockKey)
                + "?holderId=" + encode(holderId);
        try {
            delete(path);
            return true;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(409)) return false;
            throw e;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Build a per-process prefix: hostname + JVM pid. Combined with a
     * per-lease UUID this gives a unique holder ID even when one pod
     * acquires multiple leases concurrently.
     */
    private static String inferHolderPrefix() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            String rt = ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"
            String pid = rt.contains("@") ? rt.substring(0, rt.indexOf('@')) : rt;
            return host + ":" + pid;
        } catch (Exception e) {
            return "unknown:" + System.currentTimeMillis();
        }
    }

    /**
     * Lease handle — auto-releases on {@link #close()}. Use with
     * try-with-resources.
     */
    public static final class PlatformLease implements AutoCloseable {
        private final String lockKey;
        private final String holderId;
        private final Duration ttl;
        private final StorageCoordinationClient owner;
        private volatile boolean released = false;

        PlatformLease(String lockKey, String holderId, Duration ttl,
                      StorageCoordinationClient owner) {
            this.lockKey = lockKey;
            this.holderId = holderId;
            this.ttl = ttl;
            this.owner = owner;
        }

        public String key() { return lockKey; }
        public String holderId() { return holderId; }
        public Duration ttl() { return ttl; }

        /** Extend the lease. Returns true iff extension succeeded. */
        public boolean extend(Duration newTtl) {
            if (released) return false;
            return owner.extend(lockKey, holderId, newTtl);
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            try {
                owner.release(lockKey, holderId);
            } catch (Exception e) {
                // Best-effort release — lease will auto-expire at TTL even if
                // release-on-close fails (e.g., storage-manager briefly down).
                log.warn("[StorageCoord] release on close threw: {}; lease will expire at TTL",
                        e.getMessage());
            }
        }
    }
}
