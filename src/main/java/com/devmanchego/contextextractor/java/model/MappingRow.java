package com.devmanchego.contextextractor.java.model;

/**
 * One row in the DTO ↔ Entity field mapping table.
 * Source is the DTO side, target is the Entity side.
 */
public final class MappingRow {

    public enum Confidence {
        EXPLICIT,   // from @Mapping(source=..., target=...)
        IMPLICIT,   // name-matched (MapStruct default), marked "(implicit)"
        NO_MATCH    // no @Mapper and names differ → "[no match]"
    }

    private final String dtoField;       // dotted path for @Embedded flattening
    private final String dtoType;
    private final String entityField;    // dotted path for @Embedded flattening
    private final String entityType;
    private final Confidence confidence;
    private final boolean dtoOnly;       // field exists in DTO but not in Entity
    private final boolean entityOnly;    // field exists in Entity but not in DTO

    private MappingRow(Builder b) {
        this.dtoField = b.dtoField;
        this.dtoType = b.dtoType;
        this.entityField = b.entityField;
        this.entityType = b.entityType;
        this.confidence = b.confidence;
        this.dtoOnly = b.dtoOnly;
        this.entityOnly = b.entityOnly;
    }

    public String getDtoField() { return dtoField; }
    public String getDtoType() { return dtoType; }
    public String getEntityField() { return entityField; }
    public String getEntityType() { return entityType; }
    public Confidence getConfidence() { return confidence; }
    public boolean isDtoOnly() { return dtoOnly; }
    public boolean isEntityOnly() { return entityOnly; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String dtoField;
        private String dtoType;
        private String entityField;
        private String entityType;
        private Confidence confidence = Confidence.NO_MATCH;
        private boolean dtoOnly = false;
        private boolean entityOnly = false;

        public Builder dtoField(String v) { this.dtoField = v; return this; }
        public Builder dtoType(String v) { this.dtoType = v; return this; }
        public Builder entityField(String v) { this.entityField = v; return this; }
        public Builder entityType(String v) { this.entityType = v; return this; }
        public Builder confidence(Confidence v) { this.confidence = v; return this; }
        public Builder dtoOnly(boolean v) { this.dtoOnly = v; return this; }
        public Builder entityOnly(boolean v) { this.entityOnly = v; return this; }
        public MappingRow build() { return new MappingRow(this); }
    }
}
