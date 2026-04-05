package com.filetransfer.as2.service;

import com.filetransfer.shared.entity.As2Partnership;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.entity.User;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

/**
 * Auto-provisions TransferAccount records for AS2 trading partners.
 * When an AS2 message arrives from a known partner, we need a TransferAccount
 * to bridge into the RoutingEngine (which expects TransferAccount for flow matching,
 * routing evaluation, and audit logging).
 *
 * The AS2 partner's partnerAs2Id becomes the TransferAccount username,
 * and protocol = AS2. Home directory is created under as2.home-base.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class As2AccountService {

    private final TransferAccountRepository accountRepository;
    private final UserRepository userRepository;

    @Value("${as2.home-base:/data/as2}")
    private String as2HomeBase;

    /**
     * Get or create a TransferAccount for the given AS2 partnership.
     * Creates the account and home directory if they don't exist yet.
     */
    @Transactional
    public TransferAccount getOrCreateAccount(As2Partnership partnership) {
        String username = "as2_" + partnership.getPartnerAs2Id().toLowerCase()
                .replaceAll("[^a-z0-9_-]", "_");

        Optional<TransferAccount> existing = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.AS2);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Find or create system user for AS2 accounts
        User systemUser = findOrCreateAs2SystemUser();

        // Create home directory: /data/as2/{partnerAs2Id}/
        String homeDir = as2HomeBase + "/" + partnership.getPartnerAs2Id();
        createDirectories(homeDir);

        TransferAccount account = TransferAccount.builder()
                .user(systemUser)
                .protocol(Protocol.AS2)
                .username(username)
                .passwordHash("AS2_CERTIFICATE_AUTH")
                .homeDir(homeDir)
                .permissions(Map.of("read", true, "write", true, "delete", false))
                .active(true)
                .build();

        account = accountRepository.save(account);
        log.info("Auto-provisioned AS2 TransferAccount: username={} homeDir={} partnerId={}",
                username, homeDir, partnership.getPartnerAs2Id());
        return account;
    }

    private User findOrCreateAs2SystemUser() {
        Optional<User> existing = userRepository.findByEmail("system@as2.internal");
        if (existing.isPresent()) return existing.get();

        User user = new User();
        user.setEmail("system@as2.internal");
        user.setPasswordHash("SYSTEM_NO_LOGIN");
        user.setRole(com.filetransfer.shared.enums.UserRole.SYSTEM);
        return userRepository.save(user);
    }

    private void createDirectories(String homeDir) {
        try {
            Path inboxDir = Paths.get(homeDir, "inbox");
            Path outboxDir = Paths.get(homeDir, "outbox");
            Path archiveDir = Paths.get(homeDir, "archive");
            Files.createDirectories(inboxDir);
            Files.createDirectories(outboxDir);
            Files.createDirectories(archiveDir);
        } catch (Exception e) {
            log.warn("Could not create AS2 home directories at {}: {}", homeDir, e.getMessage());
        }
    }
}
