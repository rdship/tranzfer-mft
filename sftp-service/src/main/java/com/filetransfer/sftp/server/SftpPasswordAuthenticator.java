package com.filetransfer.sftp.server;

import com.filetransfer.sftp.service.CredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SftpPasswordAuthenticator implements PasswordAuthenticator {

    private final CredentialService credentialService;

    @Override
    public boolean authenticate(String username, String password, ServerSession session) {
        String ip = session.getClientAddress() != null
                ? session.getClientAddress().toString()
                : "unknown";
        log.info("SFTP password auth attempt: username={} ip={}", username, ip);
        return credentialService.authenticatePassword(username, password, ip);
    }
}
