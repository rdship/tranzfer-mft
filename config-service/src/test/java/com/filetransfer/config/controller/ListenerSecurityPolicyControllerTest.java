package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.ExternalDestination;
import com.filetransfer.shared.entity.ListenerSecurityPolicy;
import com.filetransfer.shared.entity.ServerInstance;
import com.filetransfer.shared.enums.SecurityTier;
import com.filetransfer.shared.repository.ListenerSecurityPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ListenerSecurityPolicyController}.
 *
 * <p>DmzProxyClient is a concrete class extending ResilientServiceClient (JDK 25 — cannot mock).
 * Passed as null because pushToProxyIfNeeded is a best-effort try-catch and all test
 * server instances have useProxy=false (default), so the client is never invoked.
 */
@ExtendWith(MockitoExtension.class)
class ListenerSecurityPolicyControllerTest {

    @Mock private ListenerSecurityPolicyRepository policyRepo;

    private ListenerSecurityPolicyController controller;

    @BeforeEach
    void setUp() {
        controller = new ListenerSecurityPolicyController(policyRepo, null /* DmzProxyClient — see class Javadoc */);
    }

    // ---- list ----

    @Test
    void listAll_returnsActivePolicies() {
        ListenerSecurityPolicy p1 = buildPolicy();
        ListenerSecurityPolicy p2 = buildPolicy();
        p2.setName("second-policy");
        when(policyRepo.findByActiveTrue()).thenReturn(List.of(p1, p2));

        List<ListenerSecurityPolicy> result = controller.list();

        assertEquals(2, result.size());
        assertEquals("test-policy", result.get(0).getName());
        assertEquals("second-policy", result.get(1).getName());
        verify(policyRepo).findByActiveTrue();
    }

    // ---- getById ----

    @Test
    void getById_found_returnsPolicy() {
        ListenerSecurityPolicy policy = buildPolicy();
        UUID id = policy.getId();
        when(policyRepo.findById(id)).thenReturn(Optional.of(policy));

        ListenerSecurityPolicy result = controller.get(id);

        assertEquals("test-policy", result.getName());
        assertEquals(SecurityTier.AI, result.getSecurityTier());
    }

    @Test
    void getById_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.get(id));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---- getForServer ----

    @Test
    void getForServer_returnsPolicy() {
        UUID serverId = UUID.randomUUID();
        ListenerSecurityPolicy policy = buildPolicy();
        when(policyRepo.findByServerInstanceIdAndActiveTrue(serverId))
                .thenReturn(Optional.of(policy));

        ResponseEntity<ListenerSecurityPolicy> response = controller.getForServer(serverId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("test-policy", response.getBody().getName());
    }

    @Test
    void getForServer_notFound_returnsNoContent() {
        UUID serverId = UUID.randomUUID();
        when(policyRepo.findByServerInstanceIdAndActiveTrue(serverId))
                .thenReturn(Optional.empty());

        ResponseEntity<ListenerSecurityPolicy> response = controller.getForServer(serverId);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    // ---- getForDestination ----

    @Test
    void getForDestination_returnsPolicy() {
        UUID destId = UUID.randomUUID();
        ListenerSecurityPolicy policy = buildPolicy();
        when(policyRepo.findByExternalDestinationIdAndActiveTrue(destId))
                .thenReturn(Optional.of(policy));

        ResponseEntity<ListenerSecurityPolicy> response = controller.getForDestination(destId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("test-policy", response.getBody().getName());
    }

    @Test
    void getForDestination_notFound_returnsNoContent() {
        UUID destId = UUID.randomUUID();
        when(policyRepo.findByExternalDestinationIdAndActiveTrue(destId))
                .thenReturn(Optional.empty());

        ResponseEntity<ListenerSecurityPolicy> response = controller.getForDestination(destId);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    // ---- create ----

    @Test
    void create_validPolicy_savesAndReturns() {
        ListenerSecurityPolicy input = buildPolicyWithServer();
        ListenerSecurityPolicy saved = buildPolicyWithServer();
        saved.setId(UUID.randomUUID());
        when(policyRepo.save(any())).thenReturn(saved);

        ListenerSecurityPolicy result = controller.create(input);

        assertNotNull(result.getId());
        assertEquals("test-policy", result.getName());
        verify(policyRepo).save(any());
    }

    @Test
    void create_bothFksNull_throwsBadRequest() {
        ListenerSecurityPolicy input = buildPolicy();
        // Neither serverInstance nor externalDestination set
        input.setServerInstance(null);
        input.setExternalDestination(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.create(input));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void create_bothFksSet_throwsBadRequest() {
        ListenerSecurityPolicy input = buildPolicy();
        input.setServerInstance(new ServerInstance());
        input.setExternalDestination(new ExternalDestination());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.create(input));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void create_nullName_throwsBadRequest() {
        ListenerSecurityPolicy input = buildPolicyWithServer();
        input.setName(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.create(input));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void create_blankName_throwsBadRequest() {
        ListenerSecurityPolicy input = buildPolicyWithServer();
        input.setName("   ");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.create(input));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    // ---- update ----

    @Test
    void update_existingPolicy_updatesFields() {
        UUID id = UUID.randomUUID();
        ListenerSecurityPolicy existing = buildPolicyWithServer();
        existing.setId(id);
        existing.setRateLimitPerMinute(60);

        ListenerSecurityPolicy updates = new ListenerSecurityPolicy();
        updates.setName("updated-name");
        updates.setDescription("updated description");
        updates.setSecurityTier(SecurityTier.AI_LLM);
        updates.setRateLimitPerMinute(120);
        updates.setMaxConcurrent(50);
        updates.setRequireEncryption(true);

        when(policyRepo.findById(id)).thenReturn(Optional.of(existing));
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ListenerSecurityPolicy result = controller.update(id, updates);

        assertEquals("updated-name", result.getName());
        assertEquals("updated description", result.getDescription());
        assertEquals(SecurityTier.AI_LLM, result.getSecurityTier());
        assertEquals(120, result.getRateLimitPerMinute());
        assertEquals(50, result.getMaxConcurrent());
        assertTrue(result.isRequireEncryption());
        verify(policyRepo).save(existing);
    }

    @Test
    void update_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.update(id, new ListenerSecurityPolicy()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---- delete ----

    @Test
    void delete_existingPolicy_softDeletes() {
        UUID id = UUID.randomUUID();
        ListenerSecurityPolicy existing = buildPolicyWithServer();
        existing.setId(id);
        existing.setActive(true);
        when(policyRepo.findById(id)).thenReturn(Optional.of(existing));
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.delete(id);

        assertFalse(existing.isActive(), "Policy should be soft-deleted (active=false)");
        verify(policyRepo).save(existing);
    }

    @Test
    void delete_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(policyRepo.findById(id)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete(id));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---- helpers ----

    private ListenerSecurityPolicy buildPolicy() {
        ListenerSecurityPolicy p = new ListenerSecurityPolicy();
        p.setId(UUID.randomUUID());
        p.setName("test-policy");
        p.setSecurityTier(SecurityTier.AI);
        p.setActive(true);
        return p;
    }

    private ListenerSecurityPolicy buildPolicyWithServer() {
        ListenerSecurityPolicy p = buildPolicy();
        p.setServerInstance(new ServerInstance());
        return p;
    }

    private ListenerSecurityPolicy buildPolicyWithDestination() {
        ListenerSecurityPolicy p = buildPolicy();
        p.setExternalDestination(new ExternalDestination());
        return p;
    }
}
