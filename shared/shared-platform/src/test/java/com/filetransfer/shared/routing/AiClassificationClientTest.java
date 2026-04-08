package com.filetransfer.shared.routing;

import com.filetransfer.shared.client.AiEngineClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiClassificationClientTest {

    private AiEngineClient aiEngine;
    private AiClassificationClient client;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        aiEngine = mock(AiEngineClient.class);
        client = new AiClassificationClient(aiEngine);

        // Set enabled=true via reflection (@Value-injected)
        var field = AiClassificationClient.class.getDeclaredField("enabled");
        field.setAccessible(true);
        field.set(client, true);
    }

    private Path createTestFile(String content) throws IOException {
        Path file = tempDir.resolve("test.csv");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void classify_allowedFile_returnsAllowedDecision() throws Exception {
        Path file = createTestFile("name,address\nJohn,123 Main St");

        when(aiEngine.classify(eq(file), eq("TRZ001"), eq(false)))
                .thenReturn(new AiEngineClient.ClassificationResult(true, "LOW", 10, null));

        var decision = client.classify(file, "TRZ001", false);

        assertTrue(decision.allowed());
        assertEquals("LOW", decision.riskLevel());
        assertEquals(10, decision.riskScore());
        assertNull(decision.blockReason());
    }

    @Test
    void classify_blockedUnencryptedFile_returnsBlockedDecision() throws Exception {
        Path file = createTestFile("4111-1111-1111-1111");

        when(aiEngine.classify(eq(file), eq("TRZ002"), eq(false)))
                .thenReturn(new AiEngineClient.ClassificationResult(
                        false, "CRITICAL", 95, "PCI data detected without encryption"));

        var decision = client.classify(file, "TRZ002", false);

        assertFalse(decision.allowed());
        assertEquals("CRITICAL", decision.riskLevel());
        assertEquals(95, decision.riskScore());
        assertEquals("PCI data detected without encryption", decision.blockReason());
    }

    @Test
    void classify_blockedButEncryptedFile_delegatesToAiEngine() throws Exception {
        Path file = createTestFile("encrypted content");

        when(aiEngine.classify(eq(file), eq("TRZ003"), eq(true)))
                .thenReturn(new AiEngineClient.ClassificationResult(true, "CRITICAL", 95, null));

        var decision = client.classify(file, "TRZ003", true);

        assertTrue(decision.allowed());
        assertEquals("CRITICAL", decision.riskLevel());
    }

    @Test
    void classify_serviceUnavailable_gracefulDegradation_allowsTransfer() throws Exception {
        Path file = createTestFile("some data");

        when(aiEngine.classify(eq(file), eq("TRZ004"), eq(false)))
                .thenReturn(AiEngineClient.ClassificationResult.ALLOWED);

        var decision = client.classify(file, "TRZ004", false);

        assertTrue(decision.allowed());
        assertEquals("UNKNOWN", decision.riskLevel());
        assertEquals(0, decision.riskScore());
    }

    @Test
    void classify_disabled_skipsClassification() throws Exception {
        var field = AiClassificationClient.class.getDeclaredField("enabled");
        field.setAccessible(true);
        field.set(client, false);

        Path file = createTestFile("data");
        var decision = client.classify(file, "TRZ005", false);

        assertTrue(decision.allowed());
        assertEquals("NONE", decision.riskLevel());
        verifyNoInteractions(aiEngine);
    }
}
