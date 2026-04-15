package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.core.EncryptionKey;
import com.filetransfer.shared.repository.core.EncryptionKeyRepository;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Encryption Key API — manage PGP/AES keys per transfer account.
 *
 * GET    /api/encryption-keys?accountId=
 * GET    /api/encryption-keys/{id}
 * POST   /api/encryption-keys
 * DELETE /api/encryption-keys/{id}
 */
@RestController
@RequestMapping("/api/encryption-keys")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class EncryptionKeyController {

    private final EncryptionKeyRepository keyRepository;
    private final TransferAccountRepository accountRepository;

    @GetMapping
    public List<EncryptionKey> listForAccount(@RequestParam UUID accountId) {
        return keyRepository.findByAccountIdAndActiveTrue(accountId);
    }

    @GetMapping("/{id}")
    public EncryptionKey get(@PathVariable UUID id) {
        return keyRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EncryptionKey create(@Valid @RequestBody EncryptionKey key) {
        if (key.getAccount() == null || key.getAccount().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "account.id is required");
        }
        var account = accountRepository.findById(key.getAccount().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        key.setId(null);
        key.setAccount(account);
        return keyRepository.save(key);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        EncryptionKey key = get(id);
        key.setActive(false);
        keyRepository.save(key);
    }
}
