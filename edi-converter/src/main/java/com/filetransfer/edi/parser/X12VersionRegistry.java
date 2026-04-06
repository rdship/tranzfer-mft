package com.filetransfer.edi.parser;

import lombok.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class X12VersionRegistry {

    @Data @AllArgsConstructor
    public static class VersionDef {
        private String version;
        private String txnType;
        private String implementationGuide;  // e.g., "005010X222A2"
        private Set<String> requiredSegments;
        private Set<String> conditionalSegments;
        private Map<String, Integer> maxRepeats;  // segment -> max occurrences
    }

    private static final Map<String, VersionDef> VERSIONS = new HashMap<>();

    static {
        // 005010 837P (Professional)
        VERSIONS.put("005010:837", new VersionDef("005010", "837", "005010X222A2",
            Set.of("ISA","GS","ST","BHT","HL","NM1","CLM","SV1","SE","GE","IEA"),
            Set.of("SBR","PAT","DTP","REF","N3","N4","PER","CLM","SV2","DMG"),
            Map.of("HL", 999, "NM1", 999, "SV1", 999, "DTP", 100, "REF", 100)));

        // 005010 835
        VERSIONS.put("005010:835", new VersionDef("005010", "835", "005010X221A1",
            Set.of("ISA","GS","ST","BPR","N1","LX","CLP","SE","GE","IEA"),
            Set.of("TRN","DTM","REF","SVC","AMT"),
            Map.of("CLP", 99999, "SVC", 999, "N1", 10)));

        // 005010 850
        VERSIONS.put("005010:850", new VersionDef("005010", "850", "005010",
            Set.of("ISA","GS","ST","BEG","PO1","SE","GE","IEA"),
            Set.of("N1","N3","N4","PER","PID","CTT","REF","DTM"),
            Map.of("PO1", 100000, "N1", 200)));

        // 005010 810
        VERSIONS.put("005010:810", new VersionDef("005010", "810", "005010",
            Set.of("ISA","GS","ST","BIG","IT1","TDS","SE","GE","IEA"),
            Set.of("N1","N3","N4","REF","DTM"),
            Map.of("IT1", 200000)));

        // 005010 856
        VERSIONS.put("005010:856", new VersionDef("005010", "856", "005010",
            Set.of("ISA","GS","ST","BSN","HL","SE","GE","IEA"),
            Set.of("TD1","TD5","REF","DTM","SN1","LIN","PID"),
            Map.of("HL", 200000)));

        // 005010 270
        VERSIONS.put("005010:270", new VersionDef("005010", "270", "005010X279A1",
            Set.of("ISA","GS","ST","BHT","HL","NM1","SE","GE","IEA"),
            Set.of("TRN","DTP","EQ","REF"),
            Map.of("HL", 999, "NM1", 999)));

        // 005010 271
        VERSIONS.put("005010:271", new VersionDef("005010", "271", "005010X279A1",
            Set.of("ISA","GS","ST","BHT","HL","NM1","SE","GE","IEA"),
            Set.of("TRN","DTP","EB","REF","AAA"),
            Map.of("HL", 999, "EB", 999)));

        // 005010 997
        VERSIONS.put("005010:997", new VersionDef("005010", "997", "005010",
            Set.of("ISA","GS","ST","AK1","AK9","SE","GE","IEA"),
            Set.of("AK2","AK3","AK4"),
            Map.of("AK2", 999)));

        // 005010 834
        VERSIONS.put("005010:834", new VersionDef("005010", "834", "005010X220A1",
            Set.of("ISA","GS","ST","BGN","N1","INS","SE","GE","IEA"),
            Set.of("NM1","N3","N4","REF","DTP","HD","DMG"),
            Map.of("INS", 99999, "NM1", 10)));

        // Also register 004010 versions (less strict)
        VERSIONS.put("004010:837", new VersionDef("004010", "837", "004010X098A1",
            Set.of("ISA","GS","ST","BHT","HL","NM1","CLM","SE","GE","IEA"),
            Set.of("SBR","DTP","REF"),
            Map.of()));
    }

    public VersionDef getVersionDef(String version, String txnType) {
        if (version == null || txnType == null) return null;
        VersionDef exact = VERSIONS.get(version + ":" + txnType);
        if (exact != null) return exact;
        // Try without minor version
        String major = version.length() >= 5 ? version.substring(0, 5) : version;
        return VERSIONS.get(major + ":" + txnType);
    }

    public boolean hasVersionDef(String version, String txnType) {
        return getVersionDef(version, txnType) != null;
    }
}
