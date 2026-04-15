package com.filetransfer.onboarding.dryrun;

import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Dry run endpoint — simulate a flow without writing to storage or delivering to partners.
 *
 * <pre>
 * POST /api/flows/{flowId}/dry-run
 *   Body (optional): { "filename": "payroll-march.xml", "fileSizeBytes": 45000 }
 *   → DryRunResult { steps[], wouldSucceed, issues[], totalEstimatedMs }
 * </pre>
 */
@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
@PreAuthorize(Roles.VIEWER)
public class DryRunController {

    private final FileFlowRepository flowRepository;
    private final DryRunService dryRunService;

    @PostMapping("/{flowId}/dry-run")
    public ResponseEntity<DryRunResult> dryRun(
            @PathVariable UUID flowId,
            @RequestBody(required = false) Map<String, Object> body) {

        FileFlow flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Flow not found: " + flowId));

        String filename = body != null ? (String) body.getOrDefault("filename", null) : null;
        DryRunResult result = dryRunService.dryRun(flow, filename);
        return ResponseEntity.ok(result);
    }
}
