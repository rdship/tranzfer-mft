package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.AuditLog;
import com.filetransfer.shared.repository.AuditLogRepository;
import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
@Tag(name = "Audit Logs", description = "Audit log browsing and search")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    @GetMapping
    @Operation(summary = "List recent audit logs with optional search/filter")
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String service) {

        List<AuditLog> logs = auditLogRepository.findAll(
                PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "timestamp"))
        ).getContent();

        return logs.stream()
                .filter(l -> matchesSearch(l, search))
                .filter(l -> matchesLevel(l, level))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private boolean matchesSearch(AuditLog log, String search) {
        if (search == null || search.isBlank()) return true;
        String q = search.toLowerCase();
        return (log.getTrackId() != null && log.getTrackId().toLowerCase().contains(q))
                || (log.getAction() != null && log.getAction().toLowerCase().contains(q))
                || (log.getPrincipal() != null && log.getPrincipal().toLowerCase().contains(q))
                || (log.getFilename() != null && log.getFilename().toLowerCase().contains(q))
                || (log.getPath() != null && log.getPath().toLowerCase().contains(q));
    }

    private boolean matchesLevel(AuditLog log, String level) {
        if (level == null || level.isBlank() || "ALL".equalsIgnoreCase(level)) return true;
        if ("ERROR".equalsIgnoreCase(level)) return !log.isSuccess();
        return true;
    }

    private Map<String, Object> toDto(AuditLog l) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", l.getId());
        dto.put("trackId", l.getTrackId());
        dto.put("action", l.getAction());
        dto.put("success", l.isSuccess());
        dto.put("status", l.isSuccess() ? "SUCCESS" : "FAILED");
        dto.put("path", l.getPath());
        dto.put("filename", l.getFilename());
        dto.put("fileSizeBytes", l.getFileSizeBytes());
        dto.put("principal", l.getPrincipal());
        dto.put("ipAddress", l.getIpAddress());
        dto.put("accountUsername", l.getAccount() != null ? l.getAccount().getUsername() : null);
        dto.put("timestamp", l.getTimestamp());
        dto.put("message", l.getErrorMessage());
        return dto;
    }
}
