package com.filetransfer.shared.crypto;

import com.filetransfer.shared.client.EncryptionServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialCryptoClientTest {

    private EncryptionServiceClient encryptionService;
    private CredentialCryptoClient client;

    @BeforeEach
    void setUp() {
        encryptionService = mock(EncryptionServiceClient.class);
        client = new CredentialCryptoClient(encryptionService);
    }

    @Test
    void encrypt_delegatesToEncryptionServiceClient() {
        when(encryptionService.encryptCredential("mySecret")).thenReturn("base64CipherText");

        String result = client.encrypt("mySecret");

        assertEquals("base64CipherText", result);
        verify(encryptionService).encryptCredential("mySecret");
    }

    @Test
    void decrypt_delegatesToEncryptionServiceClient() {
        when(encryptionService.decryptCredential("base64CipherText")).thenReturn("decryptedSecret");

        String result = client.decrypt("base64CipherText");

        assertEquals("decryptedSecret", result);
        verify(encryptionService).decryptCredential("base64CipherText");
    }

    @Test
    void encrypt_nullInput_delegatesNullHandlingToEncryptionService() {
        when(encryptionService.encryptCredential(null)).thenReturn(null);

        assertNull(client.encrypt(null));
    }

    @Test
    void encrypt_emptyInput_delegatesEmptyHandling() {
        when(encryptionService.encryptCredential("")).thenReturn("");

        assertEquals("", client.encrypt(""));
    }

    @Test
    void decrypt_nullInput_delegatesNullHandling() {
        when(encryptionService.decryptCredential(null)).thenReturn(null);

        assertNull(client.decrypt(null));
    }

    @Test
    void decrypt_emptyInput_delegatesEmptyHandling() {
        when(encryptionService.decryptCredential("")).thenReturn("");

        assertEquals("", client.decrypt(""));
    }

    @Test
    void encrypt_serviceUnavailable_propagatesException() {
        when(encryptionService.encryptCredential("secret"))
                .thenThrow(new RuntimeException("encryption-service: encryptCredential failed"));

        assertThrows(RuntimeException.class, () -> client.encrypt("secret"));
    }

    @Test
    void decrypt_serviceUnavailable_propagatesException() {
        when(encryptionService.decryptCredential("encrypted"))
                .thenThrow(new RuntimeException("encryption-service: decryptCredential failed"));

        assertThrows(RuntimeException.class, () -> client.decrypt("encrypted"));
    }
}
