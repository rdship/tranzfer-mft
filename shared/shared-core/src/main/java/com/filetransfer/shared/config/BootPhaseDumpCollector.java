package com.filetransfer.shared.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * R124 Phase 1 — captures thread dumps at boot-time intervals so we can see
 * where the service spent the seconds. Complements {@link StartupTimingListener}:
 * the listener tells us WHEN each Spring phase finished; the dumps tell us
 * WHAT was blocked during those phases.
 *
 * <p>Captured moments (wall-clock intervals from @PostConstruct firing):
 * <ul>
 *   <li>T+30s  — still in context refresh? bean init? @PostConstruct chain?</li>
 *   <li>T+60s  — SPIRE handshake? Kafka admin? Flyway?</li>
 *   <li>T+120s — past the 120 s Gold mandate; any lingering threads?</li>
 * </ul>
 *
 * <p>Output: {@code /tmp/boot-phase-dumps/<service>-T+<seconds>s.txt} — one
 * file per capture, plain text, greppable, small (typically 20–60KB per dump).
 * When the tester uploads diagnostics, these files explain the timing
 * listener's numbers without requiring a JVM attach.
 *
 * <p>Safety envelope:
 * <ul>
 *   <li>Uses only {@link ThreadMXBean} — no jcmd / attach API; zero external deps.</li>
 *   <li>Writes to {@code /tmp} — container tmpfs; won't fill disk, survives
 *       the capture window (OOM-on-boot scenarios are already protected by
 *       {@code -XX:+HeapDumpOnOutOfMemoryError} from R115).</li>
 *   <li>Gated by {@code platform.boot-dumps.enabled=true} (default on) so a
 *       hot-fix can disable without a rebuild.</li>
 *   <li>Exceptions during capture are swallowed — dump collection must never
 *       fail the service boot.</li>
 *   <li>Executor is shut down after T+120s; zero permanent overhead once the
 *       boot phase is past.</li>
 * </ul>
 *
 * <p>Zero behaviour change: this bean does not participate in the hot path.
 * It only observes.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "platform.boot-dumps.enabled",
        havingValue = "true", matchIfMissing = true)
public class BootPhaseDumpCollector {

    @Value("${spring.application.name:unknown}")
    private String appName;

    /**
     * Dump delays after {@link #start()} in seconds. Tuned so the 3 captures
     * straddle the boot hot spots observed on Silver-baseline R122:
     * services reached ApplicationReadyEvent at 165–220s; dumps at 30/60/120
     * give us pre-, mid-, and post-mandate visibility.
     */
    private static final int[] DUMP_DELAY_SECONDS = {30, 60, 120};

    private static final Path DUMP_DIR = Paths.get("/tmp/boot-phase-dumps");

    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        // @silent-catch-ok: dump-collector MUST NOT fail boot; log and give up if any init fails.
        try {
            Files.createDirectories(DUMP_DIR);
        } catch (IOException e) {
            log.warn("[boot-dump] could not create {} — dumps disabled: {}", DUMP_DIR, e.getMessage());
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "boot-dump-" + appName);
            t.setDaemon(true);
            return t;
        });

        for (int delay : DUMP_DELAY_SECONDS) {
            ScheduledFuture<?> ignored = scheduler.schedule(
                    () -> captureThreadDump(delay),
                    delay,
                    TimeUnit.SECONDS);
        }

        // Shut the scheduler down shortly after the last dump so it doesn't
        // hang around as an idle bean for the life of the service.
        int lastDelay = DUMP_DELAY_SECONDS[DUMP_DELAY_SECONDS.length - 1];
        scheduler.schedule(scheduler::shutdown, lastDelay + 5, TimeUnit.SECONDS);

        log.info("[boot-dump] scheduled thread dumps at T+{}s for service={}",
                java.util.Arrays.toString(DUMP_DELAY_SECONDS), appName);
    }

    /**
     * Capture a full-stack thread dump at the given interval mark. Uses
     * {@link ThreadMXBean#dumpAllThreads(boolean, boolean)} which gives us
     * monitor + synchronizer info — enough to identify lock contention,
     * blocked waits, and CPU-stuck threads.
     */
    private void captureThreadDump(int intervalSeconds) {
        Path file = DUMP_DIR.resolve(appName + "-T+" + intervalSeconds + "s.txt");
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] infos = bean.dumpAllThreads(true, true);

            StringBuilder sb = new StringBuilder(8192);
            sb.append("# boot-phase thread dump\n")
              .append("# service: ").append(appName).append('\n')
              .append("# interval: T+").append(intervalSeconds).append("s\n")
              .append("# captured: ").append(Instant.now()).append('\n')
              .append("# total-threads: ").append(infos.length).append('\n')
              .append("# uptime-ms: ").append(ManagementFactory.getRuntimeMXBean().getUptime()).append('\n')
              .append('\n');

            for (ThreadInfo info : infos) {
                sb.append(info.toString()).append('\n');
            }

            Files.writeString(file, sb.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            log.info("[boot-dump] wrote {} (threads={})", file, infos.length);
        } catch (Exception e) {
            // @silent-catch-ok: dump failure is observability-only; can't abort boot.
            log.warn("[boot-dump] failed to write {}: {}", file, e.getMessage());
        }
    }
}
