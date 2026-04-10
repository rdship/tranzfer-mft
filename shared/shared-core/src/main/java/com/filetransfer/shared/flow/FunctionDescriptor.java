package com.filetransfer.shared.flow;

/**
 * Metadata about a flow function for discovery, catalog, and import/export.
 */
public record FunctionDescriptor(
    String name,
    String version,
    String category,    // TRANSFORM, VALIDATE, ROUTE, DELIVER, GATE
    String scope,       // SYSTEM, TENANT, PARTNER
    String author,
    boolean exportable,
    String description
) {}
