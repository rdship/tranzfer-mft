package com.filetransfer.edi.map;

public record MapSummary(
    String mapId,
    String sourceType,
    String targetType,
    String category,    // STANDARD, TRAINED, PARTNER
    String partnerId,
    double confidence
) {}
