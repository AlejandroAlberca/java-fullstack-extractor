package com.devmanchego.contextextractor.java.model;

import java.util.List;

/** A single table, merged from live DB / SQL migration / JPA entity sources. */
public final class SchemaTable {

    private final String name;
    private final String schema;            // null if unknown/default
    private final String description;       // from JPA entity Javadoc, null if none
    private final String entitySimpleName;  // owning @Entity class, null if table has no mapped entity
    private final List<SchemaColumn> columns;
    private final List<SchemaIndex> indexes;
    private final DataProvenance source;     // where the table's existence/structure was confirmed from

    public SchemaTable(String name, String schema, String description, String entitySimpleName,
                       List<SchemaColumn> columns, List<SchemaIndex> indexes, DataProvenance source) {
        this.name = name;
        this.schema = schema;
        this.description = description;
        this.entitySimpleName = entitySimpleName;
        this.columns = List.copyOf(columns);
        this.indexes = List.copyOf(indexes);
        this.source = source;
    }

    public String getName() { return name; }
    public String getSchema() { return schema; }
    public String getDescription() { return description; }
    public String getEntitySimpleName() { return entitySimpleName; }
    public List<SchemaColumn> getColumns() { return columns; }
    public List<SchemaIndex> getIndexes() { return indexes; }
    public DataProvenance getSource() { return source; }

    /** Stable qualified name for cross-references, e.g. "public.orders" or just "orders". */
    public String qualifiedName() {
        return schema != null && !schema.isBlank() ? schema + "." + name : name;
    }
}
