package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.CreateAccountRequest;
import com.filetransfer.onboarding.dto.request.UpdateAccountRequest;
import com.filetransfer.onboarding.dto.response.AccountResponse;
import com.filetransfer.onboarding.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@AuthenticationPrincipal String email,
                                   @Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(email, request);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return accountService.getAccount(id);
    }

    @PatchMapping("/{id}")
    public AccountResponse update(@PathVariable UUID id,
                                   @RequestBody UpdateAccountRequest request) {
        return accountService.updateAccount(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        accountService.deleteAccount(id);
    }
}
