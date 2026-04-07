package com.filetransfer.forwarder.service;

import com.filetransfer.shared.entity.As2Message;
import com.filetransfer.shared.entity.As2Partnership;
import com.filetransfer.shared.repository.As2MessageRepository;
import com.filetransfer.shared.repository.As2PartnershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class As4ForwarderServiceTest {

    @Mock private As2PartnershipRepository partnershipRepository;
    @Mock private As2MessageRepository messageRepository;

    private As4ForwarderService service;

    private As2Partnership partnership;

    @BeforeEach
    void setUp() throws Exception {
        service = new As4ForwarderService(partnershipRepository, messageRepository, null);

        // Use short timeouts so tests fail fast instead of waiting 30s for DNS/connect
        java.lang.reflect.Field ctf = As4ForwarderService.class.getDeclaredField("connectTimeoutMs");
        ctf.setAccessible(true);
        ctf.setInt(service, 200);
        java.lang.reflect.Field rtf = As4ForwarderService.class.getDeclaredField("readTimeoutMs");
        rtf.setAccessible(true);
        rtf.setInt(service, 200);

        partnership = As2Partnership.builder()
                .id(UUID.randomUUID())
                .partnerName("Partner-EU")
                .partnerAs2Id("EU-PARTNER-AS4")
                .ourAs2Id("TRANZFER-AS4")
                .endpointUrl("https://partner-eu.example.com/as4/receive")
                .signingAlgorithm("SHA256")
                .encryptionAlgorithm("AES256")
                .protocol("AS4")
                .active(true)
                .build();
    }

    @Test
    void forward_createsOutboundMessageWithCorrectFields() throws Exception {
        // Capture status at first save time (before mutation to FAILED)
        java.util.List<String> statusesAtSaveTime = new java.util.ArrayList<>();
        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> {
                    As2Message msg = inv.getArgument(0);
                    statusesAtSaveTime.add(msg.getStatus());
                    return msg;
                });

        try {
            service.forward(partnership, "order.xml", "<Order>test</Order>".getBytes(), "TRZ-as4-001");
        } catch (Exception ignored) {
            // Expected: no real AS4 server
        }

        ArgumentCaptor<As2Message> captor = ArgumentCaptor.forClass(As2Message.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());

        As2Message saved = captor.getAllValues().get(0);
        assertEquals("OUTBOUND", saved.getDirection());
        assertEquals("order.xml", saved.getFilename());
        assertEquals("TRZ-as4-001", saved.getTrackId());
        assertTrue(saved.getMessageId().contains("@TRANZFER-AS4"));
        // First save was with SENDING status
        assertEquals("SENDING", statusesAtSaveTime.get(0));
    }

    @Test
    void forward_setsFailedStatusOnConnectionError() throws Exception {
        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThrows(Exception.class, () ->
                service.forward(partnership, "fail.xml", "data".getBytes(), "TRZ-as4-fail"));

        ArgumentCaptor<As2Message> captor = ArgumentCaptor.forClass(As2Message.class);
        verify(messageRepository, atLeast(2)).save(captor.capture());

        As2Message lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("FAILED", lastSave.getStatus());
        assertNotNull(lastSave.getErrorMessage());
    }

    @Test
    void forward_byPartnershipId_requiresAs4Protocol() {
        partnership.setProtocol("AS2"); // Wrong protocol
        UUID pid = partnership.getId();
        when(partnershipRepository.findById(pid)).thenReturn(Optional.of(partnership));

        assertThrows(IllegalArgumentException.class, () ->
                service.forward(pid, "test.xml", "data".getBytes(), "TRZ-wrong-proto"));
    }

    @Test
    void forward_byPartnershipId_looksUpAndForwards() throws Exception {
        UUID pid = partnership.getId();
        when(partnershipRepository.findById(pid)).thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        try {
            service.forward(pid, "test.xml", "data".getBytes(), "TRZ-as4-lookup");
        } catch (Exception ignored) {}

        verify(partnershipRepository).findById(pid);
        verify(messageRepository, atLeastOnce()).save(any(As2Message.class));
    }

    @Test
    void forward_byPartnershipId_throwsWhenNotFound() {
        UUID pid = UUID.randomUUID();
        when(partnershipRepository.findById(pid)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.forward(pid, "test.xml", "data".getBytes(), "TRZ-nf"));
    }

    @Test
    void forward_fileSizeTrackedCorrectly() throws Exception {
        byte[] payload = "large-payload-content-here".getBytes();
        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        try {
            service.forward(partnership, "big.dat", payload, "TRZ-size");
        } catch (Exception ignored) {}

        ArgumentCaptor<As2Message> captor = ArgumentCaptor.forClass(As2Message.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());
        assertEquals((long) payload.length, captor.getValue().getFileSize());
    }
}
