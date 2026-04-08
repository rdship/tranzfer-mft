package com.filetransfer.dmz.tunnel;

import com.filetransfer.dmz.health.BackendHealthChecker;
import com.filetransfer.tunnel.control.ControlMessage;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tunnel-aware backend health checker that probes backends through the DMZ tunnel
 * instead of making direct TCP connections across the zone boundary.
 * <p>
 * The parent class uses NIO SocketChannel for TCP connect probes, which requires
 * direct network access to backend hosts. This subclass replaces that mechanism
 * with CONTROL_REQ frames sent through the multiplexed tunnel. The internal-side
 * tunnel handler performs the actual TCP probe and returns the result.
 * <p>
 * Since the parent's probe logic ({@code probeBackend}, {@code probeAllBackends},
 * {@code BackendState}) is entirely private, this class maintains its own health state
 * and overrides all public query/lifecycle methods.
 * <p>
 * The parent's {@link #registerBackend}/{@link #removeBackend} still work — we intercept
 * registrations to track backend endpoints in our own map and delegate to super for
 * compatibility.
 */
@Slf4j
public class TunnelBackendHealthChecker extends BackendHealthChecker {

    private static final long PROBE_TIMEOUT_MS = 5_000;

    // ── Our own health state (parallel to parent's private state) ────────

    private record BackendEndpoint(String name, String host, int port) {}

    private final ConcurrentHashMap<String, BackendEndpoint> endpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BackendHealth> healthSnapshots = new ConcurrentHashMap<>();

    private final TunnelAcceptor tunnelAcceptor;
    private final int checkIntervalSeconds;
    private final int timeoutSeconds;
    private final int unhealthyThreshold;
    private final int healthyThreshold;

    // Mutable counters per backend (not exposed, used for threshold logic)
    private final ConcurrentHashMap<String, int[]> consecutiveCounts = new ConcurrentHashMap<>();
    // consecutiveCounts value: [consecutiveFailures, consecutiveSuccesses, totalChecks, totalFailures]

    private volatile ScheduledExecutorService tunnelScheduler;
    private volatile boolean tunnelRunning;

    /**
     * @param checkIntervalSeconds interval between probe cycles
     * @param timeoutSeconds       timeout per health probe
     * @param unhealthyThreshold   consecutive failures before marking unhealthy
     * @param healthyThreshold     consecutive successes before marking healthy
     * @param tunnelAcceptor       the tunnel acceptor (handler resolved lazily)
     */
    public TunnelBackendHealthChecker(int checkIntervalSeconds, int timeoutSeconds,
                                       int unhealthyThreshold, int healthyThreshold,
                                       TunnelAcceptor tunnelAcceptor) {
        super(checkIntervalSeconds, timeoutSeconds, unhealthyThreshold, healthyThreshold);
        this.tunnelAcceptor = tunnelAcceptor;
        this.checkIntervalSeconds = checkIntervalSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.unhealthyThreshold = unhealthyThreshold;
        this.healthyThreshold = healthyThreshold;
    }

    // ── Registration (track endpoints ourselves) ─────────────────────────

    @Override
    public void registerBackend(String name, String host, int port) {
        super.registerBackend(name, host, port);
        endpoints.put(name, new BackendEndpoint(name, host, port));
        consecutiveCounts.put(name, new int[]{0, 0, 0, 0});
        healthSnapshots.put(name, new BackendHealth(
                name, host, port, Status.UNKNOWN, 0, 0,
                null, Instant.now(), 0, 0, null));
    }

    @Override
    public void removeBackend(String name) {
        super.removeBackend(name);
        endpoints.remove(name);
        consecutiveCounts.remove(name);
        healthSnapshots.remove(name);
    }

    // ── Health queries (read from our tunnel-probed state) ───────────────

    @Override
    public boolean isHealthy(String name) {
        BackendHealth health = healthSnapshots.get(name);
        return health != null && health.status() == Status.HEALTHY;
    }

    @Override
    public BackendHealth getHealth(String name) {
        return healthSnapshots.get(name);
    }

    @Override
    public Map<String, BackendHealth> getAllHealth() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(healthSnapshots));
    }

    @Override
    public List<String> getUnhealthyBackends() {
        return healthSnapshots.values().stream()
                .filter(h -> h.status() == Status.UNHEALTHY)
                .map(BackendHealth::name)
                .toList();
    }

    // ── Lifecycle (our own scheduler, skip parent's TCP probes) ──────────

    @Override
    public synchronized void start() {
        // Do NOT call super.start() — parent would start TCP socket probes
        if (tunnelRunning) {
            log.warn("TunnelBackendHealthChecker is already running");
            return;
        }

        tunnelScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tunnel-health-checker");
            t.setDaemon(true);
            return t;
        });

        tunnelScheduler.scheduleAtFixedRate(
                this::probeAllViaTunnel, 0, checkIntervalSeconds, TimeUnit.SECONDS);
        tunnelRunning = true;

        log.info("TunnelBackendHealthChecker started: interval={}s, timeout={}s, "
                        + "unhealthyThreshold={}, healthyThreshold={}, backends={}",
                checkIntervalSeconds, timeoutSeconds, unhealthyThreshold, healthyThreshold,
                endpoints.size());
    }

    @Override
    public synchronized void shutdown() {
        if (!tunnelRunning) return;
        tunnelRunning = false;

        if (tunnelScheduler != null) {
            tunnelScheduler.shutdown();
            try {
                if (!tunnelScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    tunnelScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                tunnelScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            tunnelScheduler = null;
        }
        log.info("TunnelBackendHealthChecker stopped");
    }

    // ── Tunnel-based probe logic ─────────────────────────────────────────

    /**
     * Probes all registered backends by sending CONTROL_REQ health-probe requests
     * through the tunnel. The internal-side handler performs the actual TCP connect
     * check and returns the result.
     */
    private void probeAllViaTunnel() {
        if (!tunnelRunning) return;

        for (BackendEndpoint endpoint : endpoints.values()) {
            try {
                probeViaTunnel(endpoint);
            } catch (Exception e) {
                log.error("Unexpected error probing backend [{}] via tunnel: {}",
                        endpoint.name, e.getMessage(), e);
            }
        }
    }

    /**
     * Sends a health probe for a single backend through the tunnel and updates state.
     */
    private void probeViaTunnel(BackendEndpoint endpoint) {
        Instant now = Instant.now();
        boolean success = false;
        String error = null;

        DmzTunnelHandler th = tunnelAcceptor.getHandler();
        if (th == null || !th.isConnected()) {
            error = "tunnel_disconnected";
        } else {
            try {
                String body = String.format(
                        "{\"host\":\"%s\",\"port\":%d,\"timeoutSeconds\":%d}",
                        endpoint.host, endpoint.port, timeoutSeconds);

                ControlMessage request = ControlMessage.request(
                        UUID.randomUUID().toString(),
                        "GET",
                        "/internal/health-probe",
                        Map.of("X-Target-Host", endpoint.host,
                                "X-Target-Port", String.valueOf(endpoint.port)),
                        body.getBytes(StandardCharsets.UTF_8)
                );

                ControlMessage response = th
                        .sendControlRequest(request, PROBE_TIMEOUT_MS)
                        .orTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .join();

                success = response.getStatusCode() == 200;
                if (!success) {
                    error = "probe_returned_" + response.getStatusCode();
                    if (response.getBody() != null) {
                        error += ": " + new String(response.getBody(), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                Throwable cause = e instanceof CompletionException ? e.getCause() : e;
                if (cause instanceof TimeoutException) {
                    error = "tunnel_probe_timeout_" + PROBE_TIMEOUT_MS + "ms";
                } else {
                    error = "tunnel_probe_error: " + cause.getMessage();
                }
            }
        }

        // Update health state
        int[] counts = consecutiveCounts.get(endpoint.name);
        if (counts == null) return; // backend removed concurrently

        counts[2]++; // totalChecks

        BackendHealth prev = healthSnapshots.get(endpoint.name);
        Status prevStatus = prev != null ? prev.status() : Status.UNKNOWN;
        Status newStatus = prevStatus;
        Instant lastStateChange = prev != null ? prev.lastStateChange() : now;

        if (success) {
            counts[0] = 0; // reset consecutiveFailures
            counts[1]++;   // increment consecutiveSuccesses
            if (prevStatus != Status.HEALTHY && counts[1] >= healthyThreshold) {
                newStatus = Status.HEALTHY;
                lastStateChange = now;
                log.info("Backend [{}] is now HEALTHY via tunnel (after {} consecutive successes)",
                        endpoint.name, counts[1]);
            }
        } else {
            counts[1] = 0; // reset consecutiveSuccesses
            counts[0]++;   // increment consecutiveFailures
            counts[3]++;   // totalFailures
            if (prevStatus != Status.UNHEALTHY && counts[0] >= unhealthyThreshold) {
                newStatus = Status.UNHEALTHY;
                lastStateChange = now;
                log.warn("Backend [{}] is now UNHEALTHY via tunnel (after {} consecutive failures, error: {})",
                        endpoint.name, counts[0], error);
            }
        }

        healthSnapshots.put(endpoint.name, new BackendHealth(
                endpoint.name, endpoint.host, endpoint.port, newStatus,
                counts[0], counts[1], now, lastStateChange,
                counts[2], counts[3], error));
    }
}
