package com.devmanchego.contextextractor.java.model;

/**
 * A directed foreign-key relationship between two tables, merged from JPA
 * (@ManyToOne/@OneToMany/...) and/or a SQL FOREIGN KEY constraint.
 */
public final class SchemaRelationship {

    public enum Cardinality { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY }

    /** How the cardinality was determined, not just where the FK came from. */
    public enum CardinalitySource {
        /** Explicit JPA annotation (@OneToMany, @ManyToOne, ...). */
        DECLARED_JPA,
        /** Inferred from SQL: nullable/unique flags on the FK column. */
        INFERRED_SQL
    }

    private final String fromTable;
    private final String fromColumn;
    private final String toTable;
    private final String toColumn;
    private final Cardinality cardinality;
    private final CardinalitySource cardinalitySource;
    private final String constraintName; // null if only known from JPA
    private final DataProvenance provenance;

    public SchemaRelationship(String fromTable, String fromColumn, String toTable, String toColumn,
                              Cardinality cardinality, CardinalitySource cardinalitySource,
                              String constraintName, DataProvenance provenance) {
        this.fromTable = fromTable;
        this.fromColumn = fromColumn;
        this.toTable = toTable;
        this.toColumn = toColumn;
        this.cardinality = cardinality;
        this.cardinalitySource = cardinalitySource;
        this.constraintName = constraintName;
        this.provenance = provenance;
    }

    public String getFromTable() { return fromTable; }
    public String getFromColumn() { return fromColumn; }
    public String getToTable() { return toTable; }
    public String getToColumn() { return toColumn; }
    public Cardinality getCardinality() { return cardinality; }
    public CardinalitySource getCardinalitySource() { return cardinalitySource; }
    public String getConstraintName() { return constraintName; }
    public DataProvenance getProvenance() { return provenance; }

    /** Compact cardinality notation for the global relation graph, e.g. "1:N". */
    public String cardinalityNotation() {
        return switch (cardinality) {
            case ONE_TO_ONE -> "1:1";
            case ONE_TO_MANY -> "1:N";
            case MANY_TO_ONE -> "N:1";
            case MANY_TO_MANY -> "N:M";
        };
    }
}
