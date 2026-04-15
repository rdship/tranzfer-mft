package com.filetransfer.shared.vfs;

import com.filetransfer.shared.repository.vfs.VfsIntentRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Graceful shutdown handler for VFS intents.
 *
 * <p>On pod shutdown, aborts all PENDING intents created by this pod.
 * This prevents the recovery job from trying to replay operations
 * that were intentionally abandoned during a clean shutdown.
 *
 * <p>If the pod crashes (SIGKILL), intents remain PENDING and are
 * handled by {@link VfsIntentRecoveryJob} after the staleness threshold.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VfsShutdownHandler {

    private final VfsIntentRepository intentRepository;

    private String podId;

    @PostConstruct
    void init() {
        this.podId = System.getenv().getOrDefault("HOSTNAME",
                "unknown-" + ProcessHandle.current().pid());
    }

    @PreDestroy
    @Transactional
    public void abortPendingIntents() {
        int aborted = intentRepository.abortByPod(podId);
        if (aborted > 0) {
            log.info("VFS shutdown: aborted {} pending intent(s) for pod {}", aborted, podId);
        }
    }
}
