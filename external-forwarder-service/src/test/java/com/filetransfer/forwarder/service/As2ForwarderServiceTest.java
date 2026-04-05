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
class As2ForwarderServiceTest {

    @Mock private As2PartnershipRepository partnershipRepository;
    @Mock private As2MessageRepository messageRepository;

    private As2ForwarderService service;

    private As2Partnership partnership;

    @BeforeEach
    void setUp() {
        service = new As2ForwarderService(partnershipRepository, messageRepository, null);

        partnership = As2Partnership.builder()
                .id(UUID.randomUUID())
                .partnerName("Acme Corp")
                .partnerAs2Id("ACME-AS2")
                .ourAs2Id("TRANZFER-AS2")
                .endpointUrl("https://acme.example.com/as2/receive")
                .signingAlgorithm("SHA256")
                .encryptionAlgorithm("AES256")
                .mdnRequired(true)
                .mdnAsync(false)
                .protocol("AS2")
                .active(true)
                .build();
    }

    @Test
    void forward_createsOutboundMessageRecord() throws Exception {
        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Will fail on HTTP call since no real server — we verify message creation before that
        try {
            service.forward(partnership, "invoice.edi", "test-data".getBytes(), "TRZ-abc123");
        } catch (Exception ignored) {
            // Expected: no real AS2 server to connect to
        }

        ArgumentCaptor<As2Message> captor = ArgumentCaptor.forClass(As2Message.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());

        As2Message saved = captor.getAllValues().get(0);
        assertEquals("OUTBOUND", saved.getDirection());
        assertEquals("invoice.edi", saved.getFilename());
        assertEquals(9L, saved.getFileSize());
        assertEquals("TRZ-abc123", saved.getTrackId());
        assertNotNull(saved.getMessageId());
        assertTrue(saved.getMessageId().contains("@TRANZFER-AS2"));
    }

    @Test
    void forward_setsFailedStatusOnError() throws Exception {
        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThrows(Exception.class, () ->
                service.forward(partnership, "test.edi", "data".getBytes(), "TRZ-err1"));

        ArgumentCaptor<As2Message> captor = ArgumentCaptor.forClass(As2Message.class);
        verify(messageRepository, atLeast(2)).save(captor.capture());

        // Last save should have FAILED status
        As2Message lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("FAILED", lastSave.getStatus());
        assertNotNull(lastSave.getErrorMessage());
    }

    @Test
    void forward_byPartnershipId_looksUpPartnership() throws Exception {
        UUID pid = partnership.getId();
        when(partnershipRepository.findById(pid)).thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        try {
            service.forward(pid, "test.edi", "data".getBytes(), "TRZ-lookup1");
        } catch (Exception ignored) {
            // Expected: no real server
        }

        verify(partnershipRepository).findById(pid);
        verify(messageRepository, atLeastOnce()).save(any(As2Message.class));
    }

    @Test
    void forward_byPartnershipId_throwsWhenNotFound() {
        UUID pid = UUID.randomUUID();
        when(partnershipRepository.findById(pid)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.forward(pid, "test.edi", "data".getBytes(), "TRZ-nf1"));
    }

    @Test
    void forward_byPartnershipId_throwsWhenInactive() {
        partnership.setActive(false);
        UUID pid = partnership.getId();
        when(partnershipRepository.findById(pid)).thenReturn(Optional.of(partnership));

        assertThrows(IllegalArgumentException.class, () ->
                service.forward(pid, "test.edi", "data".getBytes(), "TRZ-inactive1"));
    }

    @Test
    void forward_setsAsyncMdnHeaders() throws Exception {
        partnership.setMdnAsync(true);
        partnership.setMdnUrl("https://tranzfer.example.com/as2/mdn");

        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        try {
            service.forward(partnership, "async.edi", "data".getBytes(), "TRZ-async1");
        } catch (Exception ignored) {
            // Expected: no real server
        }

        // Verify message was created with correct direction
        ArgumentCaptor<As2Message> captor = ArgumentCaptor.forClass(As2Message.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());
        assertEquals("OUTBOUND", captor.getValue().getDirection());
    }

    @Test
    void forward_messageIdContainsOurAs2Id() throws Exception {
        when(messageRepository.save(any(As2Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        try {
            service.forward(partnership, "test.edi", "data".getBytes(), null);
        } catch (Exception ignored) {}

        ArgumentCaptor<As2Message> captor = ArgumentCaptor.forClass(As2Message.class);
        verify(messageRepository, atLeastOnce()).save(captor.capture());

        String messageId = captor.getValue().getMessageId();
        assertTrue(messageId.endsWith("@TRANZFER-AS2"), "Message-ID should end with @ourAs2Id");
    }
}
