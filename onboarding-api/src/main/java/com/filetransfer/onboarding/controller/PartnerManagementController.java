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
import com.filetransfer.shared.entity.DeliveryEndpoint;
import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.entity.Partner;
import com.filetransfer.shared.entity.TransferAccount;
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
}
