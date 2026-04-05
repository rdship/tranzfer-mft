package com.filetransfer.license.catalog;

import lombok.*;
import java.util.*;

/**
 * Pre-built product tiers with default component selections and limits.
 * Used by the CLI installer to show tier options and by the license service
 * to validate licensed features.
 */
public final class ProductTier {

    private ProductTier() {}

    @Data @Builder @AllArgsConstructor
    public static class TierDefinition {
        private final String id;
        private final String name;
        private final String description;
        private final int maxInstances;
        private final int maxConcurrentConnections;
        private final List<String> componentIds;
    }

    public static final TierDefinition STANDARD = TierDefinition.builder()
        .id("STANDARD").name("Standard")
        .description("Core file transfer with SFTP, FTP, web portal, encryption, and admin UI")
        .maxInstances(3).maxConcurrentConnections(500)
        .componentIds(ComponentCatalog.getComponentsForTier("STANDARD").stream()
            .map(ComponentCatalog.Component::getId).toList())
        .build();

    public static final TierDefinition PROFESSIONAL = TierDefinition.builder()
        .id("PROFESSIONAL").name("Professional")
        .description("Standard + AS2/AS4, EDI, Kafka, analytics, DMZ proxy for B2B integration")
        .maxInstances(10).maxConcurrentConnections(2000)
        .componentIds(ComponentCatalog.getComponentsForTier("PROFESSIONAL").stream()
            .map(ComponentCatalog.Component::getId).toList())
        .build();

    public static final TierDefinition ENTERPRISE = TierDefinition.builder()
        .id("ENTERPRISE").name("Enterprise")
        .description("All components — AI classification, sanctions screening, tiered storage, unlimited scale")
        .maxInstances(100).maxConcurrentConnections(10000)
        .componentIds(ComponentCatalog.getComponentsForTier("ENTERPRISE").stream()
            .map(ComponentCatalog.Component::getId).toList())
        .build();

    private static final List<TierDefinition> ALL_TIERS = List.of(STANDARD, PROFESSIONAL, ENTERPRISE);

    public static List<TierDefinition> getAll() { return ALL_TIERS; }

    public static Optional<TierDefinition> findById(String id) {
        return ALL_TIERS.stream().filter(t -> t.getId().equalsIgnoreCase(id)).findFirst();
    }
}
