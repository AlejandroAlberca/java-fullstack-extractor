package com.devmanchego.contextextractor.java.model;

import java.util.List;

/** A database index, from live catalog introspection or a SQL migration's CREATE INDEX. */
public final class SchemaIndex {

    private final String name;
    private final List<String> columns;
    private final boolean unique;
    private final String type;          // e.g. "BTREE", "HASH" — null if unknown
    private final String partialWhere;  // partial-index predicate, null if none
    private final DataProvenance source;

    public SchemaIndex(String name, List<String> columns, boolean unique,
                       String type, String partialWhere, DataProvenance source) {
        this.name = name;
        this.columns = List.copyOf(columns);
        this.unique = unique;
        this.type = type;
        this.partialWhere = partialWhere;
        this.source = source;
    }

    public String getName() { return name; }
    public List<String> getColumns() { return columns; }
    public boolean isUnique() { return unique; }
    public String getType() { return type; }
    public String getPartialWhere() { return partialWhere; }
    public DataProvenance getSource() { return source; }
}
