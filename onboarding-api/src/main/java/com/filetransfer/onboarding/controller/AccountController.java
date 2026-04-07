package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.CreateAccountRequest;
import com.filetransfer.onboarding.dto.request.UpdateAccountRequest;
import com.filetransfer.onboarding.dto.response.AccountResponse;
import com.filetransfer.onboarding.service.AccountService;
import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
@Tag(name = "Transfer Accounts", description = "CRUD for SFTP/FTP/FTP-Web transfer accounts")
public class AccountController {

    private final AccountService accountService;

    @GetMapping
    @Operation(summary = "List all transfer accounts")
    public List<AccountResponse> list() {
        return accountService.listAccounts();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new transfer account")
    public AccountResponse create(@AuthenticationPrincipal String email,
                                   @Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(email, request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get transfer account by ID")
    public AccountResponse get(@PathVariable UUID id) {
        return accountService.getAccount(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a transfer account")
    public AccountResponse fullUpdate(@PathVariable UUID id,
                                       @RequestBody UpdateAccountRequest request) {
        return accountService.updateAccount(id, request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partially update a transfer account")
    public AccountResponse update(@PathVariable UUID id,
                                   @RequestBody UpdateAccountRequest request) {
        return accountService.updateAccount(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a transfer account")
    public void delete(@PathVariable UUID id) {
        accountService.deleteAccount(id);
    }
}
