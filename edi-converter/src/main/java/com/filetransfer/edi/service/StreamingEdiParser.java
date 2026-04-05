package com.filetransfer.edi.service;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * StAX-style Streaming EDI Parser — processes EDI documents segment-by-segment
 * without loading the entire file into memory. Handles files of any size.
 *
 * Inspired by StAEDI (Java Streaming API for EDI) concepts.
 *
 * Usage:
 *   streamingParser.stream(inputStream, "X12", event -> {
 *       if (event.type == SEGMENT) process(event.segment);
 *       if (event.type == DOCUMENT_END) finish();
 *   });
 *
 * Supports: X12, EDIFACT, HL7, CSV, fixed-width
 * Memory: O(1) per segment — can process 100GB files
 */
@Service @Slf4j
public class StreamingEdiParser {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ParseEvent {
        private EventType type;
        private Segment segment;
        private String format;
        private int segmentNumber;
        private long bytesProcessed;
        private Map<String, String> metadata;

        public enum EventType {
            DOCUMENT_START, SEGMENT, DOCUMENT_END, ERROR
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StreamResult {
        private String format;
        private int totalSegments;
        private long totalBytes;
        private long durationMs;
        private List<String> errors;
        private Map<String, String> metadata;
    }

    /**
     * Stream-parse an EDI document, invoking the callback for each segment.
     * Never loads the entire file into memory.
     */
    public StreamResult stream(InputStream input, String format, Consumer<ParseEvent> callback) {
        long start = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        int segCount = 0;
        long bytesRead = 0;

        try {
            // Emit DOCUMENT_START
            callback.accept(ParseEvent.builder()
                    .type(ParseEvent.EventType.DOCUMENT_START).format(format)
                    .metadata(Map.of("format", format)).build());

            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));

            segCount = switch (format.toUpperCase()) {
                case "X12" -> streamX12(reader, callback, metadata, errors);
                case "EDIFACT" -> streamEdifact(reader, callback, metadata, errors);
                case "HL7" -> streamHl7(reader, callback, metadata, errors);
                case "NACHA" -> streamFixedWidth(reader, callback, metadata, errors, 94);
                case "BAI2" -> streamDelimited(reader, callback, metadata, errors, ",");
                case "FIX" -> streamFix(reader, callback, metadata, errors);
                default -> streamLineByLine(reader, callback, metadata, errors);
            };

            // Emit DOCUMENT_END
            callback.accept(ParseEvent.builder()
                    .type(ParseEvent.EventType.DOCUMENT_END).format(format)
                    .segmentNumber(segCount).metadata(metadata).build());

        } catch (Exception e) {
            errors.add("Stream error: " + e.getMessage());
            callback.accept(ParseEvent.builder()
                    .type(ParseEvent.EventType.ERROR)
                    .metadata(Map.of("error", e.getMessage())).build());
        }

        return StreamResult.builder()
                .format(format).totalSegments(segCount)
                .totalBytes(bytesRead)
                .durationMs(System.currentTimeMillis() - start)
                .errors(errors).metadata(metadata).build();
    }

    /**
     * Stream-parse from a String (convenience for API calls).
     */
    public StreamResult stream(String content, String format, Consumer<ParseEvent> callback) {
        return stream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), format, callback);
    }

    /**
     * Stream-parse and collect into an EdiDocument (for backward compatibility).
     */
    public EdiDocument streamToDocument(InputStream input, String format) {
        List<Segment> segments = new ArrayList<>();
        Map<String, String> meta = new LinkedHashMap<>();

        stream(input, format, event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
            if (event.getMetadata() != null) meta.putAll(event.getMetadata());
        });

        return EdiDocument.builder()
                .sourceFormat(format).segments(segments)
                .senderId(meta.get("senderId")).receiverId(meta.get("receiverId"))
                .documentType(meta.get("documentType")).controlNumber(meta.get("controlNumber"))
                .documentDate(meta.get("documentDate"))
                .build();
    }

    // === X12 Streaming ===
    private int streamX12(BufferedReader reader, Consumer<ParseEvent> callback,
                          Map<String, String> metadata, List<String> errors) throws IOException {
        // Read first line to detect segment terminator
        StringBuilder buffer = new StringBuilder();
        int segCount = 0;
        char segTerminator = '~';
        char elemDelimiter = '*';
        boolean firstSegment = true;

        int ch;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;

            if (firstSegment && buffer.length() == 3 && buffer.toString().equals("ISA") && c == '*') {
                elemDelimiter = c;
            }

            if (c == segTerminator || (c == '\n' && !buffer.toString().contains("~"))) {
                String seg = buffer.toString().trim();
                if (!seg.isEmpty()) {
                    segCount++;
                    Segment segment = parseX12Segment(seg, elemDelimiter);
                    callback.accept(ParseEvent.builder()
                            .type(ParseEvent.EventType.SEGMENT).segment(segment)
                            .segmentNumber(segCount).build());

                    // Extract metadata from ISA/ST
                    if (firstSegment && "ISA".equals(segment.getId())) {
                        List<String> e = segment.getElements();
                        if (e.size() > 5) metadata.put("senderId", e.get(5).trim());
                        if (e.size() > 7) metadata.put("receiverId", e.get(7).trim());
                        if (e.size() > 12) metadata.put("controlNumber", e.get(12).trim());
                        // ISA16 is the component separator; ISA segment terminator follows
                        firstSegment = false;
                    }
                    if ("ST".equals(segment.getId()) && segment.getElements().size() > 0) {
                        metadata.put("documentType", segment.getElements().get(0).trim());
                    }
                }
                buffer.setLength(0);
            } else if (c != '\r') {
                buffer.append(c);
            }
        }

        // Handle trailing segment without terminator
        String remaining = buffer.toString().trim();
        if (!remaining.isEmpty()) {
            segCount++;
            callback.accept(ParseEvent.builder()
                    .type(ParseEvent.EventType.SEGMENT)
                    .segment(parseX12Segment(remaining, elemDelimiter))
                    .segmentNumber(segCount).build());
        }

        return segCount;
    }

    private Segment parseX12Segment(String raw, char delim) {
        String[] parts = raw.split("\\" + delim, -1);
        String id = parts[0].trim();
        List<String> elements = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) elements.add(parts[i]);
        return Segment.builder().id(id).elements(elements).build();
    }

    // === EDIFACT Streaming ===
    private int streamEdifact(BufferedReader reader, Consumer<ParseEvent> callback,
                              Map<String, String> metadata, List<String> errors) throws IOException {
        StringBuilder buffer = new StringBuilder();
        int segCount = 0;
        char segTerminator = '\'';

        int ch;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;
            if (c == segTerminator) {
                String seg = buffer.toString().trim();
                if (!seg.isEmpty()) {
                    segCount++;
                    String[] parts = seg.split("\\+", -1);
                    String id = parts[0];
                    List<String> elements = new ArrayList<>();
                    for (int i = 1; i < parts.length; i++) elements.add(parts[i]);
                    Segment segment = Segment.builder().id(id).elements(elements).build();
                    callback.accept(ParseEvent.builder()
                            .type(ParseEvent.EventType.SEGMENT).segment(segment)
                            .segmentNumber(segCount).build());

                    if ("UNB".equals(id) && parts.length > 3) {
                        metadata.put("senderId", parts[2]);
                        metadata.put("receiverId", parts[3]);
                    }
                    if ("UNH".equals(id) && parts.length > 2) {
                        String[] msgParts = parts[2].split(":");
                        metadata.put("documentType", msgParts[0]);
                    }
                }
                buffer.setLength(0);
            } else if (c != '\r' && c != '\n') {
                buffer.append(c);
            }
        }
        return segCount;
    }

    // === HL7 Streaming ===
    private int streamHl7(BufferedReader reader, Consumer<ParseEvent> callback,
                          Map<String, String> metadata, List<String> errors) throws IOException {
        String line;
        int segCount = 0;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            segCount++;
            String[] fields = line.split("\\|", -1);
            String id = fields[0];
            List<String> elements = new ArrayList<>();
            for (int i = 1; i < fields.length; i++) elements.add(fields[i]);
            Segment segment = Segment.builder().id(id).elements(elements).build();
            callback.accept(ParseEvent.builder()
                    .type(ParseEvent.EventType.SEGMENT).segment(segment)
                    .segmentNumber(segCount).build());

            if ("MSH".equals(id)) {
                if (fields.length > 3) metadata.put("senderId", fields[3]);
                if (fields.length > 9) metadata.put("documentType", fields[9]);
            }
        }
        return segCount;
    }

    // === Fixed-Width Streaming (NACHA) ===
    private int streamFixedWidth(BufferedReader reader, Consumer<ParseEvent> callback,
                                  Map<String, String> metadata, List<String> errors, int recordLength) throws IOException {
        String line;
        int segCount = 0;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            segCount++;
            String recType = line.substring(0, 1);
            String name = switch (recType) {
                case "1" -> "FILE_HEADER"; case "5" -> "BATCH_HEADER";
                case "6" -> "ENTRY_DETAIL"; case "7" -> "ADDENDA";
                case "8" -> "BATCH_CONTROL"; case "9" -> "FILE_CONTROL";
                default -> "RECORD_" + recType;
            };
            Segment segment = Segment.builder().id(name).elements(List.of(line))
                    .namedFields(Map.of("recordType", recType)).build();
            callback.accept(ParseEvent.builder()
                    .type(ParseEvent.EventType.SEGMENT).segment(segment)
                    .segmentNumber(segCount).build());
        }
        return segCount;
    }

    // === Delimited Streaming (BAI2, CSV) ===
    private int streamDelimited(BufferedReader reader, Consumer<ParseEvent> callback,
                                 Map<String, String> metadata, List<String> errors, String delim) throws IOException {
        String line;
        int segCount = 0;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            segCount++;
            String[] parts = line.split(delim, -1);
            String id = parts[0];
            List<String> elements = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) elements.add(parts[i]);
            callback.accept(ParseEvent.builder()
                    .type(ParseEvent.EventType.SEGMENT)
                    .segment(Segment.builder().id(id).elements(elements).build())
                    .segmentNumber(segCount).build());
        }
        return segCount;
    }

    // === FIX Streaming ===
    private int streamFix(BufferedReader reader, Consumer<ParseEvent> callback,
                           Map<String, String> metadata, List<String> errors) throws IOException {
        StringBuilder buffer = new StringBuilder();
        int ch, segCount = 0;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;
            if (c == '\001' || c == '|') { // SOH or pipe
                String field = buffer.toString();
                if (!field.isEmpty()) {
                    segCount++;
                    String[] kv = field.split("=", 2);
                    if (kv.length == 2) {
                        callback.accept(ParseEvent.builder()
                                .type(ParseEvent.EventType.SEGMENT)
                                .segment(Segment.builder().id("Tag" + kv[0]).elements(List.of(kv[1]))
                                        .namedFields(Map.of("tag", kv[0], "value", kv[1])).build())
                                .segmentNumber(segCount).build());
                        if ("35".equals(kv[0])) metadata.put("documentType", kv[1]);
                    }
                }
                buffer.setLength(0);
            } else {
                buffer.append(c);
            }
        }
        return segCount;
    }

    // === Generic line-by-line ===
    private int streamLineByLine(BufferedReader reader, Consumer<ParseEvent> callback,
                                  Map<String, String> metadata, List<String> errors) throws IOException {
        String line;
        int segCount = 0;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            segCount++;
            callback.accept(ParseEvent.builder()
                    .type(ParseEvent.EventType.SEGMENT)
                    .segment(Segment.builder().id("LINE").elements(List.of(line)).build())
                    .segmentNumber(segCount).build());
        }
        return segCount;
    }
}
