package com.filetransfer.storage.registry;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Redis-backed registry that records which storage-manager instance holds each file.
 *
 * <h3>The multi-replica problem</h3>
 * <pre>
 * Pod A stores sha256=abc → /data/storage/hot/abc.bin  (local to Pod A only)
 * Pod B receives retrieve(sha256=abc) → 404 — file doesn't exist on Pod B's disk
 * </pre>
 *
 * <h3>This registry's solution</h3>
 * <pre>
 * Pod A stores file →
 *     SET platform:storage:location:abc "http://storage-manager-A:8096"
 *
 * Pod B receives retrieve(sha256=abc) →
 *     GET platform:storage:location:abc → "http://storage-manager-A:8096"
 *     Proxy GET http://storage-manager-A:8096/api/v1/storage/retrieve-by-key/abc
 *     Return bytes to caller
 * </pre>
 *
 * <p>This is an <em>interim</em> solution. The correct long-term fix is shared object
 * storage (NFS volume, MinIO, or S3) so all pods see the same bytes.  When running
 * with a shared backend, set {@code storage.instance-url} to the same URL on all pods —
 * the routing logic becomes a no-op and adds zero overhead.
 *
 * <p>Disabled gracefully when Redis is not on the classpath.
 */
@Slf4j
@Component
@ConditionalOnBean(RedisConnectionFactory.class)
public class StorageLocationRegistry {

    public static final String KEY_PREFIX = "platform:storage:location:";
    /**
     * TTL for location entries. Files don't change location, but we use TTL
     * as a safety net so that entries from deleted files eventually clean up.
     * 7 days is safe — the delete path explicitly removes the key.
     */
    private static final long TTL_DAYS = 7;

    private final StringRedisTemplate redis;
    private final RestTemplate        restTemplate;

    @Value("${storage.instance-url:http://storage-manager:8096}")
    private String selfUrl;

    @Autowired
    public StorageLocationRegistry(@Nullable StringRedisTemplate redis,
                                   RestTemplate restTemplate) {
        this.redis       = redis;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    void init() {
        if (redis != null) {
            log.info("[StorageRegistry] This instance will register file locations in Redis as: {}", selfUrl);
        } else {
            log.warn("[StorageRegistry] Redis not available — location registry disabled. "
                   + "Multiple storage-manager replicas will NOT be able to route retrieve requests correctly.");
        }
    }

    // ── Write side ────────────────────────────────────────────────────────────

    /**
     * Register that this pod holds the physical bytes for the given SHA-256 key.
     * Called immediately after a successful write to local storage.
     */
    public void register(String sha256) {
        if (redis == null || sha256 == null) return;
        try {
            redis.opsForValue().set(KEY_PREFIX + sha256, selfUrl,
                    java.time.Duration.ofDays(TTL_DAYS));
        } catch (Exception e) {
            log.debug("[StorageRegistry] register({}) failed — retrieve routing may break: {}", sha256, e.getMessage());
        }
    }

    /**
     * Remove the location entry when a file is deleted.
     * Prevents stale routing entries pointing to non-existent files.
     */
    public void deregister(String sha256) {
        if (redis == null || sha256 == null) return;
        try {
            redis.delete(KEY_PREFIX + sha256);
        } catch (Exception e) {
            log.debug("[StorageRegistry] deregister({}) failed: {}", sha256, e.getMessage());
        }
    }

    // ── Read side ─────────────────────────────────────────────────────────────

    /**
     * Return {@code true} if the file is on THIS pod (or no routing info exists).
     * {@code false} means the caller should proxy to {@link #getOwnerUrl(String)}.
     */
    public boolean isLocal(String sha256) {
        String owner = getOwnerUrl(sha256);
        return owner == null || owner.equals(selfUrl) || owner.isBlank();
    }

    /**
     * Fetch the physical bytes from the owning pod via an internal HTTP call.
     * Returns {@code null} if the owning pod is unreachable.
     */
    @Nullable
    public byte[] proxyRetrieve(String sha256) {
        String ownerUrl = getOwnerUrl(sha256);
        if (ownerUrl == null) return null;
        try {
            log.debug("[StorageRegistry] Proxying retrieve sha256={} → {}", sha256.substring(0, 12), ownerUrl);
            return restTemplate.getForObject(
                    ownerUrl + "/api/v1/storage/retrieve-by-key/" + sha256, byte[].class);
        } catch (Exception e) {
            log.warn("[StorageRegistry] Proxy retrieve failed for sha256={} from {}: {}",
                    sha256.substring(0, 12), ownerUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Return the URL of the instance holding the file, or {@code null} if unknown.
     */
    @Nullable
    public String getOwnerUrl(String sha256) {
        if (redis == null || sha256 == null) return null;
        try {
            return redis.opsForValue().get(KEY_PREFIX + sha256);
        } catch (Exception e) {
            log.debug("[StorageRegistry] getOwnerUrl lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /** This instance's own URL — for visibility in health/info endpoints. */
    public String getSelfUrl() { return selfUrl; }
}
