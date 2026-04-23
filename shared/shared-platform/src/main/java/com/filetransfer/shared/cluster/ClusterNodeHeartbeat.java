package com.filetransfer.shared.cluster;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every service pod heartbeats its liveness row into the {@code platform_pod_heartbeat}
 * PG table (V97). Replaces {@code RedisServiceRegistry} per doc 01 of the
 * external-dep retirement plan.
 *
 * <p><b>Heartbeat frequency:</b> every 10s. The reaper (below) marks nodes
 * DEAD when {@code last_heartbeat > now() - 30s}. Total dead-detection
 * latency: ≤ 30s (1 missed heartbeat + 1 reaper tick = 10s + 15s worst case).
 *
 * <p><b>Identity:</b> {@code node_id} = {@code <hostname>:<pid>}. Stable
 * across pod restarts on the same host (hostname changes = new node, which
 * is correct for k8s). {@code service_type} comes from
 * {@code spring.application.name}.
 *
 * <p><b>Graceful shutdown:</b> {@link PreDestroy} marks this node's row as
 * {@code DRAINING} so the admin UI shows the right state without waiting
 * for the reaper. On container restart, the first heartbeat flips the
 * status back to ACTIVE.
 *
 * <p><b>Concurrency:</b> the heartbeat itself is single-threaded (one row
 * per node). The reaper is shedlock-gated so only one node runs it per
 * tick regardless of service-type — the reaper queries all service types
 * and marks their stale rows DEAD indiscriminately.
 *
 * <p>Sprint 0 scope: bean exists but writes to a table no reader queries
 * yet. Readers (admin UI /cluster page, Platform Sentinel) switch over
 * in Sprint 4 of the retirement plan.
 */
@Slf4j
@Component
public class ClusterNodeHeartbeat {

    private final JdbcTemplate jdbc;
    /**
     * R134AF — every JDBC write goes through its own REQUIRES_NEW
     * transaction so Spring commits regardless of the Hikari pool's
     * autoCommit setting (JPA forces {@code autoCommit=false} on shared
     * pool connections; R134AE proved the INSERT returned rowsAffected=1
     * yet the row was invisible to a follow-up read — classic
     * uncommitted-then-released-to-pool symptom). See R134AE runtime
     * report §2 for the narrowing evidence.
     */
    private final TransactionTemplate txTemplate;
    private final AtomicInteger heartbeatTickCount = new AtomicInteger(0);

    @Value("${spring.application.name:unknown}")
    private String serviceType;

    @Value("${server.port:-1}")
    private int serverPort;

    @Value("${cluster.host:${spring.application.name:localhost}}")
    private String host;

    @Value("${spiffe.trust-domain:filetransfer.io}")
    private String spiffeTrustDomain;

    @Value("${platform.cluster.heartbeat-enabled:true}")
    private boolean enabled;

    private String nodeId;
    private String spiffeId;
    private String url;
    private Instant startedAt;

    public ClusterNodeHeartbeat(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[ClusterHeartbeat] disabled via platform.cluster.heartbeat-enabled=false");
            return;
        }
        String hostname = resolveHostname();
        String pid = resolvePid();
        this.nodeId = hostname + ":" + pid;
        this.spiffeId = "spiffe://" + spiffeTrustDomain + "/" + serviceType;
        this.url = "http://" + host + ":" + serverPort;
        this.startedAt = Instant.now();

        PoolProbe probe = probePool();
        try {
            int rows = writeHeartbeat();
            long liveRows = countLiveRows();
            log.info("[R134AF][ClusterHeartbeat] Registered node={} serviceType={} url={} rowsAffected={} liveRowsNow={} hikari.autoCommit={} jdbcUrl={}",
                    nodeId, serviceType, url, rows, liveRows, probe.autoCommit, probe.jdbcUrl);
            if (liveRows < 1) {
                // REQUIRES_NEW tx should have committed; a still-zero count would be surprising.
                log.warn("[R134AF][ClusterHeartbeat] liveRowsNow={} after REQUIRES_NEW commit — investigate",
                        liveRows);
            }
        } catch (Exception e) {
            log.warn("[R134AF][ClusterHeartbeat] Initial heartbeat FAILED node={} jdbcUrl={} cause={}",
                    nodeId, probe.jdbcUrl, e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "PT10S")
    public void heartbeat() {
        if (!enabled || nodeId == null) return;
        try {
            int rows = writeHeartbeat();
            int tick = heartbeatTickCount.incrementAndGet();
            // First few ticks at INFO so operators see the heartbeat loop is live;
            // after that, silent-success to avoid log spam.
            if (tick <= 3) {
                log.info("[R134AF][ClusterHeartbeat] tick={} rowsAffected={} node={} (silent after tick 3)",
                        tick, rows, nodeId);
            }
            if (rows != 1) {
                log.warn("[R134AF][ClusterHeartbeat] heartbeat write returned rowsAffected={} (expected 1) node={} tick={}",
                        rows, nodeId, tick);
            }
        } catch (Exception e) {
            log.warn("[ClusterHeartbeat] heartbeat write failed (will retry in 10s): {}", e.getMessage());
        }
    }

    /**
     * R134AF — writes run inside a REQUIRES_NEW transaction so commit is
     * explicit, unaffected by caller's transaction state or the pool's
     * autoCommit setting. Fixes the R134AE-observed "rowsAffected=1 but
     * liveRowsNow=0" symptom.
     */
    private int writeHeartbeat() {
        Integer result = txTemplate.execute(status -> jdbc.update("""
            INSERT INTO platform_pod_heartbeat (node_id, service_type, host, port, url, spiffe_id,
                                        last_heartbeat, started_at, status)
            VALUES (?, ?, ?, ?, ?, ?, now(), ?, 'ACTIVE')
            ON CONFLICT (node_id) DO UPDATE
                SET service_type   = EXCLUDED.service_type,
                    host           = EXCLUDED.host,
                    port           = EXCLUDED.port,
                    url            = EXCLUDED.url,
                    spiffe_id      = EXCLUDED.spiffe_id,
                    last_heartbeat = now(),
                    status         = 'ACTIVE'
            """, nodeId, serviceType, host, serverPort, url, spiffeId,
            Timestamp.from(startedAt)));
        return result == null ? 0 : result;
    }

    private long countLiveRows() {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM platform_pod_heartbeat", Long.class);
            return n == null ? -1 : n;
        } catch (Exception e) {
            return -1;
        }
    }

    private PoolProbe probePool() {
        if (jdbc.getDataSource() == null) return new PoolProbe("unknown", "unknown");
        try (Connection c = jdbc.getDataSource().getConnection()) {
            return new PoolProbe(c.getMetaData().getURL(), String.valueOf(c.getAutoCommit()));
        } catch (Exception e) {
            return new PoolProbe("unresolved:" + e.getMessage(), "unknown");
        }
    }

    private record PoolProbe(String jdbcUrl, String autoCommit) {}

    /**
     * Reaper — marks stale nodes DEAD. ShedLock gates across replicas so
     * exactly ONE reaper runs per tick. The reaper is service-type-agnostic
     * — any service's heartbeat going stale triggers the DEAD transition
     * by any other service's reaper.
     *
     * <p>Dead-detection window: 30s. Heartbeat interval is 10s so a node
     * must miss 3 heartbeats before it's marked DEAD — tolerates brief GC
     * pauses, network hiccups, scheduler delays.
     *
     * <p><b>R134t — split-brain caveat:</b> a HEALTHY node that briefly
     * loses its PG connection (not the network to clients) will miss a
     * heartbeat and may be marked DEAD by the reaper. When PG reconnects,
     * the node's next heartbeat flips it back to ACTIVE. In the gap,
     * Platform Sentinel's auto-heal might take unnecessary action (e.g.
     * traffic steering away from the node).
     *
     * <p>For stricter correctness, a quorum-based heartbeat (the node must
     * convince N/2+1 observers it's alive before being considered ACTIVE)
     * is the textbook fix. Trade-off: higher implementation complexity
     * and more chatty cross-service traffic. We defer that until (a) we
     * run > 5 replicas of a single service type AND (b) we observe
     * spurious DEAD flags in production. Until then, the 30s window is
     * long enough that transient PG blips rarely trip it — and the
     * self-healing (node's next heartbeat re-registers ACTIVE) is cheap.
     *
     * <p>See docs/rd/2026-04-R134-external-dep-retirement/01-redis-retirement.md
     * "Hard consumer 3 — Service registry" for the design rationale.
     */
    @Scheduled(fixedDelayString = "PT15S")
    @SchedulerLock(name = "pod_heartbeat_reaper",
                   lockAtMostFor = "PT1M",
                   lockAtLeastFor = "PT5S")
    public void markDeadNodes() {
        if (!enabled) return;
        try {
            Integer marked = txTemplate.execute(status -> jdbc.update("""
                UPDATE platform_pod_heartbeat
                   SET status = 'DEAD'
                 WHERE status = 'ACTIVE'
                   AND last_heartbeat < now() - INTERVAL '30 seconds'
                """));
            if (marked != null && marked > 0) {
                log.info("[ClusterHeartbeat] Marked {} node(s) DEAD (missed heartbeats > 30s)", marked);
            }
        } catch (Exception e) {
            log.warn("[ClusterHeartbeat] Reaper failed (will retry in 15s): {}", e.getMessage());
        }
    }

    /**
     * Flag ourselves DRAINING on graceful shutdown so admin UI and
     * auto-heal see the intentional-stop state immediately, without
     * waiting for heartbeat-staleness.
     */
    @PreDestroy
    public void onShutdown() {
        if (!enabled || nodeId == null) return;
        try {
            txTemplate.execute(status -> jdbc.update("""
                UPDATE platform_pod_heartbeat
                   SET status = 'DRAINING', last_heartbeat = now()
                 WHERE node_id = ?
                """, nodeId));
            log.info("[ClusterHeartbeat] Marked {} DRAINING on shutdown", nodeId);
        } catch (Exception e) {
            log.debug("[ClusterHeartbeat] Shutdown update failed (PG may be down already): {}",
                    e.getMessage());
        }
    }

    /**
     * @return this node's identifier (for other beans that need to identify
     * the current pod — e.g., lock holder IDs)
     */
    public String getNodeId() {
        return nodeId;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host-" + System.currentTimeMillis();
        }
    }

    private static String resolvePid() {
        String rt = ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"
        return rt.contains("@") ? rt.substring(0, rt.indexOf('@')) : rt;
    }
}
