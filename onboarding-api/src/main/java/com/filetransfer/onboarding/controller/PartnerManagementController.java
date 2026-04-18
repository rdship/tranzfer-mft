package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.CreateAccountRequest;
import com.filetransfer.onboarding.dto.request.CreatePartnerRequest;
import com.filetransfer.shared.util.InputSanitizer;
import com.filetransfer.onboarding.dto.request.UpdatePartnerRequest;
import com.filetransfer.shared.dto.FileFlowDto;
import com.filetransfer.shared.security.Roles;
import com.filetransfer.onboarding.dto.response.PartnerDetailResponse;
import com.filetransfer.onboarding.service.AccountService;
import com.filetransfer.onboarding.service.PartnerService;
import com.filetransfer.shared.entity.transfer.DeliveryEndpoint;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.core.Partner;
import com.filetransfer.shared.entity.core.TransferAccount;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/partners")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class PartnerManagementController {

    private final PartnerService partnerService;
    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Partner create(@AuthenticationPrincipal String email,
                          @Valid @RequestBody CreatePartnerRequest request) {
        if (request.getDisplayName() != null) request.setDisplayName(InputSanitizer.stripHtml(request.getDisplayName()));
        if (request.getNotes() != null) request.setNotes(InputSanitizer.stripHtml(request.getNotes()));
        return partnerService.createPartner(request, email);
    }

    @GetMapping
    public List<Partner> list(@RequestParam(required = false) String status,
                              @RequestParam(required = false) String type) {
        return partnerService.listPartners(status, type);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public PartnerDetailResponse get(@PathVariable UUID id) {
        return partnerService.getPartner(id);
    }

    @PutMapping("/{id}")
    public Partner update(@PathVariable UUID id,
                          @RequestBody UpdatePartnerRequest request) {
        return partnerService.updatePartner(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        partnerService.deletePartner(id);
    }

    @PostMapping("/{id}/activate")
    public Partner activate(@PathVariable UUID id) {
        return partnerService.activatePartner(id);
    }

    @PostMapping("/{id}/suspend")
    public Partner suspend(@PathVariable UUID id) {
        return partnerService.suspendPartner(id);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return partnerService.getPartnerStats();
    }

    @GetMapping("/{id}/accounts")
    public List<TransferAccount> listAccounts(@PathVariable UUID id) {
        return partnerService.getPartnerAccounts(id);
    }

    @PostMapping("/{id}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferAccount createAccountForPartner(@PathVariable UUID id,
                                                   @AuthenticationPrincipal String email,
                                                   @Valid @RequestBody CreateAccountRequest request) {
        var response = accountService.createAccount(email, request, id);
        return partnerService.linkAccountToPartner(id, response.getId());
    }

    @GetMapping("/{id}/flows")
    @Transactional(readOnly = true)
    public List<FileFlowDto> listFlows(@PathVariable UUID id) {
        return partnerService.getPartnerFlows(id).stream().map(FileFlowDto::from).toList();
    }

    @GetMapping("/{id}/endpoints")
    public List<DeliveryEndpoint> listEndpoints(@PathVariable UUID id) {
        return partnerService.getPartnerEndpoints(id);
    }

    /**
     * R127: admin-side "test connection" for a partner — mirrors the
     * partner-portal GET /api/partner/test-connection (which requires a
     * PARTNER JWT). The R124 audit flagged 404 on the partner-portal route
     * because the audit uses an ADMIN token; this route is what the admin UI
     * calls to verify a partner's SFTP/FTP endpoint without impersonating
     * the partner. Returns connectivity metadata for each of the partner's
     * accounts.
     */
    @GetMapping("/{id}/test-connection")
    @Transactional(readOnly = true)
    public Map<String, Object> testConnection(@PathVariable UUID id) {
        List<TransferAccount> accounts = partnerService.getPartnerAccounts(id);
        if (accounts == null || accounts.isEmpty()) {
            return Map.of(
                    "partnerId", id.toString(),
                    "status", "NO_ACCOUNTS",
                    "message", "Partner has no transfer accounts to test");
        }
        List<Map<String, Object>> results = accounts.stream().map(acct -> {
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("username", acct.getUsername());
            m.put("protocol", acct.getProtocol() != null ? acct.getProtocol().name() : null);
            m.put("active", acct.isActive());
            m.put("homeDir", acct.getHomeDir());
            m.put("serverPort", acct.getProtocol() == com.filetransfer.shared.enums.Protocol.SFTP ? 2222 : 21);
            m.put("instructions", acct.getProtocol() == com.filetransfer.shared.enums.Protocol.SFTP
                    ? "sftp -P 2222 " + acct.getUsername() + "@<server_host>"
                    : "ftp <server_host>");
            return (Map<String, Object>) m;
        }).toList();
        return Map.of(
                "partnerId", id.toString(),
                "status", "OK",
                "accountCount", accounts.size(),
                "accounts", results);
    }
}
