package com.devmanchego.contextextractor.java.model;

import java.util.List;
import java.util.Objects;

/**
 * Represents a single row in the traceability table: UI field → DTO → Entity → Database column.
 * Captures the transformation rule and confidence level.
 */
public final class TraceabilityRow {

    public enum MappingType {
        DIRECT,           // Names match across all layers
        RENAMED,          // Mapped but names differ (MapStruct @Mapping)
        TRANSIENT,        // Exists in DTO but not in Entity (not persisted)
        ENUM_CONSTANT,    // Entity field is an enum; DTO uses string representation
        CALCULATED,       // Derived from other fields (MapStruct expression)
        UNMAPPED          // No matching field in one or more layers
    }

    public enum Confidence {
        HIGH,             // Explicit @Mapping or same name across all layers
        MEDIUM,           // Name matches with minor normalization
        LOW,              // Best-effort guess, requires manual review
        UNKNOWN           // Could not determine
    }

    private final String uiLabel;              // From <label> or aria-label
    private final String uiFieldName;          // From formControlName (if available)
    private final String dtoFieldName;         // From TsFieldInfo or DTO FieldInfo
    private final String dtoType;              // TypeScript type or Java type

    private final String entityFieldName;      // JPA entity field name
    private final String entityType;           // Entity field type
    private final String columnName;           // @Column(name=) or snake_case of entity field
    private final String columnType;           // SQL type (inferred from Java type)

    private final MappingType mappingType;
    private final Confidence confidence;
    private final List<String> validationRules; // @NotNull, @Email, @Size(...), etc.
    private final boolean nullable;
    private final boolean primaryKey;
    private final boolean generatedValue;
    private final String transformationRule;   // Human-readable description of transformation
    private final List<CalculationEvidence> calculationEvidence; // How/where field is calculated

    // Private constructor; use builder
    private TraceabilityRow(Builder b) {
        this.uiLabel = b.uiLabel;
        this.uiFieldName = b.uiFieldName;
        this.dtoFieldName = b.dtoFieldName;
        this.dtoType = b.dtoType;
        this.entityFieldName = b.entityFieldName;
        this.entityType = b.entityType;
        this.columnName = b.columnName;
        this.columnType = b.columnType;
        this.mappingType = b.mappingType;
        this.confidence = b.confidence;
        this.validationRules = List.copyOf(b.validationRules);
        this.nullable = b.nullable;
        this.primaryKey = b.primaryKey;
        this.generatedValue = b.generatedValue;
        this.transformationRule = b.transformationRule;
        this.calculationEvidence = List.copyOf(b.calculationEvidence);
    }

    // Getters
    public String getUiLabel() { return uiLabel; }
    public String getUiFieldName() { return uiFieldName; }
    public String getDtoFieldName() { return dtoFieldName; }
    public String getDtoType() { return dtoType; }
    public String getEntityFieldName() { return entityFieldName; }
    public String getEntityType() { return entityType; }
    public String getColumnName() { return columnName; }
    public String getColumnType() { return columnType; }
    public MappingType getMappingType() { return mappingType; }
    public Confidence getConfidence() { return confidence; }
    public List<String> getValidationRules() { return validationRules; }
    public boolean isNullable() { return nullable; }
    public boolean isPrimaryKey() { return primaryKey; }
    public boolean isGeneratedValue() { return generatedValue; }
    public String getTransformationRule() { return transformationRule; }
    public List<CalculationEvidence> getCalculationEvidence() { return calculationEvidence; }

    /**
     * Whether this field has any calculation evidence (Fase 2).
     */
    public boolean hasCalculationEvidence() {
        return !calculationEvidence.isEmpty();
    }

    /**
     * Whether this field's <em>value</em> is computed in both backend and frontend
     * (divergence risk — the "el total no cuadra" signal for an LLM).
     */
    public boolean isCalculatedInMultipleLayers() {
        return calculationEvidence.stream()
                .filter(CalculationEvidence::isValueComputation)
                .map(CalculationEvidence::layer)
                .distinct()
                .count() > 1;
    }

    // Builder
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String uiLabel;
        private String uiFieldName;
        private String dtoFieldName;
        private String dtoType;
        private String entityFieldName;
        private String entityType;
        private String columnName;
        private String columnType;
        private MappingType mappingType = MappingType.UNMAPPED;
        private Confidence confidence = Confidence.UNKNOWN;
        private final List<String> validationRules = new java.util.ArrayList<>();
        private boolean nullable = true;
        private boolean primaryKey = false;
        private boolean generatedValue = false;
        private String transformationRule;
        private final List<CalculationEvidence> calculationEvidence = new java.util.ArrayList<>();

        public Builder uiLabel(String v) { this.uiLabel = v; return this; }
        public Builder uiFieldName(String v) { this.uiFieldName = v; return this; }
        public Builder dtoFieldName(String v) { this.dtoFieldName = v; return this; }
        public Builder dtoType(String v) { this.dtoType = v; return this; }
        public Builder entityFieldName(String v) { this.entityFieldName = v; return this; }
        public Builder entityType(String v) { this.entityType = v; return this; }
        public Builder columnName(String v) { this.columnName = v; return this; }
        public Builder columnType(String v) { this.columnType = v; return this; }
        public Builder mappingType(MappingType v) { this.mappingType = v; return this; }
        public Builder confidence(Confidence v) { this.confidence = v; return this; }
        public Builder validationRule(String v) { this.validationRules.add(v); return this; }
        public Builder nullable(boolean v) { this.nullable = v; return this; }
        public Builder primaryKey(boolean v) { this.primaryKey = v; return this; }
        public Builder generatedValue(boolean v) { this.generatedValue = v; return this; }
        public Builder transformationRule(String v) { this.transformationRule = v; return this; }
        public Builder calculationEvidence(CalculationEvidence v) { this.calculationEvidence.add(v); return this; }
        public Builder calculationEvidences(java.util.Collection<CalculationEvidence> v) { this.calculationEvidence.addAll(v); return this; }

        public TraceabilityRow build() {
            return new TraceabilityRow(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TraceabilityRow that)) return false;
        return Objects.equals(dtoFieldName, that.dtoFieldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dtoFieldName);
    }
}
