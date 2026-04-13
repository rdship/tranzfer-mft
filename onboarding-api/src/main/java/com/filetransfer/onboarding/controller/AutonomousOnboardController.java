package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.security.Roles;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AUTONOMOUS PARTNER ONBOARDING
 *
 * Zero-touch partner setup. When an unknown client connects:
 * 1. DETECT   — capture SSH capabilities, IP, client version
 * 2. PROVISION — auto-create account + security profile
 * 3. LEARN    — observe first 5 transfers, detect file patterns
 * 4. OPTIMIZE — auto-create flow based on learned patterns
 * 5. COMPLETE — partner is fully onboarded, no admin intervention
 *
 * Admin can review/approve or the system auto-completes after learning phase.
 */
@RestController @RequestMapping("/api/v1/auto-onboard") @RequiredArgsConstructor @Slf4j
@PreAuthorize(Roles.OPERATOR)
public class AutonomousOnboardController {

    private final AutoOnboardSessionRepository sessionRepo;
    private final TransferAccountRepository accountRepo;
    private final FileFlowRepository flowRepo;
    private final FileTransferRecordRepository recordRepo;
    private final UserRepository userRepo;
    private final TrackIdGenerator trackIdGenerator;

    /**
     * Step 1: DETECT — called when unknown client connects.
     * SFTP service calls this when auth fails for unknown username.
     */
    @PostMapping("/detect")
    public ResponseEntity<Map<String, Object>> detect(@RequestBody Map<String, Object> body) {
        String sourceIp = (String) body.get("sourceIp");
        String clientVersion = (String) body.get("clientVersion");
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) body.getOrDefault("capabilities", Map.of());

        // Check if we already have a session for this IP
        AutoOnboardSession existing = sessionRepo.findBySourceIpAndPhaseNot(sourceIp, "COMPLETE").orElse(null);
        if (existing != null) {
            return ResponseEntity.ok(Map.of("sessionId", existing.getId(),
                    "username", existing.getGeneratedUsername(),
                    "phase", existing.getPhase(),
                    "message", "Session already exists. Use the generated credentials."));
        }

        // Auto-generate username from IP
        String username = "auto_" + sourceIp.replace(".", "_").replace(":", "_") + "_" + System.currentTimeMillis() % 10000;
        String tempPassword = "Auto" + generateSecureToken(8) + "!1";

        // Detect best security profile from capabilities
        String recommendedProfile = detectSecurityProfile(capabilities);

        AutoOnboardSession session = AutoOnboardSession.builder()
                .sourceIp(sourceIp).clientVersion(clientVersion)
                .capabilities(capabilities)
                .generatedUsername(username).tempPassword(tempPassword)
                .phase("DETECTED")
                .build();
        sessionRepo.save(session);

        log.info("AUTO-ONBOARD: New partner detected from {} (client: {})", sourceIp, clientVersion);

        // Auto-provision account
        provision(session);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId", session.getId());
        resp.put("phase", session.getPhase());
        resp.put("username", username);
        resp.put("tempPassword", tempPassword);
        resp.put("protocol", "SFTP");
        resp.put("securityProfile", recommendedProfile);
        resp.put("message", "Account auto-created. Partner can connect immediately. System is learning file patterns.");
        resp.put("nextSteps", List.of(
                "Partner connects with generated credentials",
                "System observes first 5 file transfers",
                "Optimal processing flow auto-created",
                "Admin notified for review (optional)"));
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * Step 2: PROVISION — auto-create account and directories.
     */
    private void provision(AutoOnboardSession session) {
        // Create system user if needed
        User systemUser = userRepo.findAll().stream().findFirst()
                .orElse(User.builder().email("system@tranzfer.local")
                        .passwordHash(new BCryptPasswordEncoder().encode("system")).build());

        TransferAccount account = TransferAccount.builder()
                .user(systemUser).protocol(Protocol.SFTP)
                .username(session.getGeneratedUsername())
                .passwordHash(new BCryptPasswordEncoder().encode(session.getTempPassword()))
                .homeDir("/data/sftp/" + session.getGeneratedUsername())
                .build();
        accountRepo.save(account);

        session.setPhase("ACCOUNT_CREATED");
        sessionRepo.save(session);

        log.info("AUTO-ONBOARD: Account provisioned for {} (from {})",
                session.getGeneratedUsername(), session.getSourceIp());
    }

    /**
     * Step 3: LEARN — observe file transfers and detect patterns.
     * Called by RoutingEngine after each transfer from auto-onboarded account.
     */
    @PostMapping("/learn")
    public ResponseEntity<Map<String, Object>> learn(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String filename = body.get("filename");
        String fileType = body.get("fileType");
        String fileSize = body.get("fileSize");

        AutoOnboardSession session = sessionRepo.findByGeneratedUsername(username).orElse(null);
        if (session == null) return ResponseEntity.notFound().build();

        // Track observed patterns
        List<String> patterns = session.getDetectedPatterns() != null ?
                new ArrayList<>(session.getDetectedPatterns()) : new ArrayList<>();
        patterns.add(filename);
        session.setDetectedPatterns(patterns);
        session.setFilesObserved(session.getFilesObserved() + 1);
        session.setPhase("LEARNING");
        sessionRepo.save(session);

        log.info("AUTO-ONBOARD: Learning — {} sent file '{}' (observed: {})",
                username, filename, session.getFilesObserved());

        // After 5 files, auto-create flow
        if (session.getFilesObserved() >= 5) {
            autoCreateFlow(session);
        }

        return ResponseEntity.ok(Map.of("filesObserved", session.getFilesObserved(),
                "phase", session.getPhase(), "patternsDetected", patterns.size()));
    }

    /**
     * Step 4: OPTIMIZE — auto-create processing flow based on learned patterns.
     */
    private void autoCreateFlow(AutoOnboardSession session) {
        List<String> patterns = session.getDetectedPatterns();
        if (patterns == null || patterns.isEmpty()) return;

        // Analyze file patterns
        List<FileFlow.FlowStep> steps = new ArrayList<>();
        int order = 0;

        // Detect common extensions
        Map<String, Long> extensions = patterns.stream()
                .map(f -> f.contains(".") ? f.substring(f.lastIndexOf('.')) : "")
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()));

        String dominantExt = extensions.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");

        // Build flow based on detected patterns
        if (dominantExt.equals(".pgp") || dominantExt.equals(".gpg")) {
            steps.add(FileFlow.FlowStep.builder().type("DECRYPT_PGP").config(Map.of()).order(order++).build());
        }
        if (dominantExt.equals(".gz") || dominantExt.equals(".gzip")) {
            steps.add(FileFlow.FlowStep.builder().type("DECOMPRESS_GZIP").config(Map.of()).order(order++).build());
        }
        if (dominantExt.equals(".zip")) {
            steps.add(FileFlow.FlowStep.builder().type("DECOMPRESS_ZIP").config(Map.of()).order(order++).build());
        }

        // Always add screening for new partners
        steps.add(FileFlow.FlowStep.builder().type("SCREEN").config(Map.of()).order(order++).build());

        // Build filename pattern regex from observed files
        String filenameRegex = buildFilenameRegex(patterns);

        // Add route step
        steps.add(FileFlow.FlowStep.builder().type("ROUTE").config(Map.of()).order(order++).build());

        String flowName = "auto-" + session.getGeneratedUsername();
        FileFlow flow = FileFlow.builder()
                .name(flowName)
                .description("Auto-generated flow from partner behavior analysis (" + session.getFilesObserved() + " files observed)")
                .filenamePattern(filenameRegex)
                .sourcePath("/inbox")
                .priority(50)
                .steps(steps)
                .build();
        flowRepo.save(flow);

        session.setAutoFlowId(flow.getId().toString());
        session.setPhase("COMPLETE");
        session.setCompletedAt(Instant.now());
        sessionRepo.save(session);

        log.info("AUTO-ONBOARD COMPLETE: {} — auto-flow '{}' with {} steps (pattern: {})",
                session.getGeneratedUsername(), flowName, steps.size(), filenameRegex);
    }

    /** Build regex from observed filenames */
    private String buildFilenameRegex(List<String> filenames) {
        Set<String> exts = filenames.stream()
                .filter(f -> f.contains("."))
                .map(f -> f.substring(f.lastIndexOf('.')))
                .collect(Collectors.toSet());

        if (exts.size() == 1) return ".*\\" + exts.iterator().next() + "$";
        if (exts.size() <= 3) return ".*(" + String.join("|", exts.stream().map(e -> "\\" + e).collect(Collectors.toList())) + ")$";
        return ".*"; // too many extensions, match everything
    }

    /** Detect best security profile */
    private String detectSecurityProfile(Map<String, Object> capabilities) {
        if (capabilities.containsKey("ciphers")) {
            String ciphers = capabilities.get("ciphers").toString();
            if (ciphers.contains("aes256-gcm")) return "FIPS-140-2";
            if (ciphers.contains("chacha20")) return "MODERN";
        }
        return "DEFAULT";
    }

    // === Admin endpoints ===

    @GetMapping("/sessions")
    public List<AutoOnboardSession> listSessions(@RequestParam(required = false) String phase) {
        if (phase != null) return sessionRepo.findByPhaseOrderByCreatedAtDesc(phase);
        return sessionRepo.findAll();
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<AutoOnboardSession> getSession(@PathVariable UUID id) {
        return sessionRepo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sessions/{id}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable UUID id) {
        AutoOnboardSession s = sessionRepo.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        s.setPhase("COMPLETE");
        s.setCompletedAt(Instant.now());
        sessionRepo.save(s);
        return ResponseEntity.ok(Map.of("status", "APPROVED", "username", s.getGeneratedUsername()));
    }

    /** Auto-complete sessions that have been learning for >24h */
    @Scheduled(fixedDelay = 3600000)
    @SchedulerLock(name = "onboarding_autoComplete", lockAtLeastFor = "PT50M", lockAtMostFor = "PT2H")
    @PreAuthorize("permitAll()")
    public void autoComplete() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        sessionRepo.findByPhaseOrderByCreatedAtDesc("LEARNING").stream()
                .filter(s -> s.getCreatedAt().isBefore(cutoff))
                .forEach(s -> {
                    if (s.getFilesObserved() >= 3) autoCreateFlow(s);
                    else { s.setPhase("COMPLETE"); s.setCompletedAt(Instant.now()); sessionRepo.save(s); }
                });
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<AutoOnboardSession> all = sessionRepo.findAll();
        return Map.of(
                "totalSessions", all.size(),
                "completed", all.stream().filter(s -> "COMPLETE".equals(s.getPhase())).count(),
                "learning", all.stream().filter(s -> "LEARNING".equals(s.getPhase())).count(),
                "detected", all.stream().filter(s -> "DETECTED".equals(s.getPhase())).count(),
                "avgFilesToLearn", all.stream().mapToInt(AutoOnboardSession::getFilesObserved).average().orElse(0)
        );
    }

    private String generateSecureToken(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }
}
