package com.filetransfer.edi.service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Partner Profile Manager — per-partner mapping configs, rules, and adapters.
 *
 * Each trading partner can have:
 *   - Custom element mappings (e.g., "Acme uses ISA05=ZZ not 01")
 *   - Validation overrides (e.g., "Walmart requires BSN in 856")
 *   - Format preferences (e.g., "Partner X sends EDIFACT, we need X12")
 *   - Element transformations (e.g., "Zero-pad PO numbers to 10 digits")
 *   - Compliance requirements (e.g., "Must include REF segment")
 *
 * When a new partner is onboarded, their sample files are analyzed
 * and a profile is auto-generated with detected conventions.
 */
@Service @Slf4j
public class PartnerProfileManager {

    private final Map<String, PartnerProfile> profiles = new ConcurrentHashMap<>();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PartnerProfile {
        private String partnerId;
        private String partnerName;
        private String preferredFormat;       // X12, EDIFACT, etc.
        private String preferredVersion;      // 005010, D01B, etc.
        private String senderQualifier;       // ISA05 value
        private String senderId;              // ISA06 value
        private String receiverQualifier;     // ISA07 value
        private String receiverId;            // ISA08 value
        private List<String> requiredSegments;
        private List<String> optionalSegments;
        private List<ElementRule> elementRules;
        private List<TransformRule> transformRules;
        private Map<String, String> customMappings;
        private String createdAt;
        private String updatedAt;
        private int documentsSeen;
        private double avgComplianceScore;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ElementRule {
        private String segmentId;
        private int elementIndex;
        private String fieldName;
        private RuleType type;
        private String value;        // Expected value, regex, min, max, etc.
        private String description;

        public enum RuleType {
            REQUIRED, FIXED_VALUE, REGEX, MIN_LENGTH, MAX_LENGTH,
            ENUMERATED, ZERO_PAD, TRIM, DEFAULT_VALUE
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TransformRule {
        private String sourceSegment;
        private int sourceElement;
        private String targetSegment;
        private int targetElement;
        private TransformType type;
        private String parameter;

        public enum TransformType {
            COPY, ZERO_PAD, TRIM, UPPERCASE, LOWERCASE,
            DATE_REFORMAT, SUBSTRING, REPLACE, DEFAULT_IF_EMPTY,
            LOOKUP, CONCATENATE
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProfileAnalysis {
        private String partnerId;
        private String detectedFormat;
        private String detectedVersion;
        private List<String> detectedSegments;
        private Map<String, String> detectedIdentifiers;
        private List<String> conventions;
        private List<String> recommendations;
        private PartnerProfile generatedProfile;
    }

    // === CRUD Operations ===

    public PartnerProfile createProfile(PartnerProfile profile) {
        profile.setCreatedAt(Instant.now().toString());
        profile.setUpdatedAt(Instant.now().toString());
        profiles.put(profile.getPartnerId(), profile);
        log.info("Created partner profile: {}", profile.getPartnerId());
        return profile;
    }

    public PartnerProfile getProfile(String partnerId) {
        return profiles.get(partnerId);
    }

    public List<PartnerProfile> getAllProfiles() {
        return new ArrayList<>(profiles.values());
    }

    public PartnerProfile updateProfile(String partnerId, PartnerProfile update) {
        update.setPartnerId(partnerId);
        update.setUpdatedAt(Instant.now().toString());
        PartnerProfile existing = profiles.get(partnerId);
        if (existing != null) update.setCreatedAt(existing.getCreatedAt());
        profiles.put(partnerId, update);
        return update;
    }

    public boolean deleteProfile(String partnerId) {
        return profiles.remove(partnerId) != null;
    }

    // === Auto-Profile from Sample ===

    /**
     * Analyze a sample EDI document and auto-generate a partner profile.
     * This is the "upload a sample and get a working config in minutes" feature.
     */
    public ProfileAnalysis analyzeAndCreateProfile(String partnerId, String partnerName,
                                                     String sampleContent, String format) {
        List<String> conventions = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        Map<String, String> identifiers = new LinkedHashMap<>();
        List<String> detectedSegments = new ArrayList<>();
        List<ElementRule> rules = new ArrayList<>();
        List<TransformRule> transforms = new ArrayList<>();

        String detectedFormat = format;
        String detectedVersion = "";

        // Parse the sample
        String[] segments;
        if ("X12".equalsIgnoreCase(format) || sampleContent.contains("ISA*")) {
            detectedFormat = "X12";
            segments = sampleContent.split("~");

            for (String rawSeg : segments) {
                String seg = rawSeg.trim();
                if (seg.isEmpty()) continue;
                String[] elems = seg.split("\\*", -1);
                String segId = elems[0];
                detectedSegments.add(segId);

                switch (segId) {
                    case "ISA" -> {
                        if (elems.length > 5) {
                            identifiers.put("senderQualifier", elems[5].trim());
                            conventions.add("Sender qualifier: " + elems[5].trim());
                        }
                        if (elems.length > 6) identifiers.put("senderId", elems[6].trim());
                        if (elems.length > 7) {
                            identifiers.put("receiverQualifier", elems[7].trim());
                            conventions.add("Receiver qualifier: " + elems[7].trim());
                        }
                        if (elems.length > 8) identifiers.put("receiverId", elems[8].trim());
                        if (elems.length > 12) {
                            detectedVersion = elems[12].trim();
                            conventions.add("X12 version: " + detectedVersion);
                        }
                        // Detect padding conventions
                        if (elems.length > 6 && elems[6].trim().length() == 15) {
                            conventions.add("ISA06 padded to 15 characters (standard)");
                        }
                    }
                    case "GS" -> {
                        if (elems.length > 1) {
                            conventions.add("Functional group code: " + elems[1]);
                            identifiers.put("functionalCode", elems[1]);
                        }
                        if (elems.length > 8) {
                            conventions.add("GS version: " + elems[8]);
                        }
                    }
                    case "ST" -> {
                        if (elems.length > 1) identifiers.put("transactionType", elems[1]);
                    }
                    case "NM1" -> {
                        // Detect NM1 entity coding style
                        if (elems.length > 8) {
                            String qualifier = elems[8];
                            if (!qualifier.isEmpty()) {
                                conventions.add("Uses NM1 ID qualifier: " + qualifier);
                            }
                        }
                    }
                    default -> {}
                }

                // Detect element lengths for padding rules
                for (int i = 1; i < elems.length; i++) {
                    String val = elems[i];
                    // If all digits and leading zeros, likely zero-padded
                    if (val.matches("0\\d+")) {
                        rules.add(ElementRule.builder()
                                .segmentId(segId).elementIndex(i)
                                .type(ElementRule.RuleType.ZERO_PAD)
                                .value(String.valueOf(val.length()))
                                .description("Zero-pad " + segId + "*" + String.format("%02d", i) + " to " + val.length() + " digits")
                                .build());
                        conventions.add(segId + " element " + i + " is zero-padded to " + val.length() + " chars");
                    }
                }
            }
        } else if ("EDIFACT".equalsIgnoreCase(format) || sampleContent.contains("UNB+")) {
            detectedFormat = "EDIFACT";
            segments = sampleContent.split("'");
            for (String rawSeg : segments) {
                String seg = rawSeg.trim();
                if (seg.isEmpty()) continue;
                String[] parts = seg.split("\\+", -1);
                detectedSegments.add(parts[0]);

                if ("UNB".equals(parts[0])) {
                    if (parts.length > 1) {
                        String[] syntax = parts[1].split(":");
                        detectedVersion = syntax.length > 0 ? syntax[0] : "";
                        conventions.add("Syntax identifier: " + detectedVersion);
                    }
                    if (parts.length > 2) identifiers.put("senderId", parts[2]);
                    if (parts.length > 3) identifiers.put("receiverId", parts[3]);
                }
            }
        } else {
            // Line-based
            segments = sampleContent.split("\n");
            for (String line : segments) {
                if (!line.trim().isEmpty()) {
                    String id = line.contains("|") ? line.split("\\|")[0] : line.substring(0, Math.min(3, line.length()));
                    detectedSegments.add(id);
                }
            }
        }

        // Generate recommendations
        if (detectedSegments.isEmpty()) {
            recommendations.add("Sample appears empty — provide a complete document");
        } else {
            recommendations.add("Detected " + detectedSegments.size() + " segments in " + detectedFormat + " format");
            recommendations.add("Profile auto-generated — review and adjust rules as needed");
            if (rules.isEmpty()) {
                recommendations.add("No special formatting rules detected — using defaults");
            } else {
                recommendations.add(rules.size() + " formatting rules auto-detected");
            }
        }

        // Build the profile
        PartnerProfile profile = PartnerProfile.builder()
                .partnerId(partnerId).partnerName(partnerName)
                .preferredFormat(detectedFormat).preferredVersion(detectedVersion)
                .senderQualifier(identifiers.getOrDefault("senderQualifier", ""))
                .senderId(identifiers.getOrDefault("senderId", ""))
                .receiverQualifier(identifiers.getOrDefault("receiverQualifier", ""))
                .receiverId(identifiers.getOrDefault("receiverId", ""))
                .requiredSegments(new ArrayList<>(new LinkedHashSet<>(detectedSegments)))
                .elementRules(rules).transformRules(transforms)
                .customMappings(new LinkedHashMap<>())
                .createdAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .documentsSeen(1).avgComplianceScore(0)
                .build();

        // Save it
        profiles.put(partnerId, profile);

        return ProfileAnalysis.builder()
                .partnerId(partnerId).detectedFormat(detectedFormat)
                .detectedVersion(detectedVersion)
                .detectedSegments(new ArrayList<>(new LinkedHashSet<>(detectedSegments)))
                .detectedIdentifiers(identifiers)
                .conventions(conventions).recommendations(recommendations)
                .generatedProfile(profile).build();
    }

    // === Apply Profile (transform outgoing document) ===

    /**
     * Apply partner-specific rules to an outgoing EDI document.
     */
    public String applyProfile(String partnerId, String content) {
        PartnerProfile profile = profiles.get(partnerId);
        if (profile == null) return content;

        String result = content;

        // Apply transform rules
        for (TransformRule rule : (profile.getTransformRules() != null ? profile.getTransformRules() : List.<TransformRule>of())) {
            result = applyTransform(result, rule);
        }

        // Apply element rules (zero-padding, defaults, etc.)
        for (ElementRule rule : (profile.getElementRules() != null ? profile.getElementRules() : List.<ElementRule>of())) {
            result = applyElementRule(result, rule);
        }

        return result;
    }

    private String applyTransform(String content, TransformRule rule) {
        // Apply segment-level transformations
        return content; // Extensible — implementations per TransformType
    }

    private String applyElementRule(String content, ElementRule rule) {
        if (rule.getType() == ElementRule.RuleType.ZERO_PAD) {
            // Find the segment and zero-pad the element
            String[] segs = content.split("~");
            for (int i = 0; i < segs.length; i++) {
                String seg = segs[i].trim();
                if (seg.startsWith(rule.getSegmentId() + "*")) {
                    String[] elems = seg.split("\\*", -1);
                    if (rule.getElementIndex() < elems.length) {
                        String val = elems[rule.getElementIndex()].trim();
                        int targetLen = Integer.parseInt(rule.getValue());
                        if (val.length() < targetLen && val.matches("\\d+")) {
                            elems[rule.getElementIndex()] = String.format("%" + targetLen + "s", val).replace(' ', '0');
                            segs[i] = String.join("*", elems);
                        }
                    }
                }
            }
            content = String.join("~", segs);
        }
        return content;
    }
}
