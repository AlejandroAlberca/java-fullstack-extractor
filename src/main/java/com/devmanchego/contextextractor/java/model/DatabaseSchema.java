package com.devmanchego.contextextractor.java.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Complete unified schema: tables merged from live DB / SQL migrations / JPA entities,
 * plus the relationship graph and any warnings about degraded extraction.
 */
public final class DatabaseSchema {

    private final Map<String, SchemaTable> tablesByName; // key = qualifiedName
    private final List<SchemaRelationship> relationships;
    private final Set<DataProvenance> sourcesUsed;
    private final List<String> warnings; // e.g. "Could not connect to database — static analysis only"

    public DatabaseSchema(Map<String, SchemaTable> tablesByName, List<SchemaRelationship> relationships,
                          Set<DataProvenance> sourcesUsed, List<String> warnings) {
        this.tablesByName = Map.copyOf(tablesByName);
        this.relationships = List.copyOf(relationships);
        this.sourcesUsed = Set.copyOf(sourcesUsed);
        this.warnings = List.copyOf(warnings);
    }

    public Map<String, SchemaTable> getTablesByName() { return tablesByName; }
    public List<SchemaRelationship> getRelationships() { return relationships; }
    public Set<DataProvenance> getSourcesUsed() { return sourcesUsed; }
    public List<String> getWarnings() { return warnings; }

    public boolean isEmpty() { return tablesByName.isEmpty(); }

    /** Relationships where the given table is the FK owner (outgoing references). */
    public List<SchemaRelationship> outgoingFrom(String qualifiedTableName) {
        return relationships.stream()
                .filter(r -> r.getFromTable().equals(qualifiedTableName))
                .toList();
    }

    /** Relationships where the given table is the referenced side (incoming references). */
    public List<SchemaRelationship> incomingTo(String qualifiedTableName) {
        return relationships.stream()
                .filter(r -> r.getToTable().equals(qualifiedTableName))
                .toList();
    }
}
