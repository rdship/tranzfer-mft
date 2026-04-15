package com.filetransfer.as2.controller;

import com.filetransfer.as2.routing.As2RoutingHandler;
import com.filetransfer.as2.service.As4MessageProcessor;
import com.filetransfer.as2.service.MdnGenerator;
import com.filetransfer.shared.repository.integration.As2MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * AS4 Inbound Receiver Controller (OASIS ebMS3).
 *
 * Trading partners POST SOAP/ebMS3 envelopes to this endpoint. The controller:
 * 1. Parses the SOAP envelope to extract ebMS3 headers (PartyInfo, MessageInfo)
 * 2. Validates partnership and extracts Base64-encoded payload
 * 3. Routes the received file through the platform's standard FileFlow
 * 4. Returns an ebMS3 Receipt signal
 *
 * Endpoint: POST /as4/receive
 */
@Slf4j
@RestController
@RequestMapping("/as4")
@RequiredArgsConstructor
public class As4InboundController {

    private final As4MessageProcessor messageProcessor;
    private final MdnGenerator mdnGenerator;
    private final As2RoutingHandler routingHandler;
    private final As2MessageRepository messageRepository;

    /**
     * Receive an inbound AS4 (ebMS3) message from a trading partner.
     */
    @PostMapping(value = "/receive",
            consumes = {MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE, "application/soap+xml"})
    public ResponseEntity<String> receiveAs4Message(@RequestBody String soapEnvelope) {

        log.info("AS4 message received ({} bytes)", soapEnvelope.length());

        // Process the SOAP envelope
        As4MessageProcessor.ProcessingResult result = messageProcessor.process(soapEnvelope);

        if (!result.success()) {
            log.warn("AS4 message rejected: {}", result.errorReason());
            String errorSignal = mdnGenerator.generateAs4Error(null, "EBMS:0004", result.errorReason());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_XML)
                    .body(errorSignal);
        }

        // Route the file through the platform's standard flow (async)
        routingHandler.routeInboundMessage(
                result.partnership(), result.message(), result.payload(), result.filename());

        // Generate ebMS3 Receipt signal
        String receipt = mdnGenerator.generateAs4Receipt(
                result.message().getMessageId(),
                result.partnership().getOurAs2Id(),
                result.partnership().getPartnerAs2Id());

        // Update message record
        result.message().setMdnReceived(true);
        result.message().setMdnStatus("Receipt");
        result.message().setStatus("ACKNOWLEDGED");
        messageRepository.save(result.message());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_XML)
                .body(receipt);
    }
}
