package com.filetransfer.keystore.service;

import com.filetransfer.keystore.repository.ManagedKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for KeyManagementService — focused on master password validation.
 * Uses reflection to set @Value fields (no Spring context).
 * Pattern from ParallelIOEngineTest.
 */
class KeyManagementServiceTest {

    private ManagedKeyRepository mockRepository;

    @BeforeEach
    void setUp() {
        mockRepository = mock(ManagedKeyRepository.class);
    }

    // --- PROD: default password must throw ---

    @Test
    void validateMasterPassword_prod_defaultPassword_shouldThrow() throws Exception {
        KeyManagementService service = createService(
                "change-this-master-password", "PROD");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                service::validateMasterPassword,
                "Default master password in PROD must be blocked");
        assertTrue(ex.getMessage().contains("must be changed from default"),
                "Error message should indicate default password rejection");
    }

    // --- PROD: short password (< 16 chars) must throw ---

    @Test
    void validateMasterPassword_prod_shortPassword_shouldThrow() throws Exception {
        KeyManagementService service = createService("Short123!", "PROD");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                service::validateMasterPassword,
                "Password shorter than 16 chars in PROD must be blocked");
        assertTrue(ex.getMessage().contains("at least 16 characters"),
                "Error message should indicate minimum length requirement");
    }

    // --- PROD: strong password (16+ chars, non-default) passes ---

    @Test
    void validateMasterPassword_prod_strongPassword_shouldPass() throws Exception {
        KeyManagementService service = createService(
                "ThisIsAStr0ng!Passw0rd!!", "PROD");

        assertDoesNotThrow(service::validateMasterPassword,
                "Strong 16+ char non-default password in PROD must pass validation");
    }

    // --- DEV: default password should warn but not throw ---

    @Test
    void validateMasterPassword_dev_defaultPassword_shouldNotThrow() throws Exception {
        KeyManagementService service = createService(
                "change-this-master-password", "DEV");

        assertDoesNotThrow(service::validateMasterPassword,
                "Default password in DEV should log warning but not throw");
    }

    // --- TEST: default password should warn but not throw ---

    @Test
    void validateMasterPassword_test_defaultPassword_shouldNotThrow() throws Exception {
        KeyManagementService service = createService(
                "change-this-master-password", "TEST");

        assertDoesNotThrow(service::validateMasterPassword,
                "Default password in TEST should log warning but not throw");
    }

    // --- DEV: short password should warn but not throw ---

    @Test
    void validateMasterPassword_dev_shortPassword_shouldNotThrow() throws Exception {
        KeyManagementService service = createService("dev-short", "DEV");

        assertDoesNotThrow(service::validateMasterPassword,
                "Short password in DEV should log warning but not throw");
    }

    // --- STAGING: default password should throw (same as PROD) ---

    @Test
    void validateMasterPassword_staging_defaultPassword_shouldThrow() throws Exception {
        KeyManagementService service = createService(
                "change-this-master-password", "STAGING");

        assertThrows(IllegalStateException.class,
                service::validateMasterPassword,
                "Default master password in STAGING must be blocked like PROD");
    }

    // --- CERT: short password should throw (same as PROD) ---

    @Test
    void validateMasterPassword_cert_shortPassword_shouldThrow() throws Exception {
        KeyManagementService service = createService("tooshort", "CERT");

        assertThrows(IllegalStateException.class,
                service::validateMasterPassword,
                "Short password in CERT must be blocked like PROD");
    }

    // --- PROD: exactly 16 chars, non-default, should pass ---

    @Test
    void validateMasterPassword_prod_exactly16Chars_shouldPass() throws Exception {
        KeyManagementService service = createService("Exactly16Chars!!", "PROD");
        assertEquals(16, "Exactly16Chars!!".length()); // sanity check

        assertDoesNotThrow(service::validateMasterPassword,
                "Exactly 16-char non-default password in PROD must pass");
    }

    // --- Helper: construct service with reflection-injected fields ---

    private KeyManagementService createService(String masterPassword, String environment) throws Exception {
        // Use the constructor that Lombok @RequiredArgsConstructor generates (takes ManagedKeyRepository)
        KeyManagementService service = new KeyManagementService(mockRepository);
        setField(service, "masterPassword", masterPassword);
        setField(service, "environment", environment);
        return service;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
