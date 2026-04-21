package com.filetransfer.keystore.service;

import com.filetransfer.keystore.entity.ManagedKey;
import com.filetransfer.keystore.repository.ManagedKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Idempotent seed that creates one {@code SSH_HOST_KEY} on a fresh stack so
 * {@code POST /api/v1/keys/rotate} has a callable target at cold boot.
 *
 * <p><b>R134T:</b> closes the one Sprint-6 verification gap flagged in
 * {@code docs/run-reports/R134S-sprint6-stability-verification.md} —
 * {@code keystore.key.rotated} was boot-registered but couldn't be runtime-
 * triggered on the fresh stack because the bootstrap seed contained zero
 * keys. Deleting the legacy RabbitMQ path in Sprint 7 is a one-way door;
 * we want runtime proof of all 4 dual-path events before crossing it.
 *
 * <p><b>Idempotency:</b> gated on {@code countByKeyTypeAndActiveTrue("SSH_HOST_KEY") == 0}.
 * Once any active SSH host key exists (the seed itself on first boot, a
 * rotation's new key on subsequent boots, or a demo-onboard's key), the
 * seed no-ops. No duplicate keys, no drift on restart.
 *
 * <p><b>Runtime gate:</b> {@code platform.keystore.bootstrap-seed.enabled}
 * defaults {@code true} for dev/test cycles. Operators flip to {@code false}
 * in production via env var. The gate is checked inside the event listener
 * (not via {@code @ConditionalOnProperty} at class level) per the AOT-
 * safety retrofit pattern in {@code docs/AOT-SAFETY.md} — the bean is
 * always in the frozen graph; runtime decides whether to act.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeystoreBootstrapSeed {

    private static final String SEED_ALIAS = "bootstrap-test-ssh-host";
    private static final String SEED_OWNER = "keystore-manager";

    private final ManagedKeyRepository keyRepository;
    private final KeyManagementService keyManagementService;

    @Value("${platform.keystore.bootstrap-seed.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!enabled) {
            log.info("[KeystoreBootstrapSeed] platform.keystore.bootstrap-seed.enabled=false — skipping");
            return;
        }
        long existing = keyRepository.countByKeyTypeAndActiveTrue("SSH_HOST_KEY");
        if (existing > 0) {
            log.info("[KeystoreBootstrapSeed] {} active SSH_HOST_KEY(s) already present — no seed needed",
                    existing);
            return;
        }
        try {
            ManagedKey seeded = keyManagementService.generateSshHostKey(SEED_ALIAS, SEED_OWNER);
            log.info("[KeystoreBootstrapSeed] seeded alias={} id={} fingerprint={} — "
                    + "tester may now POST /api/v1/keys/rotate with oldAlias={}",
                    seeded.getAlias(), seeded.getId(), seeded.getFingerprint(), SEED_ALIAS);
        } catch (Exception e) {
            log.warn("[KeystoreBootstrapSeed] seed generation failed (non-fatal; operator can seed manually): {}",
                    e.getMessage(), e);
        }
    }
}
