package com.devmanchego.contextextractor.java.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A single column, merged from live DB / SQL migration / JPA sources.
 * Every fact that can legitimately differ by source carries its own provenance tag.
 */
public final class SchemaColumn {

    private final String name;
    private final int ordinalPosition;      // contiguous, 1-based — no gaps
    private final String sqlType;           // e.g. "NUMERIC(12,2)", "VARCHAR(100)"
    private final String javaType;          // e.g. "BigDecimal", "String" — null if JPA didn't contribute
    private final boolean nullable;
    private final DataProvenance nullableSource;
    private final String defaultValue;      // null if none
    private final DataProvenance defaultSource;
    private final boolean primaryKey;
    private final boolean unique;
    private final DataProvenance uniqueSource;
    private final Integer maxLength;
    private final String businessMeaning;   // from Javadoc / description — always JPA-sourced, may be null
    private final String checkConstraintRaw;    // raw SQL CHECK expression, null if none
    private final List<String> checkConstraintProse; // human constraints from Bean Validation (@Min, @Max, @Pattern...)
    private final List<String> enumValues;  // resolved Java enum constants, empty if not an enum
    private final DataProvenance structuralSource; // overall provenance of type/nullable (LIVE_DB > SQL_MIGRATION > JPA)
    private final String derivationLogic;   // for derived fields: Hibernate @Formula, @Transient getter logic, etc. null if physical column
    private final String keyOrigin;         // simple name of the @MappedSuperclass this PK was inherited from, null if declared locally

    private SchemaColumn(Builder b) {
        this.name = b.name;
        this.ordinalPosition = b.ordinalPosition;
        this.sqlType = b.sqlType;
        this.javaType = b.javaType;
        this.nullable = b.nullable;
        this.nullableSource = b.nullableSource;
        this.defaultValue = b.defaultValue;
        this.defaultSource = b.defaultSource;
        this.primaryKey = b.primaryKey;
        this.unique = b.unique;
        this.uniqueSource = b.uniqueSource;
        this.maxLength = b.maxLength;
        this.businessMeaning = b.businessMeaning;
        this.checkConstraintRaw = b.checkConstraintRaw;
        this.checkConstraintProse = List.copyOf(b.checkConstraintProse);
        this.enumValues = List.copyOf(b.enumValues);
        this.structuralSource = b.structuralSource;
        this.derivationLogic = b.derivationLogic;
        this.keyOrigin = b.keyOrigin;
    }

    public String getName() { return name; }
    public int getOrdinalPosition() { return ordinalPosition; }
    public String getSqlType() { return sqlType; }
    public String getJavaType() { return javaType; }
    public boolean isNullable() { return nullable; }
    public DataProvenance getNullableSource() { return nullableSource; }
    public String getDefaultValue() { return defaultValue; }
    public DataProvenance getDefaultSource() { return defaultSource; }
    public boolean isPrimaryKey() { return primaryKey; }
    public boolean isUnique() { return unique; }
    public DataProvenance getUniqueSource() { return uniqueSource; }
    public Integer getMaxLength() { return maxLength; }
    public String getBusinessMeaning() { return businessMeaning; }
    public String getCheckConstraintRaw() { return checkConstraintRaw; }
    public List<String> getCheckConstraintProse() { return checkConstraintProse; }
    public List<String> getEnumValues() { return enumValues; }
    public boolean isEnum() { return !enumValues.isEmpty(); }
    public DataProvenance getStructuralSource() { return structuralSource; }
    public String getDerivationLogic() { return derivationLogic; }
    public boolean isDerived() { return derivationLogic != null; }
    /** Simple name of the {@code @MappedSuperclass} this primary key was inherited from, or null if declared locally. */
    public String getKeyOrigin() { return keyOrigin; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private int ordinalPosition;
        private String sqlType;
        private String javaType;
        private boolean nullable = true;
        private DataProvenance nullableSource = DataProvenance.JPA;
        private String defaultValue;
        private DataProvenance defaultSource;
        private boolean primaryKey;
        private boolean unique;
        private DataProvenance uniqueSource;
        private Integer maxLength;
        private String businessMeaning;
        private String checkConstraintRaw;
        private final List<String> checkConstraintProse = new ArrayList<>();
        private final List<String> enumValues = new ArrayList<>();
        private DataProvenance structuralSource = DataProvenance.JPA;
        private String derivationLogic;
        private String keyOrigin;

        public Builder name(String v) { this.name = v; return this; }
        public Builder ordinalPosition(int v) { this.ordinalPosition = v; return this; }
        public Builder sqlType(String v) { this.sqlType = v; return this; }
        public Builder javaType(String v) { this.javaType = v; return this; }
        public Builder nullable(boolean v, DataProvenance src) { this.nullable = v; this.nullableSource = src; return this; }
        public Builder defaultValue(String v, DataProvenance src) { this.defaultValue = v; this.defaultSource = src; return this; }
        public Builder primaryKey(boolean v) { this.primaryKey = v; return this; }
        public Builder unique(boolean v, DataProvenance src) { this.unique = v; this.uniqueSource = src; return this; }
        public Builder maxLength(Integer v) { this.maxLength = v; return this; }
        public Builder businessMeaning(String v) { this.businessMeaning = v; return this; }
        public Builder checkConstraintRaw(String v) { this.checkConstraintRaw = v; return this; }
        public Builder addCheckConstraintProse(String v) { this.checkConstraintProse.add(v); return this; }
        public Builder enumValues(List<String> v) { this.enumValues.clear(); this.enumValues.addAll(v); return this; }
        public Builder structuralSource(DataProvenance v) { this.structuralSource = v; return this; }
        public Builder derivationLogic(String v) { this.derivationLogic = v; return this; }
        public Builder keyOrigin(String v) { this.keyOrigin = v; return this; }

        public SchemaColumn build() { return new SchemaColumn(this); }
    }
}
