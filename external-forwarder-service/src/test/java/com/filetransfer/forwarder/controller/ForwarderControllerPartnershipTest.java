package com.filetransfer.forwarder.controller;

import com.filetransfer.shared.entity.integration.As2Partnership;
import com.filetransfer.shared.entity.transfer.DeliveryEndpoint;
import com.filetransfer.shared.enums.DeliveryProtocol;
import com.filetransfer.shared.repository.As2PartnershipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the resolvePartnership logic used by AS2/AS4 dispatch.
 * Uses reflection to access the private method since it's tightly coupled to the controller.
 */
@ExtendWith(MockitoExtension.class)
class ForwarderControllerPartnershipTest {

    private final As2PartnershipRepository partnershipRepo = mock(As2PartnershipRepository.class);

    /**
     * Build a minimal ForwarderController with only the partnership repo wired.
     * Other dependencies are null since we only test resolvePartnership.
     */
    private Object invokeResolvePartnership(DeliveryEndpoint ep, String protocol) throws Exception {
        ForwarderController controller = new ForwarderController(
                null, null, partnershipRepo,
                null, null, null, null, null, null, null);

        Method method = ForwarderController.class.getDeclaredMethod(
                "resolvePartnership", DeliveryEndpoint.class, String.class);
        method.setAccessible(true);

        try {
            return method.invoke(controller, ep, protocol);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void resolvePartnership_prefersDirectIdLink() throws Exception {
        UUID partnershipId = UUID.randomUUID();
        As2Partnership partnership = As2Partnership.builder()
                .id(partnershipId)
                .partnerName("Direct Partner")
                .partnerAs2Id("DIRECT-AS2")
                .ourAs2Id("US-AS2")
                .endpointUrl("https://direct.example.com/as2")
                .protocol("AS2")
                .active(true)
                .build();

        when(partnershipRepo.findById(partnershipId)).thenReturn(Optional.of(partnership));

        DeliveryEndpoint ep = DeliveryEndpoint.builder()
                .name("Some Other Name")
                .protocol(DeliveryProtocol.AS2)
                .as2PartnershipId(partnershipId)
                .build();

        As2Partnership result = (As2Partnership) invokeResolvePartnership(ep, "AS2");
        assertEquals(partnershipId, result.getId());

        // Should NOT fall back to name lookup
        verify(partnershipRepo, never()).findByPartnerNameAndActiveTrue(any());
    }

    @Test
    void resolvePartnership_fallsBackToNameMatch() throws Exception {
        As2Partnership partnership = As2Partnership.builder()
                .id(UUID.randomUUID())
                .partnerName("Acme Corp")
                .partnerAs2Id("ACME-AS2")
                .ourAs2Id("US-AS2")
                .endpointUrl("https://acme.example.com/as2")
                .protocol("AS2")
                .active(true)
                .build();

        when(partnershipRepo.findByPartnerNameAndActiveTrue("Acme Corp"))
                .thenReturn(Optional.of(partnership));

        DeliveryEndpoint ep = DeliveryEndpoint.builder()
                .name("Acme Corp")
                .protocol(DeliveryProtocol.AS2)
                .as2PartnershipId(null) // no direct link
                .build();

        As2Partnership result = (As2Partnership) invokeResolvePartnership(ep, "AS2");
        assertEquals("ACME-AS2", result.getPartnerAs2Id());
    }

    @Test
    void resolvePartnership_directId_throwsOnProtocolMismatch() {
        UUID partnershipId = UUID.randomUUID();
        As2Partnership partnership = As2Partnership.builder()
                .id(partnershipId)
                .partnerAs2Id("P1")
                .ourAs2Id("US")
                .endpointUrl("https://example.com/as2")
                .protocol("AS2") // Partnership is AS2
                .active(true)
                .build();

        when(partnershipRepo.findById(partnershipId)).thenReturn(Optional.of(partnership));

        DeliveryEndpoint ep = DeliveryEndpoint.builder()
                .name("Test")
                .protocol(DeliveryProtocol.AS4)
                .as2PartnershipId(partnershipId)
                .build();

        // Requesting AS4 but partnership is AS2 → should fail
        assertThrows(IllegalArgumentException.class, () ->
                invokeResolvePartnership(ep, "AS4"));
    }

    @Test
    void resolvePartnership_directId_throwsOnInactivePartnership() {
        UUID partnershipId = UUID.randomUUID();
        As2Partnership partnership = As2Partnership.builder()
                .id(partnershipId)
                .partnerAs2Id("P1")
                .ourAs2Id("US")
                .endpointUrl("https://example.com/as2")
                .protocol("AS2")
                .active(false) // inactive
                .build();

        when(partnershipRepo.findById(partnershipId)).thenReturn(Optional.of(partnership));

        DeliveryEndpoint ep = DeliveryEndpoint.builder()
                .name("Test")
                .protocol(DeliveryProtocol.AS2)
                .as2PartnershipId(partnershipId)
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                invokeResolvePartnership(ep, "AS2"));
    }

    @Test
    void resolvePartnership_nameFallback_throwsWhenNoMatch() {
        when(partnershipRepo.findByPartnerNameAndActiveTrue("Unknown Partner"))
                .thenReturn(Optional.empty());

        DeliveryEndpoint ep = DeliveryEndpoint.builder()
                .name("Unknown Partner")
                .protocol(DeliveryProtocol.AS2)
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                invokeResolvePartnership(ep, "AS2"));
    }

    @Test
    void resolvePartnership_nameFallback_filtersProtocol() {
        As2Partnership as2Partnership = As2Partnership.builder()
                .id(UUID.randomUUID())
                .partnerName("MultiProto Partner")
                .partnerAs2Id("MP-AS2")
                .ourAs2Id("US")
                .endpointUrl("https://example.com/as2")
                .protocol("AS2") // Partnership is AS2
                .active(true)
                .build();

        when(partnershipRepo.findByPartnerNameAndActiveTrue("MultiProto Partner"))
                .thenReturn(Optional.of(as2Partnership));

        DeliveryEndpoint ep = DeliveryEndpoint.builder()
                .name("MultiProto Partner")
                .protocol(DeliveryProtocol.AS4)
                .build();

        // Name matches but protocol doesn't → should fail
        assertThrows(IllegalArgumentException.class, () ->
                invokeResolvePartnership(ep, "AS4"));
    }
}
