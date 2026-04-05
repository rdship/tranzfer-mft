package com.filetransfer.as2.service;

import com.filetransfer.shared.entity.As2Message;
import com.filetransfer.shared.entity.As2Partnership;
import com.filetransfer.shared.repository.As2MessageRepository;
import com.filetransfer.shared.repository.As2PartnershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class As2MessageProcessorTest {

    private As2PartnershipRepository partnershipRepository;
    private As2MessageRepository messageRepository;
    private As2MessageProcessor processor;

    private As2Partnership partnership;

    @BeforeEach
    void setUp() {
        partnershipRepository = mock(As2PartnershipRepository.class);
        messageRepository = mock(As2MessageRepository.class);
        processor = new As2MessageProcessor(partnershipRepository, messageRepository);

        partnership = new As2Partnership();
        partnership.setOurAs2Id("OUR_AS2_ID");
        partnership.setPartnerAs2Id("PARTNER_A");
        partnership.setSigningAlgorithm("SHA256");
    }

    @Test
    void process_missingAs2From_returnsFailure() {
        var result = processor.process(null, "OUR_AS2_ID", "msg-1", "subject",
                "application/edi", new byte[]{1}, Map.of());

        assertFalse(result.success());
        assertEquals("Missing AS2-From header", result.errorReason());
    }

    @Test
    void process_blankAs2From_returnsFailure() {
        var result = processor.process("  ", "OUR_AS2_ID", "msg-1", "subject",
                "application/edi", new byte[]{1}, Map.of());

        assertFalse(result.success());
        assertEquals("Missing AS2-From header", result.errorReason());
    }

    @Test
    void process_missingAs2To_returnsFailure() {
        var result = processor.process("PARTNER_A", null, "msg-1", "subject",
                "application/edi", new byte[]{1}, Map.of());

        assertFalse(result.success());
        assertEquals("Missing AS2-To header", result.errorReason());
    }

    @Test
    void process_missingMessageId_returnsFailure() {
        var result = processor.process("PARTNER_A", "OUR_AS2_ID", null, "subject",
                "application/edi", new byte[]{1}, Map.of());

        assertFalse(result.success());
        assertEquals("Missing Message-ID header", result.errorReason());
    }

    @Test
    void process_blankMessageId_returnsFailure() {
        var result = processor.process("PARTNER_A", "OUR_AS2_ID", "  ", "subject",
                "application/edi", new byte[]{1}, Map.of());

        assertFalse(result.success());
        assertEquals("Missing Message-ID header", result.errorReason());
    }

    @Test
    void process_duplicateMessageId_returnsFailure() {
        when(messageRepository.findByMessageId("msg-001")).thenReturn(Optional.of(new As2Message()));

        var result = processor.process("PARTNER_A", "OUR_AS2_ID", "msg-001", "subject",
                "application/edi", new byte[]{1}, Map.of());

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("Duplicate Message-ID"));
        assertTrue(result.errorReason().contains("msg-001"));
    }

    @Test
    void process_unknownPartner_returnsFailure() {
        when(messageRepository.findByMessageId(anyString())).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("UNKNOWN_PARTNER"))
                .thenReturn(Optional.empty());

        var result = processor.process("UNKNOWN_PARTNER", "OUR_AS2_ID", "msg-002", "subject",
                "application/edi", new byte[]{1}, Map.of());

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("Unknown trading partner"));
        assertTrue(result.errorReason().contains("UNKNOWN_PARTNER"));
    }

    @Test
    void process_as2ToMismatch_returnsFailure() {
        when(messageRepository.findByMessageId(anyString())).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));

        var result = processor.process("PARTNER_A", "WRONG_AS2_ID", "msg-003", "subject",
                "application/edi", new byte[]{1}, Map.of());

        assertFalse(result.success());
        assertEquals("AS2-To does not match our AS2 ID", result.errorReason());
    }

    @Test
    void process_success_returnsCompleteResult() {
        when(messageRepository.findByMessageId(anyString())).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] payload = "Hello World".getBytes(StandardCharsets.UTF_8);
        var result = processor.process("PARTNER_A", "OUR_AS2_ID", "<msg-004@host>",
                "invoice.edi", "application/edi", payload, new HashMap<>());

        assertTrue(result.success());
        assertNull(result.errorReason());
        assertNotNull(result.message());
        assertNotNull(result.partnership());
        assertNotNull(result.mic());
        assertArrayEquals(payload, result.payload());
        assertEquals(partnership, result.partnership());

        // Message should have been saved
        verify(messageRepository).save(any(As2Message.class));
    }

    @Test
    void process_extractsFilenameFromContentDisposition() {
        when(messageRepository.findByMessageId(anyString())).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Disposition", "attachment; filename=\"invoice.edi\"");

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        var result = processor.process("PARTNER_A", "OUR_AS2_ID", "msg-005",
                null, "application/edi", payload, headers);

        assertTrue(result.success());
        assertEquals("invoice.edi", result.filename());
    }

    @Test
    void process_extractsFilenameFromSubject() {
        when(messageRepository.findByMessageId(anyString())).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        var result = processor.process("PARTNER_A", "OUR_AS2_ID", "msg-006",
                "report.csv", "application/edi", payload, new HashMap<>());

        assertTrue(result.success());
        assertEquals("report.csv", result.filename());
    }

    @Test
    void process_generatesFilenameFromMessageId() {
        when(messageRepository.findByMessageId(anyString())).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        // No Content-Disposition, no Subject with extension, so falls back to messageId
        var result = processor.process("PARTNER_A", "OUR_AS2_ID", "<abc123@host.com>",
                "no-extension", "application/edi", payload, new HashMap<>());

        assertTrue(result.success());
        assertEquals("abc123.dat", result.filename());
    }

    @Test
    void process_computesMic() {
        when(messageRepository.findByMessageId(anyString())).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] payload = "Hello".getBytes(StandardCharsets.UTF_8);
        var result = processor.process("PARTNER_A", "OUR_AS2_ID", "msg-007",
                "file.edi", "application/edi", payload, new HashMap<>());

        assertTrue(result.success());
        assertNotNull(result.mic());
        // MIC format is "base64hash, algorithm"
        assertTrue(result.mic().contains("SHA-256"), "MIC should contain algorithm name");
    }

    @Test
    void process_stripsAngleBracketsFromMessageId() {
        when(messageRepository.findByMessageId("msg-008@host")).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        var result = processor.process("PARTNER_A", "OUR_AS2_ID", "<msg-008@host>",
                "file.edi", "application/edi", payload, new HashMap<>());

        assertTrue(result.success());
        // Verify the cleaned message ID was used for duplicate check
        verify(messageRepository).findByMessageId("msg-008@host");
    }
}
