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
import com.filetransfer.shared.dto.FolderDefinition;
import com.filetransfer.shared.entity.FolderTemplate;
import com.filetransfer.shared.entity.ServerInstance;
import com.filetransfer.shared.repository.FolderTemplateRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.repository.UserRepository;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
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
    private final ServerInstanceRepository serverInstanceRepository;
    private final FolderTemplateRepository folderTemplateRepository;
    private final VirtualFileSystem virtualFileSystem;

    @Value("${file-transfer.sftp-home-base:/data/sftp}")
    private String sftpHomeBase;

    @Value("${file-transfer.ftp-home-base:/data/ftp}")
    private String ftpHomeBase;

    @Value("${file-transfer.ftpweb-home-base:/data/ftpweb}")
    private String ftpWebHomeBase;

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

        List<String> folderPaths = resolveFolderPaths(account.getServerInstance());

        // Virtual mode: provision phantom folders (zero disk I/O)
        if ("VIRTUAL".equalsIgnoreCase(account.getStorageMode())) {
            virtualFileSystem.provisionFolders(account.getId(), folderPaths);
        }

        // Publish event (physical mode services create dirs from this; virtual mode ignores)
        eventPublisher.publishAccountCreated(AccountCreatedEvent.builder()
                .accountId(account.getId())
                .protocol(account.getProtocol())
                .username(account.getUsername())
                .homeDir(account.getHomeDir())
                .serverInstance(account.getServerInstance())
                .folderPaths(folderPaths)
                .build());

        return toResponse(account);
    }

    public List<AccountResponse> listAccounts() {
        return accountRepository.findAll().stream().map(this::toResponse).toList();
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
        String base = switch (protocol) {
            case SFTP -> sftpHomeBase;
            case FTP -> ftpHomeBase;
            case FTP_WEB, HTTPS -> ftpWebHomeBase;
            case AS2, AS4 -> sftpHomeBase; // AS2/AS4 accounts use SFTP home base
        };
        return base + "/" + username;
    }

    private List<String> resolveFolderPaths(String serverInstanceId) {
        if (serverInstanceId == null) return List.of();
        return serverInstanceRepository.findByInstanceId(serverInstanceId)
                .filter(si -> si.getFolderTemplate() != null)
                .map(si -> si.getFolderTemplate().getFolders().stream()
                        .map(FolderDefinition::getPath).toList())
                .orElse(List.of());
    }

    private void provisionHomeDir(String homeDir) {
        try {
            Files.createDirectories(Paths.get(homeDir));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create home directory: " + homeDir, e);
        }
    }

    private AccountResponse toResponse(TransferAccount account) {
        String instructions = buildConnectionInstructions(account);

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

    private String buildConnectionInstructions(TransferAccount account) {
        if (account.getServerInstance() != null) {
            var server = serverInstanceRepository.findByInstanceId(account.getServerInstance());
            if (server.isPresent()) {
                ServerInstance s = server.get();
                String host = s.getClientConnectionHost();
                int port = s.getClientConnectionPort();
                return switch (account.getProtocol()) {
                    case SFTP -> String.format("Connect via: sftp -P %d %s@%s (server: %s)",
                            port, account.getUsername(), host, s.getName());
                    case FTP -> String.format("Connect via: ftp %s %d, user: %s (server: %s)",
                            host, port, account.getUsername(), s.getName());
                    case FTP_WEB, HTTPS -> String.format("Connect via: https://%s:%d/api/files, user: %s (server: %s)",
                            host, port, account.getUsername(), s.getName());
                    case AS2, AS4 -> String.format("Connect via: POST https://%s:%d/as2/receive (partner: %s, server: %s)",
                            host, port, account.getUsername(), s.getName());
                };
            }
        }
        // Fallback: no server instance assigned
        return switch (account.getProtocol()) {
            case SFTP -> "Connect via: sftp -P 2222 " + account.getUsername() + "@<host>";
            case FTP -> "Connect via: ftp <host> (port 21), user: " + account.getUsername();
            case FTP_WEB, HTTPS -> "Connect via: https://<host>:8083/api/files, user: " + account.getUsername();
            case AS2, AS4 -> "Connect via: POST https://<host>:8094/as2/receive, partner: " + account.getUsername();
        };
    }
}
