package com.filetransfer.ftpweb.service;

import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.entity.vfs.VirtualEntry;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.FolderTemplateRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FileOperationService.
 * Uses @TempDir for filesystem operations to avoid touching the real filesystem.
 */
@ExtendWith(MockitoExtension.class)
class FileOperationServiceTest {

    @Mock private TransferAccountRepository accountRepository;
    @Mock private RoutingEngine routingEngine;
    @Mock private ServerInstanceRepository serverInstanceRepository;
    @Mock private FolderTemplateRepository folderTemplateRepository;
    @Mock private com.filetransfer.shared.vfs.VirtualFileSystem virtualFileSystem;
    @Mock private com.filetransfer.shared.client.StorageServiceClient storageServiceClient;

    private FileOperationService service;

    @TempDir
    Path tempDir;

    private TransferAccount testAccount;

    @BeforeEach
    void setUp() throws Exception {
        service = new FileOperationService(accountRepository, routingEngine, serverInstanceRepository, folderTemplateRepository, virtualFileSystem, storageServiceClient);

        // Set @Value field via reflection
        Field instanceIdField = FileOperationService.class.getDeclaredField("instanceId");
        instanceIdField.setAccessible(true);
        instanceIdField.set(service, null);

        testAccount = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .protocol(Protocol.FTP_WEB)
                .homeDir(tempDir.toString())
                .storageMode("PHYSICAL")
                .active(true)
                .build();

        // Default: findByUsernameAndProtocolAndActiveTrue returns our test account
        lenient().when(accountRepository.findByUsernameAndProtocolAndActiveTrue("testuser", Protocol.FTP_WEB))
                .thenReturn(Optional.of(testAccount));
    }

    // ── upload: blocked extensions ──────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "malware.exe", "script.bat", "hack.cmd", "run.sh", "payload.jar",
            "deploy.war", "util.class", "lib.dll", "module.so", "plugin.dylib",
            "backdoor.jsp", "shell.php", "script.py", "script.ps1", "page.asp",
            "page.aspx", "script.rb", "script.pl", "module.cgi", "UPPER.EXE"
    })
    void upload_blockedExtension_throws(String filename) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(filename);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.upload("testuser", "/", file));
        assertTrue(ex.getMessage().contains("File extension not allowed"));
    }

    // ── upload: invalid filenames ───────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "sub/file.txt", "sub\\file.txt"})
    void upload_invalidFilename_throws(String filename) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(filename);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.upload("testuser", "/", file));
        assertTrue(ex.getMessage().contains("Invalid filename"));
    }

    @Test
    void upload_nullFilename_throws() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(null);

        assertThrows(NullPointerException.class,
                () -> service.upload("testuser", "/", file));
    }

    // ── upload: successful ──────────────────────────────────────────────

    @Test
    void upload_success() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("report.pdf");

        service.upload("testuser", "/", file);

        verify(file).transferTo(tempDir.resolve("report.pdf"));
        verify(routingEngine).onFileUploaded(eq(testAccount), eq("//report.pdf"), anyString());
    }

    @Test
    void upload_intoSubdirectory() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("data.csv");

        service.upload("testuser", "inbox", file);

        Path expectedDir = tempDir.resolve("inbox");
        verify(file).transferTo(expectedDir.resolve("data.csv"));
        verify(routingEngine).onFileUploaded(eq(testAccount), eq("inbox/data.csv"), anyString());
    }

    // ── resolveAndValidate: path traversal prevention ───────────────────

    @Test
    void pathTraversal_parentDirectory_throws() {
        assertThrows(SecurityException.class,
                () -> service.list("testuser", "../../etc"));
    }

    @Test
    void pathTraversal_embeddedDotDot_throws() {
        assertThrows(SecurityException.class,
                () -> service.list("testuser", "subdir/../../.."));
    }

    @Test
    void pathTraversal_normalizedStaysInHome_ok() throws IOException {
        Files.createDirectories(tempDir.resolve("a/b"));

        // "a/b/.." normalizes to "a" which is still within homeDir
        List<FileOperationService.FileEntry> entries = service.list("testuser", "a/b/..");
        // Should not throw
        assertNotNull(entries);
    }

    // ── list ────────────────────────────────────────────────────────────

    @Test
    void list_emptyDirectory_returnsEmpty() throws IOException {
        List<FileOperationService.FileEntry> entries = service.list("testuser", "/");
        assertTrue(entries.isEmpty());
    }

    @Test
    void list_withFilesAndDirs() throws IOException {
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createFile(tempDir.resolve("file2.csv"));
        Files.createDirectories(tempDir.resolve("subdir"));

        List<FileOperationService.FileEntry> entries = service.list("testuser", "/");

        assertEquals(3, entries.size());

        // Verify directory is correctly identified
        FileOperationService.FileEntry dirEntry = entries.stream()
                .filter(e -> "subdir".equals(e.name())).findFirst().orElseThrow();
        assertTrue(dirEntry.directory());

        // Verify file is correctly identified
        FileOperationService.FileEntry fileEntry = entries.stream()
                .filter(e -> "file1.txt".equals(e.name())).findFirst().orElseThrow();
        assertFalse(fileEntry.directory());
    }

    // ── download ────────────────────────────────────────────────────────

    @Test
    void download_fileNotFound_throws() {
        assertThrows(NoSuchElementException.class,
                () -> service.download("testuser", "nonexistent.txt"));
    }

    @Test
    void download_directory_throws() throws IOException {
        Files.createDirectories(tempDir.resolve("somedir"));

        assertThrows(NoSuchElementException.class,
                () -> service.download("testuser", "somedir"));
    }

    @Test
    void download_success() throws Exception {
        Files.writeString(tempDir.resolve("report.pdf"), "PDF content");

        Resource resource = service.download("testuser", "report.pdf");

        assertNotNull(resource);
        assertTrue(resource.exists());
        verify(routingEngine).onFileDownloaded(eq(testAccount), anyString());
    }

    // ── mkdir ───────────────────────────────────────────────────────────

    @Test
    void mkdir_createsDirectory() throws IOException {
        service.mkdir("testuser", "new-folder");

        assertTrue(Files.isDirectory(tempDir.resolve("new-folder")));
    }

    @Test
    void mkdir_createsNestedDirectories() throws IOException {
        service.mkdir("testuser", "a/b/c");

        assertTrue(Files.isDirectory(tempDir.resolve("a/b/c")));
    }

    // ── delete ──────────────────────────────────────────────────────────

    @Test
    void delete_file() throws IOException {
        Path file = Files.createFile(tempDir.resolve("to-delete.txt"));
        assertTrue(Files.exists(file));

        service.delete("testuser", "to-delete.txt");

        assertFalse(Files.exists(file));
    }

    @Test
    void delete_directoryRecursively() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("dir-to-delete/subdir"));
        Files.createFile(dir.resolve("file.txt"));

        service.delete("testuser", "dir-to-delete");

        assertFalse(Files.exists(tempDir.resolve("dir-to-delete")));
    }

    // ── rename ──────────────────────────────────────────────────────────

    @Test
    void rename_file() throws IOException {
        Files.writeString(tempDir.resolve("old-name.txt"), "content");

        service.rename("testuser", "old-name.txt", "new-name.txt");

        assertFalse(Files.exists(tempDir.resolve("old-name.txt")));
        assertTrue(Files.exists(tempDir.resolve("new-name.txt")));
        assertEquals("content", Files.readString(tempDir.resolve("new-name.txt")));
    }

    // ── findAccount ─────────────────────────────────────────────────────

    @Test
    void findAccount_noInstanceId_usesActiveTrue() throws IOException {
        service.list("testuser", "/");

        verify(accountRepository).findByUsernameAndProtocolAndActiveTrue("testuser", Protocol.FTP_WEB);
        verify(accountRepository, never()).findByUsernameAndProtocolAndInstance(any(), any(), any());
    }

    @Test
    void findAccount_withInstanceId_usesInstanceLookup() throws Exception {
        // Set instanceId via reflection
        Field instanceIdField = FileOperationService.class.getDeclaredField("instanceId");
        instanceIdField.setAccessible(true);
        instanceIdField.set(service, "node-1");

        when(accountRepository.findByUsernameAndProtocolAndInstance("testuser", Protocol.FTP_WEB, "node-1"))
                .thenReturn(Optional.of(testAccount));

        service.list("testuser", "/");

        verify(accountRepository).findByUsernameAndProtocolAndInstance("testuser", Protocol.FTP_WEB, "node-1");
        verify(accountRepository, never()).findByUsernameAndProtocolAndActiveTrue(any(), any());
    }

    @Test
    void findAccount_notFound_throws() {
        when(accountRepository.findByUsernameAndProtocolAndActiveTrue("unknown", Protocol.FTP_WEB))
                .thenReturn(Optional.empty());

        NoSuchElementException ex = assertThrows(NoSuchElementException.class,
                () -> service.list("unknown", "/"));
        assertTrue(ex.getMessage().contains("FTP_WEB account not found"));
    }

    // ── Virtual mode: bucket-aware upload ─────────────────────────────────

    private TransferAccount virtualAccount;

    private void setupVirtualAccount() {
        virtualAccount = TransferAccount.builder()
                .id(UUID.randomUUID())
                .username("vuser")
                .protocol(Protocol.FTP_WEB)
                .homeDir(tempDir.toString())
                .storageMode("VIRTUAL")
                .active(true)
                .build();
        lenient().when(accountRepository.findByUsernameAndProtocolAndActiveTrue("vuser", Protocol.FTP_WEB))
                .thenReturn(Optional.of(virtualAccount));
    }

    @Test
    void virtualUpload_inlineBucket_noCasCall() throws IOException {
        setupVirtualAccount();
        byte[] smallData = "tiny EDI file".getBytes();

        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("small.edi");
        when(file.getBytes()).thenReturn(smallData);
        when(file.getContentType()).thenReturn("application/edi-x12");
        when(virtualFileSystem.determineBucket(smallData.length, virtualAccount.getId())).thenReturn("INLINE");

        service.upload("vuser", "inbox", file);

        // INLINE: content goes directly to VFS, no Storage Manager call
        verify(virtualFileSystem).writeFile(virtualAccount.getId(), "/inbox/small.edi",
                null, smallData.length, null, "application/edi-x12", smallData);
        verifyNoInteractions(storageServiceClient);
    }

    @Test
    void virtualUpload_standardBucket_onboardsToCas() throws IOException {
        setupVirtualAccount();
        byte[] mediumData = new byte[100_000]; // 100KB

        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("report.csv");
        when(file.getBytes()).thenReturn(mediumData);
        when(file.getContentType()).thenReturn("text/csv");
        when(virtualFileSystem.determineBucket(mediumData.length, virtualAccount.getId())).thenReturn("STANDARD");
        when(storageServiceClient.store(eq("report.csv"), eq(mediumData), isNull(), anyString()))
                .thenReturn(Map.of("sha256", "abc123sha", "trackId", "TRK001"));

        service.upload("vuser", "inbox", file);

        // STANDARD: onboard to Storage Manager, then register in VFS
        verify(storageServiceClient).store(eq("report.csv"), eq(mediumData), isNull(), anyString());
        verify(virtualFileSystem).writeFile(virtualAccount.getId(), "/inbox/report.csv",
                "abc123sha", mediumData.length, "TRK001", "text/csv", null);
    }

    @Test
    void virtualUpload_chunkedBucket_onboardsChunks() throws IOException {
        setupVirtualAccount();
        byte[] largeData = new byte[5 * 1024 * 1024]; // 5MB (will produce 2 chunks at 4MB each)

        UUID entryId = UUID.randomUUID();
        VirtualEntry chunkedEntry = VirtualEntry.builder().id(entryId).build();

        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("archive.bin");
        when(file.getBytes()).thenReturn(largeData);
        when(file.getContentType()).thenReturn("application/octet-stream");
        when(virtualFileSystem.determineBucket(largeData.length, virtualAccount.getId())).thenReturn("CHUNKED");
        when(virtualFileSystem.writeFile(virtualAccount.getId(), "/inbox/archive.bin",
                null, largeData.length, null, "application/octet-stream", null))
                .thenReturn(chunkedEntry);
        when(storageServiceClient.store(anyString(), any(byte[].class), isNull(), anyString()))
                .thenReturn(Map.of("sha256", "chunksha"));

        service.upload("vuser", "inbox", file);

        // CHUNKED: manifest created in VFS, chunks onboarded to Storage Manager
        verify(virtualFileSystem).writeFile(virtualAccount.getId(), "/inbox/archive.bin",
                null, largeData.length, null, "application/octet-stream", null);
        verify(storageServiceClient, times(2)).store(anyString(), any(byte[].class), isNull(), anyString());
        verify(virtualFileSystem, times(2)).registerChunk(eq(entryId), anyInt(), eq("chunksha"), anyLong(), eq("chunksha"));
    }
}
