package com.filetransfer.ftp.server;

import com.filetransfer.ftp.service.CredentialService;
import com.filetransfer.shared.entity.TransferAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.AbstractUserManager;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FtpUserManager extends AbstractUserManager {

    private final CredentialService credentialService;

    @Override
    public User getUserByName(String username) throws FtpException {
        return credentialService.findAccount(username)
                .map(this::toFtpUser)
                .orElse(null);
    }

    @Override
    public String[] getAllUserNames() throws FtpException {
        return new String[0];
    }

    @Override
    public void delete(String username) throws FtpException {
        throw new FtpException("Deletion must go through the Onboarding API");
    }

    @Override
    public void save(User user) throws FtpException {
        throw new FtpException("User management must go through the Onboarding API");
    }

    @Override
    public boolean doesExist(String username) throws FtpException {
        return credentialService.findAccount(username).isPresent();
    }

    @Override
    public User authenticate(Authentication authentication) throws AuthenticationFailedException {
        if (!(authentication instanceof UsernamePasswordAuthentication upAuth)) {
            throw new AuthenticationFailedException("Only username/password authentication is supported");
        }

        String username = upAuth.getUsername();
        String password = upAuth.getPassword();

        if (!credentialService.authenticate(username, password, "unknown")) {
            throw new AuthenticationFailedException("Invalid credentials for user: " + username);
        }

        try {
            return getUserByName(username);
        } catch (FtpException e) {
            throw new AuthenticationFailedException("User lookup failed after successful auth", e);
        }
    }

    private BaseUser toFtpUser(TransferAccount account) {
        BaseUser user = new BaseUser();
        user.setName(account.getUsername());
        user.setHomeDirectory(account.getHomeDir());
        user.setEnabled(account.isActive());
        user.setMaxIdleTime(300);

        List<Authority> authorities = new ArrayList<>();
        authorities.add(new ConcurrentLoginPermission(10, 5));

        Map<String, Boolean> perms = account.getPermissions();
        if (perms != null && Boolean.TRUE.equals(perms.get("write"))) {
            authorities.add(new WritePermission());
        }

        user.setAuthorities(authorities);
        return user;
    }

}
