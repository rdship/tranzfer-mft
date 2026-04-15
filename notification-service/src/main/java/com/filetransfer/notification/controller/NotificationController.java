package com.filetransfer.notification.controller;

import com.filetransfer.notification.dto.TestNotificationRequest;
import com.filetransfer.notification.service.NotificationDispatcher;
import com.filetransfer.notification.service.TemplateRenderer;
import com.filetransfer.shared.entity.integration.NotificationLog;
import com.filetransfer.shared.entity.integration.NotificationRule;
import com.filetransfer.shared.entity.integration.NotificationTemplate;
import com.filetransfer.shared.repository.integration.NotificationLogRepository;
import com.filetransfer.shared.repository.integration.NotificationRuleRepository;
import com.filetransfer.shared.repository.integration.NotificationTemplateRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class NotificationController {

    private final NotificationTemplateRepository templateRepository;
    private final NotificationRuleRepository ruleRepository;
    private final NotificationLogRepository logRepository;
    private final NotificationDispatcher dispatcher;
    private final TemplateRenderer templateRenderer;

    // =========================================================================
    // Templates
    // =========================================================================

    @GetMapping("/templates")
    public List<NotificationTemplate> listTemplates() {
        return templateRepository.findAll();
    }

    @GetMapping("/templates/{id}")
    public ResponseEntity<NotificationTemplate> getTemplate(@PathVariable UUID id) {
        return templateRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/templates")
    @PreAuthorize(Roles.ADMIN)
    public NotificationTemplate createTemplate(@Valid @RequestBody NotificationTemplate template) {
        return templateRepository.save(template);
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<NotificationTemplate> updateTemplate(@PathVariable UUID id,
                                                                @Valid @RequestBody NotificationTemplate update) {
        return templateRepository.findById(id)
                .map(existing -> {
                    existing.setName(update.getName());
                    existing.setChannel(update.getChannel());
                    existing.setSubjectTemplate(update.getSubjectTemplate());
                    existing.setBodyTemplate(update.getBodyTemplate());
                    existing.setEventType(update.getEventType());
                    existing.setActive(update.isActive());
                    return ResponseEntity.ok(templateRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/templates/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Void> deleteTemplate(@PathVariable UUID id) {
        if (templateRepository.existsById(id)) {
            templateRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // =========================================================================
    // Rules
    // =========================================================================

    @GetMapping("/rules")
    public List<NotificationRule> listRules() {
        return ruleRepository.findAll();
    }

    @GetMapping("/rules/{id}")
    public ResponseEntity<NotificationRule> getRule(@PathVariable UUID id) {
        return ruleRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/rules")
    @PreAuthorize(Roles.ADMIN)
    public NotificationRule createRule(@Valid @RequestBody NotificationRule rule) {
        return ruleRepository.save(rule);
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<NotificationRule> updateRule(@PathVariable UUID id,
                                                        @Valid @RequestBody NotificationRule update) {
        return ruleRepository.findById(id)
                .map(existing -> {
                    existing.setName(update.getName());
                    existing.setEventTypePattern(update.getEventTypePattern());
                    existing.setChannel(update.getChannel());
                    existing.setRecipients(update.getRecipients());
                    existing.setEnabled(update.isEnabled());
                    existing.setConditions(update.getConditions());
                    return ResponseEntity.ok(ruleRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        if (ruleRepository.existsById(id)) {
            ruleRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // =========================================================================
    // Logs
    // =========================================================================

    @GetMapping("/logs")
    public Page<NotificationLog> listLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return logRepository.findAllByOrderBySentAtDesc(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt")));
    }

    @GetMapping("/logs/recent")
    public List<NotificationLog> recentLogs() {
        return logRepository.findTop100ByOrderBySentAtDesc();
    }

    @GetMapping("/logs/by-track-id/{trackId}")
    public List<NotificationLog> logsByTrackId(@PathVariable String trackId) {
        return logRepository.findByTrackId(trackId);
    }

    // =========================================================================
    // Test / Health
    // =========================================================================

    @PostMapping("/test")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<NotificationLog> sendTestNotification(
            @Valid @RequestBody TestNotificationRequest request) {
        String body = request.getBody();
        String subject = request.getSubject();

        // Render template variables if provided
        if (request.getVariables() != null && !request.getVariables().isEmpty()) {
            if (body != null) {
                body = templateRenderer.render(body, request.getVariables());
            }
            if (subject != null) {
                subject = templateRenderer.render(subject, request.getVariables());
            }
        }

        if (body == null) {
            body = "Test notification from TranzFer MFT at " + Instant.now();
        }
        if (subject == null) {
            subject = "TranzFer MFT Test Notification";
        }

        NotificationLog result = dispatcher.sendTestNotification(
                request.getChannel(), request.getRecipient(), subject, body);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        long sentLast24h = logRepository.countByStatusAndSentAtAfter(
                "SENT", Instant.now().minus(24, ChronoUnit.HOURS));
        long failedLast24h = logRepository.countByStatusAndSentAtAfter(
                "FAILED", Instant.now().minus(24, ChronoUnit.HOURS));

        return Map.of(
                "status", "UP",
                "service", "notification-service",
                "templates", templateRepository.count(),
                "rules", ruleRepository.count(),
                "sent24h", sentLast24h,
                "failed24h", failedLast24h
        );
    }
}
