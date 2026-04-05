package com.filetransfer.shared.routing;

import com.filetransfer.shared.config.PlatformConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiClassificationClientTest {

    private RestTemplate restTemplate;
    private AiClassificationClient client;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        PlatformConfig platformConfig = new PlatformConfig();
        platformConfig.getSecurity().setControlApiKey("test_key");

        client = new AiClassificationClient(restTemplate, platformConfig);

        // Set fields via reflection (@Value-injected)
        setField("aiEngineUrl", "http://localhost:8091");
        setField("enabled", true);
    }

    private void setField(String name, Object value) throws Exception {
        var field = AiClassificationClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private Path createTestFile(String content) throws IOException {
        Path file = tempDir.resolve("test.csv");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void classify_allowedFile_returnsAllowedDecision() throws Exception {
        Path file = createTestFile("name,address\nJohn,123 Main St");

        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("riskLevel", "LOW", "riskScore", 10, "blocked", false), HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        var decision = client.classify(file, "TRZ001", false);

        assertTrue(decision.allowed());
        assertEquals("LOW", decision.riskLevel());
        assertEquals(10, decision.riskScore());
        assertNull(decision.blockReason());
    }

    @Test
    void classify_blockedUnencryptedFile_returnsBlockedDecision() throws Exception {
        Path file = createTestFile("4111-1111-1111-1111");

        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("riskLevel", "CRITICAL", "riskScore", 95, "blocked", true,
                        "blockReason", "PCI data detected without encryption"),
                HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        var decision = client.classify(file, "TRZ002", false);

        assertFalse(decision.allowed());
        assertEquals("CRITICAL", decision.riskLevel());
        assertEquals(95, decision.riskScore());
        assertEquals("PCI data detected without encryption", decision.blockReason());
    }

    @Test
    void classify_blockedButEncryptedFile_returnsAllowed() throws Exception {
        Path file = createTestFile("encrypted content");

        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("riskLevel", "CRITICAL", "riskScore", 95, "blocked", true,
                        "blockReason", "PCI data detected"),
                HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        // isEncrypted=true should bypass the block
        var decision = client.classify(file, "TRZ003", true);

        assertTrue(decision.allowed());
        assertEquals("CRITICAL", decision.riskLevel());
    }

    @Test
    void classify_serviceUnavailable_gracefulDegradation_allowsTransfer() throws Exception {
        Path file = createTestFile("some data");

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("Connection refused"));

        var decision = client.classify(file, "TRZ004", false);

        assertTrue(decision.allowed());
        assertEquals("UNKNOWN", decision.riskLevel());
        assertEquals(0, decision.riskScore());
    }

    @Test
    void classify_disabled_skipsClassification() throws Exception {
        setField("enabled", false);
        Path file = createTestFile("data");

        var decision = client.classify(file, "TRZ005", false);

        assertTrue(decision.allowed());
        assertEquals("NONE", decision.riskLevel());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void classify_sendsInternalApiKeyHeader() throws Exception {
        Path file = createTestFile("data");

        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("riskLevel", "NONE", "riskScore", 0, "blocked", false), HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        client.classify(file, "TRZ006", false);

        verify(restTemplate).postForEntity(
                eq("http://localhost:8091/api/v1/ai/classify"),
                argThat(entity -> {
                    @SuppressWarnings("unchecked")
                    HttpEntity<?> e = (HttpEntity<?>) entity;
                    return "test_key".equals(e.getHeaders().getFirst("X-Internal-Key"));
                }),
                eq(Map.class));
    }

    @Test
    void classify_nonOkStatus_gracefulDegradation() throws Exception {
        Path file = createTestFile("data");

        ResponseEntity<Map> response = new ResponseEntity<>(null, HttpStatus.SERVICE_UNAVAILABLE);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        var decision = client.classify(file, "TRZ007", false);

        // Non-OK status falls through to the default "allow"
        assertTrue(decision.allowed());
        assertEquals("UNKNOWN", decision.riskLevel());
    }
}
