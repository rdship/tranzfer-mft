package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PartnerPortalController: ownership checks, login, tracking.
 * Uses real JwtUtil to avoid JDK 25 Mockito restriction on concrete classes.
 */
@ExtendWith(MockitoExtension.class)
class PartnerPortalControllerTest {

    @Mock private TransferAccountRepository accountRepo;
    @Mock private FileTransferRecordRepository transferRepo;
    @Mock private AuditLogRepository auditLogRepo;
    @Mock private FolderMappingRepository mappingRepo;
    @Mock private FlowExecutionRepository flowExecRepo;

    // Real JwtUtil — JDK 25 Mockito cannot mock concrete classes
    private final JwtUtil jwtUtil = new JwtUtil(
            "test-jwt-secret-key-at-least-32-bytes-long-for-hmac-signing", 900000L);

    private PartnerPortalController controller;

    @BeforeEach
    void setUp() {
        controller = new PartnerPortalController(
                accountRepo, transferRepo, auditLogRepo, mappingRepo, flowExecRepo, jwtUtil);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- Ownership check: receipt endpoint denies access when user != record owner (403) ---

    @Test
    void receipt_deniesAccessWhenUserIsNotRecordOwner() {
        setAuthenticatedUser("partnerA");

        TransferAccount ownerAccount = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("partnerB")
                .protocol(Protocol.SFTP)
                .passwordHash("hash")
                .homeDir("/data/sftp/partnerB")
                .build();

        FolderMapping mapping = FolderMapping.builder()
                .sourceAccount(ownerAccount)
                .sourcePath("/inbox")
                .build();

        FileTransferRecord record = FileTransferRecord.builder()
                .trackId("TRZ-123456")
                .originalFilename("test.txt")
                .sourceFilePath("/data/sftp/partnerB/inbox/test.txt")
                .destinationFilePath("/data/sftp/dest/outbox/test.txt")
                .folderMapping(mapping)
                .uploadedAt(Instant.now())
                .build();

        when(transferRepo.findByTrackId("TRZ-123456")).thenReturn(Optional.of(record));

        ResponseEntity<Map<String, Object>> response = controller.receipt("TRZ-123456");

        assertEquals(403, response.getStatusCode().value());
        assertEquals("Access denied", response.getBody().get("error"));
    }

    @Test
    void receipt_allowsAccessWhenUserIsRecordOwner() {
        setAuthenticatedUser("partnerB");

        TransferAccount ownerAccount = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("partnerB")
                .protocol(Protocol.SFTP)
                .passwordHash("hash")
                .homeDir("/data/sftp/partnerB")
                .build();

        FolderMapping mapping = FolderMapping.builder()
                .sourceAccount(ownerAccount)
                .sourcePath("/inbox")
                .build();

        FileTransferRecord record = FileTransferRecord.builder()
                .trackId("TRZ-123456")
                .originalFilename("report.csv")
                .sourceFilePath("/data/sftp/partnerB/inbox/report.csv")
                .destinationFilePath("/data/sftp/dest/outbox/report.csv")
                .folderMapping(mapping)
                .status(FileTransferStatus.DOWNLOADED)
                .uploadedAt(Instant.now())
                .build();

        when(transferRepo.findByTrackId("TRZ-123456")).thenReturn(Optional.of(record));

        ResponseEntity<Map<String, Object>> response = controller.receipt("TRZ-123456");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("TRZ-123456", response.getBody().get("trackId"));
        assertEquals("report.csv", response.getBody().get("filename"));
        assertEquals("partnerB", response.getBody().get("sender"));
    }

    @Test
    void receipt_returns404WhenRecordNotFound() {
        setAuthenticatedUser("partnerA");
        when(transferRepo.findByTrackId("nonexistent")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.receipt("nonexistent");

        assertEquals(404, response.getStatusCode().value());
    }

    // --- Login: valid credentials return JWT with PARTNER role ---

    @Test
    void login_validCredentialsReturnJwtWithPartnerRole() {
        TransferAccount account = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("partner1")
                .protocol(Protocol.SFTP)
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("correctPassword"))
                .homeDir("/data/sftp/partner1")
                .active(true)
                .build();

        when(accountRepo.findAll()).thenReturn(List.of(account));

        ResponseEntity<Map<String, Object>> response = controller.partnerLogin(
                Map.of("username", "partner1", "password", "correctPassword"));

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body.get("token"), "Should return a JWT token");
        assertTrue(((String) body.get("token")).length() > 10, "Token should be a real JWT");
        assertEquals("partner1", body.get("username"));
        assertEquals("PARTNER", body.get("role"));
        assertEquals("SFTP", body.get("protocol"));
        assertEquals("/data/sftp/partner1", body.get("homeDir"));
    }

    // --- Login: invalid password returns 401 ---

    @Test
    void login_invalidPasswordReturns401() {
        TransferAccount account = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("partner1")
                .protocol(Protocol.SFTP)
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("correctPassword"))
                .homeDir("/data/sftp/partner1")
                .active(true)
                .build();

        when(accountRepo.findAll()).thenReturn(List.of(account));

        ResponseEntity<Map<String, Object>> response = controller.partnerLogin(
                Map.of("username", "partner1", "password", "wrongPassword"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("Invalid credentials", response.getBody().get("error"));
    }

    @Test
    void login_unknownUsernameReturns401() {
        when(accountRepo.findAll()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.partnerLogin(
                Map.of("username", "unknown", "password", "anything"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("Invalid credentials", response.getBody().get("error"));
    }

    @Test
    void login_inactiveAccountReturns401() {
        TransferAccount account = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("partner1")
                .protocol(Protocol.SFTP)
                .passwordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("correctPassword"))
                .homeDir("/data/sftp/partner1")
                .active(false) // inactive
                .build();

        when(accountRepo.findAll()).thenReturn(List.of(account));

        ResponseEntity<Map<String, Object>> response = controller.partnerLogin(
                Map.of("username", "partner1", "password", "correctPassword"));

        assertEquals(401, response.getStatusCode().value());
    }

    // --- Partner can only see own transfer records ---

    @Test
    void track_deniesAccessWhenPartnerDoesNotOwnRecord() {
        setAuthenticatedUser("partnerA");

        TransferAccount otherAccount = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("partnerB")
                .protocol(Protocol.SFTP)
                .passwordHash("hash")
                .homeDir("/data/sftp/partnerB")
                .build();

        FolderMapping mapping = FolderMapping.builder()
                .sourceAccount(otherAccount)
                .sourcePath("/inbox")
                .build();

        FileTransferRecord record = FileTransferRecord.builder()
                .trackId("TRZ-999999")
                .originalFilename("confidential.pdf")
                .sourceFilePath("/data/sftp/partnerB/inbox/confidential.pdf")
                .destinationFilePath("/data/sftp/dest/outbox/confidential.pdf")
                .folderMapping(mapping)
                .status(FileTransferStatus.DOWNLOADED)
                .uploadedAt(Instant.now())
                .build();

        when(transferRepo.findByTrackId("TRZ-999999")).thenReturn(Optional.of(record));

        ResponseEntity<?> response = controller.track("TRZ-999999");

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void track_allowsAccessWhenPartnerOwnsRecord() {
        setAuthenticatedUser("partnerA");

        TransferAccount myAccount = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("partnerA")
                .protocol(Protocol.SFTP)
                .passwordHash("hash")
                .homeDir("/data/sftp/partnerA")
                .build();

        FolderMapping mapping = FolderMapping.builder()
                .sourceAccount(myAccount)
                .sourcePath("/inbox")
                .build();

        FileTransferRecord record = FileTransferRecord.builder()
                .trackId("TRZ-111111")
                .originalFilename("myfile.csv")
                .sourceFilePath("/data/sftp/partnerA/inbox/myfile.csv")
                .destinationFilePath("/data/sftp/dest/outbox/myfile.csv")
                .folderMapping(mapping)
                .status(FileTransferStatus.DOWNLOADED)
                .uploadedAt(Instant.now())
                .build();

        when(transferRepo.findByTrackId("TRZ-111111")).thenReturn(Optional.of(record));
        when(flowExecRepo.findByTrackId("TRZ-111111")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.track("TRZ-111111");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void track_returns404WhenRecordNotFound() {
        setAuthenticatedUser("partnerA");
        when(transferRepo.findByTrackId("TRZ-MISSING")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.track("TRZ-MISSING");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void track_deniesAccessWhenFolderMappingIsNull() {
        setAuthenticatedUser("partnerA");

        FileTransferRecord record = FileTransferRecord.builder()
                .trackId("TRZ-NULLMAP")
                .originalFilename("test.txt")
                .sourceFilePath("/source")
                .destinationFilePath("/dest")
                .folderMapping(null)
                .uploadedAt(Instant.now())
                .build();

        when(transferRepo.findByTrackId("TRZ-NULLMAP")).thenReturn(Optional.of(record));

        ResponseEntity<?> response = controller.track("TRZ-NULLMAP");

        assertEquals(403, response.getStatusCode().value());
    }

    // --- Transfers: partner only sees own records ---

    @Test
    void transfers_returnsEmptyListWhenNoAccountFound() {
        setAuthenticatedUser("partnerX");
        when(accountRepo.findAll()).thenReturn(List.of());

        List<Map<String, Object>> result = controller.transfers(0, 20, null);

        assertTrue(result.isEmpty());
    }

    // --- Authentication guard on endpoints ---

    @Test
    void dashboard_throwsWhenNotAuthenticated() {
        SecurityContextHolder.clearContext();

        assertThrows(SecurityException.class, () -> controller.dashboard());
    }

    // === Helpers ===

    private void setAuthenticatedUser(String username) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}
