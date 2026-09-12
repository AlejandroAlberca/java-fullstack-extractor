package com.devmanchego.contextextractor.java.model;

import java.util.ArrayList;
import java.util.List;

/** A single field on a DTO or Entity class, with metadata extracted from annotations. */
public final class FieldInfo {

    private final String name;
    private final String type;
    private final boolean optional;
    private final boolean transientField;
    private final String columnName;        // from @Column(name=...), null if absent
    private final boolean nullable;         // from @Column(nullable=...)
    private final Integer maxLength;        // from @Column(length=...) or @Size(max=...)
    private final List<String> validationRules; // @NotNull, @NotBlank, @Email, @Size(...)
    private final boolean primaryKey;       // @Id, or a component of an @EmbeddedId
    private final boolean generatedValue;   // @GeneratedValue
    private final boolean unique;           // @Column(unique=true)
    private final String description;       // field-level Javadoc, null if none
    private final List<String> enumValues;  // resolved Java enum constants, empty if field isn't an enum
    private final boolean formula;          // @Formula — DB-computed, never a real physical column
    private final String formulaExpression; // raw @Formula(value=...) text, null if absent or not a formula
    /**
     * Simple name of the {@code @MappedSuperclass} this field was inherited from, or {@code null}
     * when it's declared directly on the entity. Set only for the primary key today — see
     * {@code PersistenceMappingExtractor#collectInheritedFields} — so a reader can tell a locally-
     * declared identifier apart from one pulled up an inheritance chain.
     */
    private final String keyOrigin;

    private FieldInfo(Builder b) {
        this.name = b.name;
        this.type = b.type;
        this.optional = b.optional;
        this.transientField = b.transientField;
        this.columnName = b.columnName;
        this.nullable = b.nullable;
        this.maxLength = b.maxLength;
        this.validationRules = List.copyOf(b.validationRules);
        this.primaryKey = b.primaryKey;
        this.generatedValue = b.generatedValue;
        this.unique = b.unique;
        this.description = b.description;
        this.enumValues = List.copyOf(b.enumValues);
        this.formula = b.formula;
        this.formulaExpression = b.formulaExpression;
        this.keyOrigin = b.keyOrigin;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public boolean isOptional() { return optional; }
    public boolean isTransient() { return transientField; }
    public String getColumnName() { return columnName; }
    public boolean isNullable() { return nullable; }
    public Integer getMaxLength() { return maxLength; }
    public List<String> getValidationRules() { return validationRules; }
    public boolean isPrimaryKey() { return primaryKey; }
    public boolean isGeneratedValue() { return generatedValue; }
    public boolean isUnique() { return unique; }
    public String getDescription() { return description; }
    public List<String> getEnumValues() { return enumValues; }
    public boolean isFormula() { return formula; }
    public String getFormulaExpression() { return formulaExpression; }
    public String getKeyOrigin() { return keyOrigin; }

    /** Returns a copy of this field with a different resolved column name (e.g. @AttributeOverride). */
    public FieldInfo withColumnName(String newColumnName) {
        return toBuilder().columnName(newColumnName).build();
    }

    /** Returns a copy of this field recording the mapped superclass its primary key was inherited from. */
    public FieldInfo withKeyOrigin(String declaringClassSimpleName) {
        return toBuilder().keyOrigin(declaringClassSimpleName).build();
    }

    private Builder toBuilder() {
        return new Builder()
                .name(name).type(type).optional(optional).transientField(transientField)
                .columnName(columnName).nullable(nullable).maxLength(maxLength)
                .validationRules(validationRules)
                .primaryKey(primaryKey).generatedValue(generatedValue).unique(unique)
                .description(description).enumValues(enumValues)
                .formula(formula).formulaExpression(formulaExpression).keyOrigin(keyOrigin);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private String type;
        private boolean optional = true;
        private boolean transientField = false;
        private String columnName;
        private boolean nullable = true;
        private Integer maxLength;
        private final List<String> validationRules = new ArrayList<>();
        private boolean primaryKey = false;
        private boolean generatedValue = false;
        private boolean unique = false;
        private String description;
        private final List<String> enumValues = new ArrayList<>();
        private boolean formula = false;
        private String formulaExpression;
        private String keyOrigin;

        public Builder name(String v) { this.name = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder optional(boolean v) { this.optional = v; return this; }
        public Builder transientField(boolean v) { this.transientField = v; return this; }
        public Builder columnName(String v) { this.columnName = v; return this; }
        public Builder nullable(boolean v) { this.nullable = v; return this; }
        public Builder maxLength(Integer v) { this.maxLength = v; return this; }
        public Builder addValidationRule(String rule) { this.validationRules.add(rule); return this; }
        public Builder validationRules(List<String> v) { this.validationRules.clear(); this.validationRules.addAll(v); return this; }
        public Builder primaryKey(boolean v) { this.primaryKey = v; return this; }
        public Builder generatedValue(boolean v) { this.generatedValue = v; return this; }
        public Builder unique(boolean v) { this.unique = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder enumValues(List<String> v) { this.enumValues.clear(); this.enumValues.addAll(v); return this; }
        public Builder formula(boolean v) { this.formula = v; return this; }
        public Builder formulaExpression(String v) { this.formulaExpression = v; return this; }
        public Builder keyOrigin(String v) { this.keyOrigin = v; return this; }
        public FieldInfo build() { return new FieldInfo(this); }
    }
}
