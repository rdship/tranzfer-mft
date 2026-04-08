package com.filetransfer.shared.routing;

import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import com.filetransfer.shared.repository.FolderMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutingEvaluatorTest {

    private FolderMappingRepository mappingRepository;
    private FileTransferRecordRepository recordRepository;
    private ClusterService clusterService;
    private RoutingEvaluator evaluator;

    private Method pathUnderMappedDirMethod;
    private Method filenameMatchesMethod;
    private Method extractFilenameMethod;
    private Method protocolToServiceTypeMethod;

    @BeforeEach
    void setUp() throws Exception {
        mappingRepository = mock(FolderMappingRepository.class);
        recordRepository = mock(FileTransferRecordRepository.class);
        clusterService = mock(ClusterService.class);
        evaluator = new RoutingEvaluator(mappingRepository, recordRepository, clusterService);

        pathUnderMappedDirMethod = RoutingEvaluator.class.getDeclaredMethod(
                "pathUnderMappedDir", String.class, String.class);
        pathUnderMappedDirMethod.setAccessible(true);

        filenameMatchesMethod = RoutingEvaluator.class.getDeclaredMethod(
                "filenameMatches", String.class, String.class);
        filenameMatchesMethod.setAccessible(true);

        extractFilenameMethod = RoutingEvaluator.class.getDeclaredMethod(
                "extractFilename", String.class);
        extractFilenameMethod.setAccessible(true);

        protocolToServiceTypeMethod = RoutingEvaluator.class.getDeclaredMethod(
                "protocolToServiceType", Protocol.class);
        protocolToServiceTypeMethod.setAccessible(true);
    }

    private boolean invokePathUnderMappedDir(String relativeFilePath, String mappedSourcePath) throws Exception {
        return (boolean) pathUnderMappedDirMethod.invoke(evaluator, relativeFilePath, mappedSourcePath);
    }

    private boolean invokeFilenameMatches(String filename, String pattern) throws Exception {
        return (boolean) filenameMatchesMethod.invoke(evaluator, filename, pattern);
    }

    private String invokeExtractFilename(String path) throws Exception {
        return (String) extractFilenameMethod.invoke(evaluator, path);
    }

    private ServiceType invokeProtocolToServiceType(Protocol protocol) throws Exception {
        return (ServiceType) protocolToServiceTypeMethod.invoke(evaluator, protocol);
    }

    // --- pathUnderMappedDir tests ---

    @Test
    void pathUnderMappedDir_fileInMappedDir_returnsTrue() throws Exception {
        assertTrue(invokePathUnderMappedDir("/inbox/file.csv", "/inbox"));
    }

    @Test
    void pathUnderMappedDir_mappedDirWithTrailingSlash_returnsTrue() throws Exception {
        assertTrue(invokePathUnderMappedDir("/inbox/file.csv", "/inbox/"));
    }

    @Test
    void pathUnderMappedDir_fileInDifferentDir_returnsFalse() throws Exception {
        assertFalse(invokePathUnderMappedDir("/outbox/file.csv", "/inbox"));
    }

    @Test
    void pathUnderMappedDir_filePathWithoutLeadingSlash_returnsTrue() throws Exception {
        // Auto-prepends "/" to relative path
        assertTrue(invokePathUnderMappedDir("inbox/file.csv", "/inbox"));
    }

    @Test
    void pathUnderMappedDir_exactMatch_returnsTrue() throws Exception {
        assertTrue(invokePathUnderMappedDir("/inbox", "/inbox"));
    }

    @Test
    void pathUnderMappedDir_nestedSubdir_returnsTrue() throws Exception {
        assertTrue(invokePathUnderMappedDir("/inbox/subdir/file.csv", "/inbox"));
    }

    // --- filenameMatches tests ---

    @Test
    void filenameMatches_nullPattern_returnsTrue() throws Exception {
        assertTrue(invokeFilenameMatches("report.csv", null));
    }

    @Test
    void filenameMatches_emptyPattern_returnsTrue() throws Exception {
        assertTrue(invokeFilenameMatches("report.csv", ""));
    }

    @Test
    void filenameMatches_blankPattern_returnsTrue() throws Exception {
        assertTrue(invokeFilenameMatches("report.csv", "   "));
    }

    @Test
    void filenameMatches_matchingRegex_returnsTrue() throws Exception {
        assertTrue(invokeFilenameMatches("report.csv", ".*\\.csv"));
    }

    @Test
    void filenameMatches_nonMatchingRegex_returnsFalse() throws Exception {
        assertFalse(invokeFilenameMatches("report.pdf", ".*\\.csv"));
    }

    @Test
    void filenameMatches_invalidRegex_returnsFalse() throws Exception {
        assertFalse(invokeFilenameMatches("report.csv", "[invalid"));
    }

    @Test
    void filenameMatches_exactNameMatch_returnsTrue() throws Exception {
        assertTrue(invokeFilenameMatches("report.csv", "report\\.csv"));
    }

    // --- extractFilename tests ---

    @Test
    void extractFilename_pathWithDirectory_returnsFilename() throws Exception {
        assertEquals("report.csv", invokeExtractFilename("/inbox/report.csv"));
    }

    @Test
    void extractFilename_filenameOnly_returnsSame() throws Exception {
        assertEquals("report.csv", invokeExtractFilename("report.csv"));
    }

    @Test
    void extractFilename_nestedPath_returnsFilename() throws Exception {
        assertEquals("file.txt", invokeExtractFilename("/a/b/c/file.txt"));
    }

    // --- protocolToServiceType tests ---

    @Test
    void protocolToServiceType_sftp_returnsSftp() throws Exception {
        assertEquals(ServiceType.SFTP, invokeProtocolToServiceType(Protocol.SFTP));
    }

    @Test
    void protocolToServiceType_ftp_returnsFtp() throws Exception {
        assertEquals(ServiceType.FTP, invokeProtocolToServiceType(Protocol.FTP));
    }

    @Test
    void protocolToServiceType_ftpWeb_returnsFtpWeb() throws Exception {
        assertEquals(ServiceType.FTP_WEB, invokeProtocolToServiceType(Protocol.FTP_WEB));
    }

    @Test
    void protocolToServiceType_https_returnsFtpWeb() throws Exception {
        assertEquals(ServiceType.FTP_WEB, invokeProtocolToServiceType(Protocol.HTTPS));
    }

    @Test
    void protocolToServiceType_as2_returnsSftp() throws Exception {
        assertEquals(ServiceType.SFTP, invokeProtocolToServiceType(Protocol.AS2));
    }

    @Test
    void protocolToServiceType_as4_returnsSftp() throws Exception {
        assertEquals(ServiceType.SFTP, invokeProtocolToServiceType(Protocol.AS4));
    }
}
