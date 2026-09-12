package com.devmanchego.contextextractor.java.model;

import java.util.List;
import java.util.Map;

/**
 * Raw structural schema recovered from a SQL source — either a live JDBC catalog
 * introspection ({@code LiveSchemaIntrospector}) or static Flyway migration parsing
 * ({@code FlywayMigrationParser}). Both producers share this shape so
 * {@code SchemaBuilder} can merge them uniformly regardless of provenance.
 */
public record RelationalSchema(Map<String, ParsedTable> tables, List<ParsedIndex> indexes,
                               List<String> versionHistory) {
    public boolean isEmpty() { return tables.isEmpty(); }

    public static RelationalSchema empty() {
        return new RelationalSchema(Map.of(), List.of(), List.of());
    }

    /** A table as recovered from SQL — columns and constraints, no JPA semantics yet. */
    public record ParsedTable(String name, List<ParsedColumn> columns, List<String> primaryKeyColumns,
                              List<ParsedForeignKey> foreignKeys, List<List<String>> uniqueConstraints,
                              List<String> checkConstraints) {
    }

    public record ParsedColumn(String name, String sqlType, boolean nullable, boolean primaryKey,
                               boolean unique, String defaultValue, String checkRaw) {

        public static final class Builder {
            public String name;
            public String sqlType;
            public boolean nullable = true;
            public boolean primaryKey;
            public boolean unique;
            public String defaultValue;
            public String checkRaw;

            public ParsedColumn build() {
                return new ParsedColumn(name, sqlType, nullable, primaryKey, unique, defaultValue, checkRaw);
            }
        }
    }

    public record ParsedForeignKey(String fromTable, String fromColumn, String toTable,
                                   String toColumn, String constraintName) {
    }

    public record ParsedIndex(String name, String table, List<String> columns, boolean unique,
                              String partialWhere) {
    }
}
