package com.filetransfer.as2.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MdnGeneratorTest {

    private MdnGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new MdnGenerator();
    }

    // --- generateSuccess tests ---

    @Test
    void generateSuccess_bodyContainsProcessedSuccessfully() {
        var mdn = generator.generateSuccess("msg-001", "OUR_ID", "PARTNER_ID", "abc123, SHA-256");

        assertTrue(mdn.body().contains("processed successfully"),
                "MDN body should indicate successful processing");
    }

    @Test
    void generateSuccess_contentTypeStartsWithMultipartReport() {
        var mdn = generator.generateSuccess("msg-001", "OUR_ID", "PARTNER_ID", "abc123, SHA-256");

        assertTrue(mdn.contentType().startsWith("multipart/report"),
                "Content-Type should start with multipart/report");
    }

    @Test
    void generateSuccess_isMarkedAsSuccess() {
        var mdn = generator.generateSuccess("msg-001", "OUR_ID", "PARTNER_ID", "abc123, SHA-256");

        assertTrue(mdn.success());
    }

    @Test
    void generateSuccess_containsMic() {
        String mic = "base64hash==, SHA-256";
        var mdn = generator.generateSuccess("msg-001", "OUR_ID", "PARTNER_ID", mic);

        assertTrue(mdn.body().contains(mic),
                "MDN body should contain the MIC value");
    }

    @Test
    void generateSuccess_containsOriginalMessageId() {
        var mdn = generator.generateSuccess("msg-001", "OUR_ID", "PARTNER_ID", "mic-value");

        assertTrue(mdn.body().contains("msg-001"),
                "MDN body should reference original message ID");
    }

    @Test
    void generateSuccess_containsDispositionProcessed() {
        var mdn = generator.generateSuccess("msg-001", "OUR_ID", "PARTNER_ID", "mic-value");

        assertTrue(mdn.body().contains("Disposition: automatic-action/MDN-sent-automatically; processed"),
                "MDN should contain processed disposition");
    }

    @Test
    void generateSuccess_messageIdIsGenerated() {
        var mdn = generator.generateSuccess("msg-001", "OUR_ID", "PARTNER_ID", "mic-value");

        assertNotNull(mdn.messageId());
        assertTrue(mdn.messageId().startsWith("<") && mdn.messageId().endsWith(">"),
                "MDN Message-ID should be wrapped in angle brackets");
    }

    // --- generateError tests ---

    @Test
    void generateError_bodyContainsProcessingFailed() {
        var mdn = generator.generateError("msg-002", "OUR_ID", "PARTNER_ID", "Invalid signature");

        assertTrue(mdn.body().contains("processing failed"),
                "Error MDN body should indicate processing failure");
    }

    @Test
    void generateError_bodyContainsErrorDescription() {
        var mdn = generator.generateError("msg-002", "OUR_ID", "PARTNER_ID", "Invalid signature");

        assertTrue(mdn.body().contains("Invalid signature"),
                "Error MDN body should contain the error description");
    }

    @Test
    void generateError_isMarkedAsNotSuccess() {
        var mdn = generator.generateError("msg-002", "OUR_ID", "PARTNER_ID", "Invalid signature");

        assertFalse(mdn.success());
    }

    @Test
    void generateError_contentTypeIsMultipartReport() {
        var mdn = generator.generateError("msg-002", "OUR_ID", "PARTNER_ID", "Invalid signature");

        assertTrue(mdn.contentType().startsWith("multipart/report"),
                "Error MDN Content-Type should be multipart/report");
    }

    @Test
    void generateError_handlesNullOriginalMessageId() {
        var mdn = generator.generateError(null, "OUR_ID", "PARTNER_ID", "Some error");

        assertFalse(mdn.success());
        assertTrue(mdn.body().contains("unknown"),
                "Should handle null original message ID gracefully");
    }

    // --- generateAs4Receipt tests ---

    @Test
    void generateAs4Receipt_containsRefToMessageId() {
        String receipt = generator.generateAs4Receipt("msg-003", "OUR_PARTY", "PARTNER_PARTY");

        assertTrue(receipt.contains("<eb:RefToMessageId>msg-003</eb:RefToMessageId>"),
                "AS4 receipt should reference the original message ID");
    }

    @Test
    void generateAs4Receipt_containsNonRepudiationInformation() {
        String receipt = generator.generateAs4Receipt("msg-003", "OUR_PARTY", "PARTNER_PARTY");

        assertTrue(receipt.contains("NonRepudiationInformation"),
                "AS4 receipt should contain NonRepudiationInformation element");
    }

    @Test
    void generateAs4Receipt_isValidXml() {
        String receipt = generator.generateAs4Receipt("msg-003", "OUR_PARTY", "PARTNER_PARTY");

        assertTrue(receipt.contains("<?xml version=\"1.0\""),
                "AS4 receipt should have XML declaration");
        assertTrue(receipt.contains("<soap:Envelope"),
                "AS4 receipt should have SOAP envelope");
        assertTrue(receipt.contains("</soap:Envelope>"),
                "AS4 receipt should have closing SOAP envelope tag");
    }

    @Test
    void generateAs4Receipt_containsSignalMessage() {
        String receipt = generator.generateAs4Receipt("msg-003", "OUR_PARTY", "PARTNER_PARTY");

        assertTrue(receipt.contains("<eb:SignalMessage>"),
                "AS4 receipt should contain SignalMessage element");
    }

    @Test
    void generateAs4Receipt_containsTimestamp() {
        String receipt = generator.generateAs4Receipt("msg-003", "OUR_PARTY", "PARTNER_PARTY");

        assertTrue(receipt.contains("<eb:Timestamp>"),
                "AS4 receipt should contain a Timestamp element");
    }

    // --- generateAs4Error tests ---

    @Test
    void generateAs4Error_containsErrorCode() {
        String error = generator.generateAs4Error("msg-004", "EBMS:0004", "Decryption failed");

        assertTrue(error.contains("EBMS:0004"),
                "AS4 error should contain the error code");
    }

    @Test
    void generateAs4Error_containsErrorDescription() {
        String error = generator.generateAs4Error("msg-004", "EBMS:0004", "Decryption failed");

        assertTrue(error.contains("Decryption failed"),
                "AS4 error should contain the error description");
    }

    @Test
    void generateAs4Error_isValidXml() {
        String error = generator.generateAs4Error("msg-004", "EBMS:0004", "Decryption failed");

        assertTrue(error.contains("<?xml version=\"1.0\""),
                "AS4 error should have XML declaration");
        assertTrue(error.contains("<soap:Envelope"),
                "AS4 error should have SOAP envelope");
        assertTrue(error.contains("</soap:Envelope>"),
                "AS4 error should have closing SOAP envelope tag");
    }

    @Test
    void generateAs4Error_containsRefToMessageId() {
        String error = generator.generateAs4Error("msg-004", "EBMS:0004", "Decryption failed");

        assertTrue(error.contains("<eb:RefToMessageId>msg-004</eb:RefToMessageId>"),
                "AS4 error should reference the original message ID");
    }

    @Test
    void generateAs4Error_handlesNullOriginalMessageId() {
        String error = generator.generateAs4Error(null, "EBMS:0001", "Some error");

        assertTrue(error.contains("<eb:RefToMessageId>unknown</eb:RefToMessageId>"),
                "Should use 'unknown' when original message ID is null");
    }

    @Test
    void generateAs4Error_containsSeverityFailure() {
        String error = generator.generateAs4Error("msg-005", "EBMS:0004", "Error");

        assertTrue(error.contains("severity=\"failure\""),
                "AS4 error should have severity=failure attribute");
    }
}
