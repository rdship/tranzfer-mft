package com.filetransfer.shared.crypto;

import com.filetransfer.shared.config.PlatformConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CredentialCryptoClientTest {

    private RestTemplate restTemplate;
    private PlatformConfig platformConfig;
    private CredentialCryptoClient client;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        platformConfig = new PlatformConfig();
        platformConfig.getSecurity().setControlApiKey("test_key");

        client = new CredentialCryptoClient(restTemplate, platformConfig);

        // Set the URL field via reflection (it uses @Value)
        var urlField = CredentialCryptoClient.class.getDeclaredField("encryptionServiceUrl");
        urlField.setAccessible(true);
        urlField.set(client, "http://localhost:8086");
    }

    @Test
    void encrypt_callsEncryptionServiceAndReturnsEncryptedValue() {
        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("encrypted", "base64CipherText"), HttpStatus.OK);
        when(restTemplate.postForEntity(
                eq("http://localhost:8086/api/encrypt/credential/encrypt"),
                any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        String result = client.encrypt("mySecret");

        assertEquals("base64CipherText", result);
        verify(restTemplate).postForEntity(
                eq("http://localhost:8086/api/encrypt/credential/encrypt"),
                argThat(entity -> {
                    @SuppressWarnings("unchecked")
                    HttpEntity<Map<String, String>> e = (HttpEntity<Map<String, String>>) entity;
                    return "test_key".equals(e.getHeaders().getFirst("X-Internal-Key"))
                            && "mySecret".equals(e.getBody().get("value"));
                }),
                eq(Map.class));
    }

    @Test
    void decrypt_callsDecryptionServiceAndReturnsPlaintext() {
        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("value", "decryptedSecret"), HttpStatus.OK);
        when(restTemplate.postForEntity(
                eq("http://localhost:8086/api/encrypt/credential/decrypt"),
                any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        String result = client.decrypt("base64CipherText");

        assertEquals("decryptedSecret", result);
    }

    @Test
    void encrypt_nullInput_returnsNull() {
        assertNull(client.encrypt(null));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void encrypt_emptyInput_returnsEmpty() {
        assertEquals("", client.encrypt(""));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void decrypt_nullInput_returnsNull() {
        assertNull(client.decrypt(null));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void decrypt_emptyInput_returnsEmpty() {
        assertEquals("", client.decrypt(""));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void encrypt_serviceUnavailable_throwsRuntimeException() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertThrows(RuntimeException.class, () -> client.encrypt("secret"));
    }

    @Test
    void decrypt_serviceUnavailable_throwsRuntimeException() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertThrows(RuntimeException.class, () -> client.decrypt("encrypted"));
    }

    @Test
    void encrypt_unexpectedStatusCode_throwsRuntimeException() {
        ResponseEntity<Map> response = new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        assertThrows(RuntimeException.class, () -> client.encrypt("secret"));
    }

    @Test
    void encrypt_includesCorrectContentTypeAndApiKey() {
        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("encrypted", "enc"), HttpStatus.OK);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

        client.encrypt("test");

        verify(restTemplate).postForEntity(anyString(),
                argThat(entity -> {
                    @SuppressWarnings("unchecked")
                    HttpEntity<Map<String, String>> e = (HttpEntity<Map<String, String>>) entity;
                    HttpHeaders h = e.getHeaders();
                    return MediaType.APPLICATION_JSON.equals(h.getContentType())
                            && "test_key".equals(h.getFirst("X-Internal-Key"));
                }),
                eq(Map.class));
    }
}
