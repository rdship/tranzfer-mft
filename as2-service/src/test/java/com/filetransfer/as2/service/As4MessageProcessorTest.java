package com.filetransfer.as2.service;

import com.filetransfer.shared.entity.integration.As2Message;
import com.filetransfer.shared.entity.integration.As2Partnership;
import com.filetransfer.shared.repository.integration.As2MessageRepository;
import com.filetransfer.shared.repository.integration.As2PartnershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class As4MessageProcessorTest {

    private As2PartnershipRepository partnershipRepository;
    private As2MessageRepository messageRepository;
    private As4MessageProcessor processor;

    private As2Partnership partnership;

    private static final String VALID_SOAP = """
            <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                           xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
              <soap:Header>
                <eb:Messaging>
                  <eb:UserMessage>
                    <eb:MessageInfo>
                      <eb:MessageId>msg-001</eb:MessageId>
                    </eb:MessageInfo>
                    <eb:PartyInfo>
                      <eb:From><eb:PartyId>PARTNER_A</eb:PartyId></eb:From>
                      <eb:To><eb:PartyId>OUR_ID</eb:PartyId></eb:To>
                    </eb:PartyInfo>
                    <eb:CollaborationInfo>
                      <eb:ConversationId>conv-1</eb:ConversationId>
                      <eb:Action>invoice.edi</eb:Action>
                    </eb:CollaborationInfo>
                  </eb:UserMessage>
                </eb:Messaging>
              </soap:Header>
              <soap:Body>
                <Payload>SGVsbG8gV29ybGQ=</Payload>
              </soap:Body>
            </soap:Envelope>
            """;

    @BeforeEach
    void setUp() {
        partnershipRepository = mock(As2PartnershipRepository.class);
        messageRepository = mock(As2MessageRepository.class);
        processor = new As4MessageProcessor(partnershipRepository, messageRepository);

        partnership = new As2Partnership();
        partnership.setOurAs2Id("OUR_ID");
        partnership.setPartnerAs2Id("PARTNER_A");
        partnership.setSigningAlgorithm("SHA256");
    }

    @Test
    void process_nullSoapBody_returnsFailure() {
        var result = processor.process(null);

        assertFalse(result.success());
        assertEquals("Empty SOAP body", result.errorReason());
    }

    @Test
    void process_emptySoapBody_returnsFailure() {
        var result = processor.process("   ");

        assertFalse(result.success());
        assertEquals("Empty SOAP body", result.errorReason());
    }

    @Test
    void process_missingMessageId_returnsFailure() {
        String soap = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <soap:Header><eb:Messaging><eb:UserMessage>
                    <eb:MessageInfo></eb:MessageInfo>
                    <eb:PartyInfo>
                      <eb:From><eb:PartyId>PARTNER_A</eb:PartyId></eb:From>
                      <eb:To><eb:PartyId>OUR_ID</eb:PartyId></eb:To>
                    </eb:PartyInfo>
                  </eb:UserMessage></eb:Messaging></soap:Header>
                  <soap:Body><Payload>SGVsbG8=</Payload></soap:Body>
                </soap:Envelope>
                """;

        var result = processor.process(soap);

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("Missing eb:MessageId"));
    }

    @Test
    void process_missingFromPartyId_returnsFailure() {
        String soap = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <soap:Header><eb:Messaging><eb:UserMessage>
                    <eb:MessageInfo><eb:MessageId>msg-002</eb:MessageId></eb:MessageInfo>
                    <eb:PartyInfo>
                      <eb:To><eb:PartyId>OUR_ID</eb:PartyId></eb:To>
                    </eb:PartyInfo>
                  </eb:UserMessage></eb:Messaging></soap:Header>
                  <soap:Body><Payload>SGVsbG8=</Payload></soap:Body>
                </soap:Envelope>
                """;

        when(messageRepository.findByMessageId("msg-002")).thenReturn(Optional.empty());

        var result = processor.process(soap);

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("Missing eb:From PartyId"));
    }

    @Test
    void process_missingToPartyId_returnsFailure() {
        String soap = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <soap:Header><eb:Messaging><eb:UserMessage>
                    <eb:MessageInfo><eb:MessageId>msg-003</eb:MessageId></eb:MessageInfo>
                    <eb:PartyInfo>
                      <eb:From><eb:PartyId>PARTNER_A</eb:PartyId></eb:From>
                    </eb:PartyInfo>
                  </eb:UserMessage></eb:Messaging></soap:Header>
                  <soap:Body><Payload>SGVsbG8=</Payload></soap:Body>
                </soap:Envelope>
                """;

        when(messageRepository.findByMessageId("msg-003")).thenReturn(Optional.empty());

        var result = processor.process(soap);

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("Missing eb:To PartyId"));
    }

    @Test
    void process_duplicateMessageId_returnsFailure() {
        when(messageRepository.findByMessageId("msg-001")).thenReturn(Optional.of(new As2Message()));

        var result = processor.process(VALID_SOAP);

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("Duplicate MessageId"));
        assertTrue(result.errorReason().contains("msg-001"));
    }

    @Test
    void process_unknownPartner_returnsFailure() {
        when(messageRepository.findByMessageId("msg-001")).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.empty());

        var result = processor.process(VALID_SOAP);

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("Unknown trading partner"));
        assertTrue(result.errorReason().contains("PARTNER_A"));
    }

    @Test
    void process_toPartyMismatch_returnsFailure() {
        partnership.setOurAs2Id("DIFFERENT_ID");
        when(messageRepository.findByMessageId("msg-001")).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));

        var result = processor.process(VALID_SOAP);

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("To PartyId does not match"));
    }

    @Test
    void process_missingPayload_returnsFailure() {
        String soap = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <soap:Header><eb:Messaging><eb:UserMessage>
                    <eb:MessageInfo><eb:MessageId>msg-004</eb:MessageId></eb:MessageInfo>
                    <eb:PartyInfo>
                      <eb:From><eb:PartyId>PARTNER_A</eb:PartyId></eb:From>
                      <eb:To><eb:PartyId>OUR_ID</eb:PartyId></eb:To>
                    </eb:PartyInfo>
                  </eb:UserMessage></eb:Messaging></soap:Header>
                  <soap:Body></soap:Body>
                </soap:Envelope>
                """;

        when(messageRepository.findByMessageId("msg-004")).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));

        var result = processor.process(soap);

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("No payload found"));
    }

    @Test
    void process_invalidBase64Payload_returnsFailure() {
        String soap = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <soap:Header><eb:Messaging><eb:UserMessage>
                    <eb:MessageInfo><eb:MessageId>msg-005</eb:MessageId></eb:MessageInfo>
                    <eb:PartyInfo>
                      <eb:From><eb:PartyId>PARTNER_A</eb:PartyId></eb:From>
                      <eb:To><eb:PartyId>OUR_ID</eb:PartyId></eb:To>
                    </eb:PartyInfo>
                  </eb:UserMessage></eb:Messaging></soap:Header>
                  <soap:Body><Payload>!!!not-valid-base64!!!</Payload></soap:Body>
                </soap:Envelope>
                """;

        when(messageRepository.findByMessageId("msg-005")).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));

        var result = processor.process(soap);

        assertFalse(result.success());
        assertTrue(result.errorReason().contains("Invalid Base64 payload"));
    }

    @Test
    void process_validSoap_returnsSuccess() {
        when(messageRepository.findByMessageId("msg-001")).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = processor.process(VALID_SOAP);

        assertTrue(result.success());
        assertNull(result.errorReason());
        assertNotNull(result.message());
        assertNotNull(result.partnership());
        assertNotNull(result.payload());
        assertEquals("Hello World", new String(result.payload()));
        assertEquals("invoice.edi", result.filename());
        assertEquals("conv-1", result.conversationId());
        assertEquals(partnership, result.partnership());

        verify(messageRepository).save(any(As2Message.class));
    }

    @Test
    void process_actionWithoutExtension_usesMessageIdForFilename() {
        String soap = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <soap:Header><eb:Messaging><eb:UserMessage>
                    <eb:MessageInfo><eb:MessageId>msg-006</eb:MessageId></eb:MessageInfo>
                    <eb:PartyInfo>
                      <eb:From><eb:PartyId>PARTNER_A</eb:PartyId></eb:From>
                      <eb:To><eb:PartyId>OUR_ID</eb:PartyId></eb:To>
                    </eb:PartyInfo>
                    <eb:CollaborationInfo>
                      <eb:ConversationId>conv-2</eb:ConversationId>
                      <eb:Action>processInvoice</eb:Action>
                    </eb:CollaborationInfo>
                  </eb:UserMessage></eb:Messaging></soap:Header>
                  <soap:Body><Payload>SGVsbG8=</Payload></soap:Body>
                </soap:Envelope>
                """;

        when(messageRepository.findByMessageId("msg-006")).thenReturn(Optional.empty());
        when(partnershipRepository.findByPartnerAs2IdAndActiveTrue("PARTNER_A"))
                .thenReturn(Optional.of(partnership));
        when(messageRepository.save(any(As2Message.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = processor.process(soap);

        assertTrue(result.success());
        // Action "processInvoice" has no dot, so filename falls back to messageId + ".dat"
        assertEquals("msg-006.dat", result.filename());
    }
}
