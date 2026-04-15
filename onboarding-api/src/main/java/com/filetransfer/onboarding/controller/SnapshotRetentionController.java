package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.core.PlatformSetting;
import com.filetransfer.shared.enums.Environment;
import com.filetransfer.shared.repository.transfer.FlowStepSnapshotRepository;
import com.filetransfer.shared.repository.core.PlatformSettingRepository;
import com.filetransfer.shared.security.Roles;
import com.filetransfer.onboarding.scheduler.SnapshotRetentionJob;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Management API for FlowStepSnapshot retention policy.
 *
 * <pre>
 * GET  /api/snapshot-retention            — current policy + stats  (VIEWER)
 * PUT  /api/snapshot-retention            — update retention days   (ADMIN)
 * POST /api/snapshot-retention/purge-now  — immediate purge         (ADMIN)
 * </pre>
 */
@RestController
@RequestMapping("/api/snapshot-retention")
@RequiredArgsConstructor
public class SnapshotRetentionController {

    private static final String SETTING_KEY = "snapshot.retention.days";

    private final FlowStepSnapshotRepository snapshotRepo;
    private final PlatformSettingRepository  settingRepo;
    private final SnapshotRetentionJob       retentionJob;

    @GetMapping
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<Map<String, Object>> getStatus() {
        int days   = retentionJob.readRetentionDays();
        long total = snapshotRepo.count();
        long eligible = days > 0
                ? snapshotRepo.countByCreatedAtBefore(Instant.now().minus(days, ChronoUnit.DAYS))
                : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retentionDays",   days);
        result.put("enabled",         days > 0);
        result.put("totalSnapshots",  total);
        result.put("eligibleForPurge", eligible);
        result.put("lastPurgeAt",     retentionJob.getLastPurgeAt().get());
        result.put("lastPurgeCount",  retentionJob.getLastPurgeCount().get());
        result.put("nextPurgeSchedule", "02:00 UTC daily");
        return ResponseEntity.ok(result);
    }

    @PutMapping
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Map<String, Object>> updateRetentionDays(
            @RequestBody Map<String, Integer> body) {

        Integer days = body.get("retentionDays");
        if (days == null || days < 0 || days > 3650) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "retentionDays must be 0–3650 (0 = disabled)");
        }

        PlatformSetting setting = settingRepo
                .findBySettingKeyAndEnvironmentAndServiceName(SETTING_KEY, Environment.PROD, "GLOBAL")
                .orElseGet(() -> PlatformSetting.builder()
                        .settingKey(SETTING_KEY)
                        .environment(Environment.PROD)
                        .serviceName("GLOBAL")
                        .dataType("INTEGER")
                        .category("MAINTENANCE")
                        .description("Days to retain FlowStepSnapshots before auto-purge (0 = disabled)")
                        .sensitive(false)
                        .active(true)
                        .build());

        setting.setSettingValue(String.valueOf(days));
        settingRepo.save(setting);

        return ResponseEntity.ok(Map.of(
                "retentionDays", days,
                "message", days == 0
                        ? "Retention disabled — snapshots will accumulate indefinitely"
                        : "Retention set to " + days + " days. Next purge at 02:00 UTC."));
    }

    @PostMapping("/purge-now")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Map<String, Object>> purgeNow() {
        int days = retentionJob.readRetentionDays();
        if (days <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Retention is disabled (retentionDays=0). Enable it first.");
        }
        long purged = retentionJob.purgeNow();
        return ResponseEntity.ok(Map.of(
                "purged",  purged,
                "message", purged == 0
                        ? "No snapshots older than " + days + " days — nothing purged"
                        : purged + " snapshot(s) purged (older than " + days + " days)"));
    }
}
