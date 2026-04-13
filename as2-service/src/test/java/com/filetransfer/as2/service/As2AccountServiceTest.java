package com.filetransfer.as2.service;

import com.filetransfer.shared.entity.integration.As2Partnership;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.entity.core.User;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.enums.UserRole;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class As2AccountServiceTest {

    private TransferAccountRepository accountRepository;
    private UserRepository userRepository;
    private As2AccountService service;

    private As2Partnership partnership;

    @BeforeEach
    void setUp() throws Exception {
        accountRepository = mock(TransferAccountRepository.class);
        userRepository = mock(UserRepository.class);
        service = new As2AccountService(accountRepository, userRepository);

        // Set the @Value field via reflection
        Field homeBaseField = As2AccountService.class.getDeclaredField("as2HomeBase");
        homeBaseField.setAccessible(true);
        homeBaseField.set(service, "/data/as2");

        partnership = new As2Partnership();
        partnership.setPartnerAs2Id("PARTNER-A");
        partnership.setPartnerName("Partner A Corp");
        partnership.setOurAs2Id("OUR_ID");
    }

    @Test
    void getOrCreateAccount_existingAccount_returnsExistingWithoutSaving() {
        TransferAccount existing = TransferAccount.builder()
                .username("as2_partner-a")
                .protocol(Protocol.AS2)
                .homeDir("/data/as2/PARTNER-A")
                .active(true)
                .build();

        when(accountRepository.findByUsernameAndProtocolAndActiveTrue("as2_partner-a", Protocol.AS2))
                .thenReturn(Optional.of(existing));

        TransferAccount result = service.getOrCreateAccount(partnership);

        assertSame(existing, result);
        verify(accountRepository, never()).save(any(TransferAccount.class));
    }

    @Test
    void getOrCreateAccount_newAccount_createsWithAs2Protocol() {
        when(accountRepository.findByUsernameAndProtocolAndActiveTrue(anyString(), eq(Protocol.AS2)))
                .thenReturn(Optional.empty());

        User systemUser = new User();
        systemUser.setEmail("system@as2.internal");
        systemUser.setRole(UserRole.SYSTEM);
        when(userRepository.findByEmail("system@as2.internal")).thenReturn(Optional.of(systemUser));
        when(accountRepository.save(any(TransferAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferAccount result = service.getOrCreateAccount(partnership);

        assertNotNull(result);
        assertEquals(Protocol.AS2, result.getProtocol());
        assertTrue(result.getUsername().startsWith("as2_"),
                "Username should start with 'as2_' prefix");
        assertTrue(result.isActive());

        verify(accountRepository).save(any(TransferAccount.class));
    }

    @Test
    void getOrCreateAccount_usernameSanitization_replacesSpecialChars() {
        As2Partnership specialPartnership = new As2Partnership();
        specialPartnership.setPartnerAs2Id("PARTNER@A.COM");

        when(accountRepository.findByUsernameAndProtocolAndActiveTrue("as2_partner_a_com", Protocol.AS2))
                .thenReturn(Optional.empty());

        User systemUser = new User();
        systemUser.setEmail("system@as2.internal");
        when(userRepository.findByEmail("system@as2.internal")).thenReturn(Optional.of(systemUser));
        when(accountRepository.save(any(TransferAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferAccount result = service.getOrCreateAccount(specialPartnership);

        // "@" and "." are not [a-z0-9_-] so they get replaced with "_"
        assertEquals("as2_partner_a_com", result.getUsername());
    }

    @Test
    void getOrCreateAccount_systemUserCreation_createsWhenNotExists() {
        when(accountRepository.findByUsernameAndProtocolAndActiveTrue(anyString(), eq(Protocol.AS2)))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("system@as2.internal")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return u;
        });
        when(accountRepository.save(any(TransferAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        service.getOrCreateAccount(partnership);

        // Verify a new system user was created with the expected attributes
        verify(userRepository).save(argThat(user ->
                "system@as2.internal".equals(user.getEmail()) &&
                "SYSTEM_NO_LOGIN".equals(user.getPasswordHash()) &&
                user.getRole() == UserRole.SYSTEM
        ));
    }

    @Test
    void getOrCreateAccount_systemUserReuse_returnsExistingUser() {
        User existingSystemUser = new User();
        existingSystemUser.setEmail("system@as2.internal");
        existingSystemUser.setRole(UserRole.SYSTEM);

        when(accountRepository.findByUsernameAndProtocolAndActiveTrue(anyString(), eq(Protocol.AS2)))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("system@as2.internal")).thenReturn(Optional.of(existingSystemUser));
        when(accountRepository.save(any(TransferAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        service.getOrCreateAccount(partnership);

        // System user should NOT have been saved again
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getOrCreateAccount_newAccount_setsHomeDirFromPartnerAs2Id() {
        when(accountRepository.findByUsernameAndProtocolAndActiveTrue(anyString(), eq(Protocol.AS2)))
                .thenReturn(Optional.empty());

        User systemUser = new User();
        systemUser.setEmail("system@as2.internal");
        when(userRepository.findByEmail("system@as2.internal")).thenReturn(Optional.of(systemUser));
        when(accountRepository.save(any(TransferAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferAccount result = service.getOrCreateAccount(partnership);

        assertEquals("/data/as2/PARTNER-A", result.getHomeDir());
    }

    @Test
    void getOrCreateAccount_newAccount_setsPasswordHashAsCertAuth() {
        when(accountRepository.findByUsernameAndProtocolAndActiveTrue(anyString(), eq(Protocol.AS2)))
                .thenReturn(Optional.empty());

        User systemUser = new User();
        systemUser.setEmail("system@as2.internal");
        when(userRepository.findByEmail("system@as2.internal")).thenReturn(Optional.of(systemUser));
        when(accountRepository.save(any(TransferAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferAccount result = service.getOrCreateAccount(partnership);

        assertEquals("AS2_CERTIFICATE_AUTH", result.getPasswordHash());
    }
}
