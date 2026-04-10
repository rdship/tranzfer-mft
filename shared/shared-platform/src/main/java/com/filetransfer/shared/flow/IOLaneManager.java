package com.filetransfer.shared.flow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Manages I/O concurrency permits per lane. Each lane has a configurable
 * number of permits; callers must acquire before starting I/O and release
 * when done. This prevents any single traffic class from overwhelming
 * disk or network I/O.
 */
@Component @Slf4j
public class IOLaneManager {

    @Value("${flow.lanes.realtime.permits:8}")
    private int realtimePermits;

    @Value("${flow.lanes.bulk.permits:4}")
    private int bulkPermits;

    @Value("${flow.lanes.background.permits:2}")
    private int backgroundPermits;

    private final EnumMap<IOLane, Semaphore> lanes = new EnumMap<>(IOLane.class);

    @PostConstruct
    public void init() {
        lanes.put(IOLane.REALTIME, new Semaphore(realtimePermits, true));
        lanes.put(IOLane.BULK, new Semaphore(bulkPermits, true));
        lanes.put(IOLane.BACKGROUND, new Semaphore(backgroundPermits, true));
        log.info("I/O lanes initialized: REALTIME={}, BULK={}, BACKGROUND={}",
                realtimePermits, bulkPermits, backgroundPermits);
    }

    /** Acquire a permit for the given lane. Blocks until available. */
    public void acquire(IOLane lane) throws InterruptedException {
        lanes.get(lane).acquire();
    }

    /** Try to acquire a permit within the given timeout. Returns false if unavailable. */
    public boolean tryAcquire(IOLane lane, long timeout, TimeUnit unit) throws InterruptedException {
        return lanes.get(lane).tryAcquire(timeout, unit);
    }

    /** Release a permit back to the lane. */
    public void release(IOLane lane) {
        lanes.get(lane).release();
    }

    /** Get current available permits for a lane (for metrics). */
    public int availablePermits(IOLane lane) {
        return lanes.get(lane).availablePermits();
    }

    /** Get queue length (threads waiting) for a lane (for metrics). */
    public int queueLength(IOLane lane) {
        return lanes.get(lane).getQueueLength();
    }

    /** Snapshot of all lane stats for the health endpoint. */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        for (IOLane lane : IOLane.values()) {
            Semaphore s = lanes.get(lane);
            stats.put(lane.name().toLowerCase() + "Available", s.availablePermits());
            stats.put(lane.name().toLowerCase() + "Waiting", s.getQueueLength());
        }
        return stats;
    }
}
