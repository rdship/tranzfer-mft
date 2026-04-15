package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.configexport.ConfigBundle;
import com.filetransfer.onboarding.service.configexport.ConfigBundleBuilder;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.repository.transfer.FolderMappingRepository;
import com.filetransfer.shared.repository.core.PartnerRepository;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase-1 configuration export endpoints.
 *
 * <p>Produces a portable, redacted JSON {@link ConfigBundle} representing the
 * current configuration of the platform so an operator can promote it to
 * another environment (test → stage → prod). The export is read-only and
 * admin-only.
 */
@RestController
@RequestMapping("/api/v1/config-export")
@RequiredArgsConstructor
@PreAuthorize(Roles.ADMIN)
@Tag(name = "Configuration Export", description = "Export platform configuration as a portable bundle")
@Slf4j
public class ConfigExportController {

    private final ConfigBundleBuilder bundleBuilder;
    private final PartnerRepository partnerRepo;
    private final TransferAccountRepository accountRepo;
    private final FileFlowRepository flowRepo;
    private final FolderMappingRepository folderMappingRepo;
    private final ServerInstanceRepository serverInstanceRepo;

    /**
     * Returns the set of entity types the builder can export along with the
     * current row count for each. The admin UI uses this to build the
     * "select scope" checkbox tree.
     */
    @GetMapping("/scope")
    public Map<String, Object> getScope() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ConfigBundleBuilder.KEY_PARTNERS, partnerRepo.count());
        out.put(ConfigBundleBuilder.KEY_ACCOUNTS, accountRepo.count());
        out.put(ConfigBundleBuilder.KEY_FLOWS, flowRepo.count());
        out.put(ConfigBundleBuilder.KEY_FOLDER_MAPPINGS, folderMappingRepo.count());
        out.put(ConfigBundleBuilder.KEY_SERVER_INSTANCES, serverInstanceRepo.count());
        return out;
    }

    /**
     * Build and return a {@link ConfigBundle} for the requested entity types.
     *
     * <p>Request body: {@code { "scope": ["partners", "accounts", "flows"] }}
     *
     * @return 200 with a JSON ConfigBundle body and a {@code Content-Disposition}
     *         header that lets browsers treat it as a download; 400 if scope
     *         is empty or contains unknown types; 500 if the builder fails.
     */
    @PostMapping
    public ResponseEntity<ConfigBundle> export(@RequestBody Map<String, Object> request) {
        Object raw = request == null ? null : request.get("scope");
        if (!(raw instanceof Collection<?> rawScope) || rawScope.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Request body must contain a non-empty 'scope' array");
        }

        Set<String> scope = new HashSet<>();
        List<String> unknown = new java.util.ArrayList<>();
        for (Object o : rawScope) {
            String key = String.valueOf(o);
            if (!ConfigBundleBuilder.SUPPORTED_SCOPES.contains(key)) {
                unknown.add(key);
            } else {
                scope.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Unknown scope entries: " + unknown
                            + ". Supported: " + ConfigBundleBuilder.SUPPORTED_SCOPES);
        }

        ConfigBundle bundle;
        try {
            bundle = bundleBuilder.build(scope);
        } catch (Exception e) {
            log.error("ConfigExportController: bundle build failed: {}", e.getMessage(), e);
            throw new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to build configuration bundle: " + e.getMessage(), e);
        }

        String filename = "tranzfer-config-" + Instant.now().toEpochMilli() + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bundle);
    }

    /**
     * Lightweight feature-flag / info endpoint. The UI can ping this to learn
     * which schema version the server speaks and which entity types it can
     * currently export.
     */
    @GetMapping("/info")
    public Map<String, String> getInfo() {
        return Map.of(
                "schemaVersion", "1.0.0",
                "supportedEntities", String.join(",", ConfigBundleBuilder.SUPPORTED_SCOPES)
        );
    }
}
