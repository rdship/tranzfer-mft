package com.filetransfer.dmz.health;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Active health checker for backend services reachable through the DMZ proxy.
 *
 * <p>This is a plain Java class (not a Spring bean) designed to be instantiated and managed
 * by {@link com.filetransfer.dmz.proxy.ProxyManager}. It performs periodic TCP connect probes
 * against registered backends and maintains per-backend health state with configurable
 * thresholds for marking a backend as unhealthy or recovering it back to healthy.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li>Uses NIO {@link SocketChannel} with non-blocking connect + {@link Selector} for
 *       timeout-controlled probes — no thread-per-backend overhead.</li>
 *   <li>A single daemon {@link ScheduledExecutorService} drives all probes; each probe is
 *       isolated so a failure in one backend never affects another.</li>
 *   <li>State is stored in a {@link ConcurrentHashMap} for lock-free reads from Netty I/O threads.</li>
 * </ul>
 *
 * <h3>Integration</h3>
 * Before {@link com.filetransfer.dmz.proxy.TcpProxyServer} connects to a backend, call
 * {@link #isHealthy(String)}. If it returns {@code false}, reject the inbound connection
 * immediately instead of attempting a doomed TCP connect.
 *
 * <h3>Usage example</h3>
 * <pre>{@code
 * BackendHealthChecker checker = new BackendHealthChecker(10, 3, 3, 1);
 * checker.registerBackend("gateway-service", "gateway-service", 2220);
 * checker.registerBackend("ftp-web-service", "ftp-web-service", 8083);
 * checker.start();
 *
 * // In TcpProxyServer pipeline init:
 * if (!checker.isHealthy(mapping.getName())) {
 *     log.warn("Backend [{}] is unhealthy — rejecting connection", mapping.getName());
 *     clientCh.close();
 *     return;
 * }
 *
 * // On shutdown:
 * checker.shutdown();
 * }</pre>
 *
 * @author Roshan Dubey
 * @since 1.0
 */
@Slf4j
public class BackendHealthChecker {

    // ────────────────────────────────────────────────────────────────────────
    //  Health status enum
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Represents the observed health status of a backend.
     */
    public enum Status {
        /** Backend is reachable and accepting TCP connections. */
        HEALTHY,
        /** Backend has failed consecutive probes beyond the unhealthy threshold. */
        UNHEALTHY,
        /** Backend was registered but has not yet been probed. */
        UNKNOWN
    }

    // ────────────────────────────────────────────────────────────────────────
    //  BackendHealth record
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of a single backend's health state at a point in time.
     *
     * @param name               logical name of the backend (matches the port-mapping name)
     * @param host               hostname or IP address being probed
     * @param port               TCP port being probed
     * @param status             current health status
     * @param consecutiveFailures number of consecutive probe failures since last success
     * @param consecutiveSuccesses number of consecutive probe successes since last failure
     * @param lastCheck          timestamp of the most recent probe attempt
     * @param lastStateChange    timestamp of the most recent status transition
     * @param totalChecks        total number of probes executed since registration
     * @param totalFailures      total number of failed probes since registration
     * @param lastError          human-readable description of the most recent probe failure,
     *                           or {@code null} if the last probe succeeded
     */
    public record BackendHealth(
            String name,
            String host,
            int port,
            Status status,
            int consecutiveFailures,
            int consecutiveSuccesses,
            Instant lastCheck,
            Instant lastStateChange,
            long totalChecks,
            long totalFailures,
            String lastError
    ) {}

    // ────────────────────────────────────────────────────────────────────────
    //  Mutable internal state per backend (never exposed directly)
    // ────────────────────────────────────────────────────────────────────────

    private static final class BackendState {
        final String name;
        final String host;
        final int port;

        volatile Status status = Status.UNKNOWN;
        volatile int consecutiveFailures;
        volatile int consecutiveSuccesses;
        volatile Instant lastCheck;
        volatile Instant lastStateChange = Instant.now();
        volatile long totalChecks;
        volatile long totalFailures;
        volatile String lastError;

        BackendState(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        /** Create an immutable snapshot for external consumption. */
        BackendHealth snapshot() {
            return new BackendHealth(
                    name, host, port, status,
                    consecutiveFailures, consecutiveSuccesses,
                    lastCheck, lastStateChange,
                    totalChecks, totalFailures, lastError
            );
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Configuration
    // ────────────────────────────────────────────────────────────────────────

    private final int checkIntervalSeconds;
    private final int timeoutSeconds;
    private final int unhealthyThreshold;
    private final int healthyThreshold;

    // ────────────────────────────────────────────────────────────────────────
    //  Runtime state
    // ────────────────────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, BackendState> backends = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    // ────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new health checker with the specified configuration.
     *
     * @param checkIntervalSeconds interval between probe cycles (default: 10)
     * @param timeoutSeconds       TCP connect timeout per probe (default: 3)
     * @param unhealthyThreshold   consecutive failures before marking unhealthy (default: 3)
     * @param healthyThreshold     consecutive successes before marking healthy (default: 1)
     * @throws IllegalArgumentException if any parameter is non-positive
     */
    public BackendHealthChecker(int checkIntervalSeconds, int timeoutSeconds,
                                int unhealthyThreshold, int healthyThreshold) {
        if (checkIntervalSeconds <= 0) {
            throw new IllegalArgumentException("checkIntervalSeconds must be positive, got: " + checkIntervalSeconds);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive, got: " + timeoutSeconds);
        }
        if (unhealthyThreshold <= 0) {
            throw new IllegalArgumentException("unhealthyThreshold must be positive, got: " + unhealthyThreshold);
        }
        if (healthyThreshold <= 0) {
            throw new IllegalArgumentException("healthyThreshold must be positive, got: " + healthyThreshold);
        }
        this.checkIntervalSeconds = checkIntervalSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.unhealthyThreshold = unhealthyThreshold;
        this.healthyThreshold = healthyThreshold;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Backend registration
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Registers a backend for health monitoring.
     *
     * <p>If the health checker is already running, probes will begin for this backend
     * on the next scheduled cycle. The initial status is {@link Status#UNKNOWN}.
     *
     * @param name logical name (must be unique; typically matches the port-mapping name)
     * @param host hostname or IP address to probe
     * @param port TCP port to probe
     * @throws IllegalArgumentException if a backend with the same name is already registered,
     *                                  or if host is null/blank, or if port is out of range
     */
    public void registerBackend(String name, String host, int port) {
        Objects.requireNonNull(name, "Backend name must not be null");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be null or blank for backend: " + name);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be 1-65535, got: " + port + " for backend: " + name);
        }

        BackendState newState = new BackendState(name, host, port);
        BackendState existing = backends.putIfAbsent(name, newState);
        if (existing != null) {
            throw new IllegalArgumentException("Backend already registered: " + name);
        }
        log.info("Registered backend for health checking: [{}] -> {}:{}", name, host, port);
    }

    /**
     * Removes a backend from health monitoring.
     *
     * <p>If the backend is not registered, this method is a no-op.
     *
     * @param name logical name of the backend to remove
     */
    public void removeBackend(String name) {
        BackendState removed = backends.remove(name);
        if (removed != null) {
            log.info("Removed backend from health checking: [{}]", name);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Health queries
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Returns whether the named backend is considered healthy.
     *
     * <p>A backend is healthy only if its status is explicitly {@link Status#HEALTHY}.
     * Returns {@code false} for {@link Status#UNHEALTHY}, {@link Status#UNKNOWN},
     * or if no backend with the given name is registered.
     *
     * @param name logical name of the backend
     * @return {@code true} if and only if the backend status is {@link Status#HEALTHY}
     */
    public boolean isHealthy(String name) {
        BackendState state = backends.get(name);
        return state != null && state.status == Status.HEALTHY;
    }

    /**
     * Returns the full health snapshot for a single backend.
     *
     * @param name logical name of the backend
     * @return the health snapshot, or {@code null} if the backend is not registered
     */
    public BackendHealth getHealth(String name) {
        BackendState state = backends.get(name);
        return state != null ? state.snapshot() : null;
    }

    /**
     * Returns health snapshots for all registered backends.
     *
     * @return unmodifiable map of backend name to health snapshot
     */
    public Map<String, BackendHealth> getAllHealth() {
        Map<String, BackendHealth> result = new LinkedHashMap<>();
        backends.forEach((name, state) -> result.put(name, state.snapshot()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the names of all backends currently marked as {@link Status#UNHEALTHY}.
     *
     * @return unmodifiable list of unhealthy backend names
     */
    public List<String> getUnhealthyBackends() {
        return backends.values().stream()
                .filter(s -> s.status == Status.UNHEALTHY)
                .map(s -> s.name)
                .collect(Collectors.toUnmodifiableList());
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Starts the scheduled health probing.
     *
     * <p>Probes run on a single daemon thread at fixed intervals. If the checker is
     * already running, this method is a no-op.
     */
    public synchronized void start() {
        if (running) {
            log.warn("BackendHealthChecker is already running");
            return;
        }

        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r, "backend-health-checker");
            t.setDaemon(true);
            return t;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory);
        scheduler.scheduleAtFixedRate(
                this::probeAllBackends,
                0,
                checkIntervalSeconds,
                TimeUnit.SECONDS
        );
        running = true;
        log.info("BackendHealthChecker started: interval={}s, timeout={}s, "
                        + "unhealthyThreshold={}, healthyThreshold={}",
                checkIntervalSeconds, timeoutSeconds, unhealthyThreshold, healthyThreshold);
    }

    /**
     * Stops the scheduled health probing and releases resources.
     *
     * <p>Waits up to 5 seconds for the scheduler to terminate gracefully.
     * If the checker is not running, this method is a no-op.
     */
    public synchronized void shutdown() {
        if (!running) {
            return;
        }
        running = false;

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    log.warn("BackendHealthChecker scheduler did not terminate gracefully — forced shutdown");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        log.info("BackendHealthChecker stopped");
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Probe logic
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Probes every registered backend. Called by the scheduler on each tick.
     * Failures in one backend probe are caught and logged — they never propagate
     * to affect other backends.
     */
    private void probeAllBackends() {
        for (BackendState state : backends.values()) {
            try {
                probeBackend(state);
            } catch (Exception e) {
                // Defensive: this should never happen since probeBackend catches internally,
                // but we guard here to protect the scheduler from cancellation.
                log.error("Unexpected error probing backend [{}]: {}", state.name, e.getMessage(), e);
            }
        }
    }

    /**
     * Performs a single TCP connect probe against a backend and updates its health state.
     *
     * <p>Uses a non-blocking {@link SocketChannel} with a {@link Selector}-based timeout
     * to avoid blocking the scheduler thread beyond the configured timeout.
     *
     * @param state the mutable backend state to probe and update
     */
    private void probeBackend(BackendState state) {
        Instant now = Instant.now();
        state.totalChecks++;
        state.lastCheck = now;

        boolean success = false;
        String error = null;

        SocketChannel channel = null;
        Selector selector = null;
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(false);

            boolean connected = channel.connect(new InetSocketAddress(state.host, state.port));
            if (!connected) {
                selector = Selector.open();
                channel.register(selector, SelectionKey.OP_CONNECT);

                int readyCount = selector.select(timeoutSeconds * 1000L);
                if (readyCount > 0) {
                    try {
                        success = channel.finishConnect();
                    } catch (IOException connectEx) {
                        error = connectEx.getMessage();
                    }
                } else {
                    error = "Connect timed out after " + timeoutSeconds + "s";
                }
            } else {
                // Immediate connect (e.g., localhost)
                success = true;
            }
        } catch (IOException e) {
            error = e.getMessage();
        } finally {
            closeQuietly(selector);
            closeQuietly(channel);
        }

        // Update state
        if (success) {
            onProbeSuccess(state, now);
        } else {
            onProbeFailure(state, now, error);
        }
    }

    /**
     * Handles a successful probe: increments consecutive successes, resets failures,
     * and transitions to {@link Status#HEALTHY} if the healthy threshold is met.
     */
    private void onProbeSuccess(BackendState state, Instant now) {
        state.consecutiveSuccesses++;
        state.consecutiveFailures = 0;
        state.lastError = null;

        if (state.status != Status.HEALTHY && state.consecutiveSuccesses >= healthyThreshold) {
            Status previous = state.status;
            state.status = Status.HEALTHY;
            state.lastStateChange = now;
            log.info("Backend [{}] is now HEALTHY (was {}, after {} consecutive successes)",
                    state.name, previous, state.consecutiveSuccesses);
        }
    }

    /**
     * Handles a failed probe: increments consecutive failures, resets successes,
     * and transitions to {@link Status#UNHEALTHY} if the unhealthy threshold is met.
     */
    private void onProbeFailure(BackendState state, Instant now, String error) {
        state.consecutiveFailures++;
        state.consecutiveSuccesses = 0;
        state.totalFailures++;
        state.lastError = error;

        if (state.status != Status.UNHEALTHY && state.consecutiveFailures >= unhealthyThreshold) {
            Status previous = state.status;
            state.status = Status.UNHEALTHY;
            state.lastStateChange = now;
            log.warn("Backend [{}] is now UNHEALTHY (was {}, after {} consecutive failures, last error: {})",
                    state.name, previous, state.consecutiveFailures, error);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Utilities
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Closes a resource quietly, suppressing any {@link IOException}.
     *
     * @param closeable the resource to close (may be {@code null})
     */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Intentionally suppressed — probe cleanup must not throw
            }
        }
    }
}
