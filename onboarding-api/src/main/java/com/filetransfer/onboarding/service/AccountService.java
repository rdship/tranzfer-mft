package com.filetransfer.onboarding.service;

import com.filetransfer.onboarding.dto.request.CreateAccountRequest;
import com.filetransfer.onboarding.dto.request.UpdateAccountRequest;
import com.filetransfer.onboarding.dto.response.AccountResponse;
import com.filetransfer.onboarding.messaging.AccountEventPublisher;
import com.filetransfer.shared.dto.AccountCreatedEvent;
import com.filetransfer.shared.dto.AccountUpdatedEvent;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.entity.User;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.entity.SftpServerInstance;
import com.filetransfer.shared.repository.SftpServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final TransferAccountRepository accountRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountEventPublisher eventPublisher;
    private final SftpServerInstanceRepository serverInstanceRepository;

    @Value("${file-transfer.sftp-home-base:/data/sftp}")
    private String sftpHomeBase;

    @Value("${file-transfer.ftp-home-base:/data/ftp}")
    private String ftpHomeBase;

    @Transactional
    public AccountResponse createAccount(String ownerEmail, CreateAccountRequest request) {
        if (accountRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already taken: " + request.getUsername());
        }

        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + ownerEmail));

        String homeDir = resolveHomeDir(request.getProtocol(), request.getUsername());
        provisionHomeDir(homeDir);

        Map<String, Boolean> permissions = request.getPermissions() != null
                ? request.getPermissions()
                : Map.of("read", true, "write", true, "delete", false);

        TransferAccount account = TransferAccount.builder()
                .user(owner)
                .protocol(request.getProtocol())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .publicKey(request.getPublicKey())
                .homeDir(homeDir)
                .permissions(permissions)
                .serverInstance(request.getServerInstance())
                .build();

        accountRepository.save(account);

        eventPublisher.publishAccountCreated(AccountCreatedEvent.builder()
                .accountId(account.getId())
                .protocol(account.getProtocol())
                .username(account.getUsername())
                .homeDir(account.getHomeDir())
                .serverInstance(account.getServerInstance())
                .build());

        return toResponse(account);
    }

    public AccountResponse getAccount(UUID accountId) {
        return toResponse(findById(accountId));
    }

    @Transactional
    public AccountResponse updateAccount(UUID accountId, UpdateAccountRequest request) {
        TransferAccount account = findById(accountId);

        if (request.getActive() != null) {
            account.setActive(request.getActive());
        }
        if (request.getNewPassword() != null) {
            account.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        }
        if (request.getPublicKey() != null) {
            account.setPublicKey(request.getPublicKey());
        }
        if (request.getPermissions() != null) {
            account.setPermissions(request.getPermissions());
        }
        if (request.getServerInstance() != null) {
            account.setServerInstance(request.getServerInstance());
        }

        accountRepository.save(account);

        eventPublisher.publishAccountUpdated(AccountUpdatedEvent.builder()
                .accountId(account.getId())
                .username(account.getUsername())
                .active(account.isActive())
                .build());

        return toResponse(account);
    }

    @Transactional
    public void deleteAccount(UUID accountId) {
        TransferAccount account = findById(accountId);
        account.setActive(false);
        accountRepository.save(account);

        eventPublisher.publishAccountUpdated(AccountUpdatedEvent.builder()
                .accountId(account.getId())
                .username(account.getUsername())
                .active(false)
                .build());
    }

    private TransferAccount findById(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + id));
    }

    private String resolveHomeDir(Protocol protocol, String username) {
        String base = protocol == Protocol.SFTP ? sftpHomeBase : ftpHomeBase;
        return base + "/" + username;
    }

    private void provisionHomeDir(String homeDir) {
        try {
            Files.createDirectories(Paths.get(homeDir));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create home directory: " + homeDir, e);
        }
    }

    private AccountResponse toResponse(TransferAccount account) {
        String instructions;
        if (account.getProtocol() == Protocol.SFTP) {
            instructions = buildSftpInstructions(account);
        } else {
            instructions = "Connect via: ftp <host> (port 21), user: " + account.getUsername();
        }

        return AccountResponse.builder()
                .id(account.getId())
                .protocol(account.getProtocol())
                .username(account.getUsername())
                .homeDir(account.getHomeDir())
                .permissions(account.getPermissions())
                .active(account.isActive())
                .serverInstance(account.getServerInstance())
                .createdAt(account.getCreatedAt())
                .connectionInstructions(instructions)
                .build();
    }

    private String buildSftpInstructions(TransferAccount account) {
        if (account.getServerInstance() != null) {
            var server = serverInstanceRepository.findByInstanceId(account.getServerInstance());
            if (server.isPresent()) {
                SftpServerInstance s = server.get();
                String host = s.getClientConnectionHost();
                int port = s.getClientConnectionPort();
                return String.format("Connect via: sftp -P %d %s@%s (server: %s)",
                        port, account.getUsername(), host, s.getName());
            }
        }
        return "Connect via: sftp -P 2222 " + account.getUsername() + "@<host>";
    }
}
