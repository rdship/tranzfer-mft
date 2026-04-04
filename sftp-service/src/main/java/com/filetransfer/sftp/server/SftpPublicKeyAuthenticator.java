package com.filetransfer.sftp.server;

import com.filetransfer.sftp.service.CredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.security.PublicKey;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SftpPublicKeyAuthenticator implements PublickeyAuthenticator {

    private final CredentialService credentialService;

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) {
        String ip = session.getClientAddress() != null
                ? session.getClientAddress().toString()
                : "unknown";

        return credentialService.findAccount(username).map(account -> {
            if (account.getPublicKey() == null) return false;
            try {
                // Parse the stored authorized_keys line and compare
                List<AuthorizedKeyEntry> entries = AuthorizedKeyEntry.readAuthorizedKeys(
                        new StringReader(account.getPublicKey()), true);
                for (AuthorizedKeyEntry entry : entries) {
                    PublicKey stored = entry.resolvePublicKey(null, null, null);
                    if (stored != null && stored.equals(key)) {
                        log.info("SFTP publickey auth success: username={}", username);
                        return true;
                    }
                }
            } catch (Exception e) {
                log.warn("Error parsing public key for user {}: {}", username, e.getMessage());
            }
            log.warn("SFTP publickey auth failed: username={} ip={}", username, ip);
            return false;
        }).orElse(false);
    }
}
