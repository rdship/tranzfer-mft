package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.Tenant;
import com.filetransfer.shared.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController @RequestMapping("/api/v1/tenants") @RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepo;

    /** Self-service signup */
    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody Map<String, String> body) {
        String slug = body.get("slug").toLowerCase().replaceAll("[^a-z0-9-]", "");
        if (tenantRepo.existsBySlug(slug)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Slug already taken: " + slug));
        }

        Tenant tenant = Tenant.builder()
                .slug(slug).companyName(body.get("companyName"))
                .contactEmail(body.get("email")).plan("TRIAL")
                .trialEndsAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .customDomain(slug + ".tranzfer.io")
                .build();
        tenantRepo.save(tenant);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tenantId", tenant.getId());
        resp.put("slug", slug);
        resp.put("domain", tenant.getCustomDomain());
        resp.put("plan", "TRIAL (30 days)");
        resp.put("sftpHost", slug + ".tranzfer.io");
        resp.put("sftpPort", 2222);
        resp.put("portalUrl", "https://" + slug + ".tranzfer.io/portal");
        resp.put("apiUrl", "https://" + slug + ".tranzfer.io/api");
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping public List<Tenant> list() { return tenantRepo.findAll(); }

    @GetMapping("/{slug}")
    public ResponseEntity<?> get(@PathVariable String slug) {
        return tenantRepo.findBySlugAndActiveTrue(slug)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{slug}/usage")
    public ResponseEntity<Map<String, Object>> usage(@PathVariable String slug) {
        Tenant t = tenantRepo.findBySlugAndActiveTrue(slug).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("slug", slug);
        u.put("plan", t.getPlan());
        u.put("transfersUsed", t.getTransfersUsed());
        u.put("transferLimit", t.getTransferLimit());
        u.put("trialEndsAt", t.getTrialEndsAt());
        u.put("active", t.isActive());
        return ResponseEntity.ok(u);
    }
}
