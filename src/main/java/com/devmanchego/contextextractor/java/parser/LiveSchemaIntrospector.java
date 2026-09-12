package com.devmanchego.contextextractor.java.parser;

import com.devmanchego.contextextractor.java.model.DatabaseConnectionInfo;
import com.devmanchego.contextextractor.java.model.DatabaseType;
import com.devmanchego.contextextractor.java.model.RelationalSchema;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedColumn;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedForeignKey;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedIndex;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * Reads live database structure via {@link DatabaseMetaData} (tables, columns, PK, FK,
 * indexes). This is the preferred structural source when a connection is available —
 * it reflects reality, not a specific migration's intent.
 *
 * <p><b>Never throws.</b> Any connection or introspection failure (no driver on the
 * classpath, host unreachable, bad credentials, DB down) is caught, logged as a
 * warning, and reported back via {@link Result#warning()} so the caller can fall back
 * to static analysis (JPA entities + SQL migrations) instead of aborting the run.
 */
public class LiveSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(LiveSchemaIntrospector.class);

    private static final int LOGIN_TIMEOUT_SECONDS = 5;

    /**
     * Attempts to connect and introspect. Always returns a usable {@link Result} —
     * check {@link Result#warning()} to see if it degraded to an empty schema.
     */
    public Result introspect(DatabaseConnectionInfo connectionInfo) {
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);

        String username = connectionInfo.getUsername().orElse(null);
        String password = connectionInfo.getPassword().orElse(null);

        try (Connection conn = DriverManager.getConnection(
                connectionInfo.getJdbcUrl(), username, password)) {

            RelationalSchema schema = readSchema(conn, connectionInfo.getType());
            log.info("Live database introspection succeeded: {} table(s), {} index(es)",
                    schema.tables().size(), schema.indexes().size());
            return new Result(schema, null);

        } catch (SQLException e) {
            String warning = "Could not connect to database (" + connectionInfo.getType().getDisplayName()
                    + " at " + connectionInfo.getServer() + ":" + connectionInfo.getEffectivePort()
                    + "/" + connectionInfo.getDatabaseName() + "): " + e.getMessage()
                    + " — falling back to static analysis from JPA entities and SQL migrations only.";
            log.warn(warning);
            return new Result(RelationalSchema.empty(), warning);
        } catch (Exception e) {
            // Defensive: a missing driver class, misconfigured URL, or any other
            // unexpected failure must degrade gracefully rather than abort the pipeline.
            String warning = "Live database introspection failed unexpectedly: " + e.getMessage()
                    + " — falling back to static analysis from JPA entities and SQL migrations only.";
            log.warn(warning);
            return new Result(RelationalSchema.empty(), warning);
        }
    }

    private RelationalSchema readSchema(Connection conn, DatabaseType dbType) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = conn.getCatalog();
        String schemaPattern = defaultSchemaPattern(dbType, conn);

        Map<String, ParsedTable> tables = new LinkedHashMap<>();
        List<ParsedIndex> indexes = new ArrayList<>();

        List<String> tableNames = new ArrayList<>();
        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tableNames.add(rs.getString("TABLE_NAME"));
            }
        }

        for (String tableName : tableNames) {
            List<String> pkColumns = readPrimaryKeyColumns(meta, catalog, schemaPattern, tableName);
            Set<String> uniqueColumns = readUniqueSingleColumns(meta, catalog, schemaPattern, tableName);
            List<ParsedColumn> columns = readColumns(meta, catalog, schemaPattern, tableName, pkColumns, uniqueColumns);
            List<ParsedForeignKey> foreignKeys = readForeignKeys(meta, catalog, schemaPattern, tableName);
            indexes.addAll(readIndexes(meta, conn, catalog, schemaPattern, tableName, dbType));

            tables.put(tableName, new ParsedTable(tableName, columns, pkColumns, foreignKeys,
                    List.of(), List.of()));
        }

        return new RelationalSchema(tables, indexes, List.of());
    }

    private String defaultSchemaPattern(DatabaseType dbType, Connection conn) {
        try {
            return switch (dbType) {
                // Oracle: schema == username by convention.
                case ORACLE -> conn.getMetaData().getUserName();
                // PostgreSQL: scope to the connected schema (first entry of search_path,
                // normally "public") — a null pattern would otherwise also pull in
                // pg_catalog/information_schema system tables.
                case POSTGRESQL -> {
                    String schema = conn.getSchema();
                    yield schema != null ? schema : "public";
                }
                // MySQL: "schema" maps to catalog (database name), already scoped via getCatalog().
                case MYSQL -> null;
            };
        } catch (SQLException e) {
            return null;
        }
    }

    private List<String> readPrimaryKeyColumns(DatabaseMetaData meta, String catalog,
                                               String schema, String table) throws SQLException {
        List<String> pk = new ArrayList<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                pk.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pk;
    }

    /** Columns covered by a single-column unique index (used to mark ParsedColumn.unique). */
    private Set<String> readUniqueSingleColumns(DatabaseMetaData meta, String catalog,
                                                String schema, String table) throws SQLException {
        Map<String, List<String>> byIndexName = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, true, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) continue;
                byIndexName.computeIfAbsent(indexName, k -> new ArrayList<>()).add(columnName);
            }
        }
        Set<String> result = new LinkedHashSet<>();
        byIndexName.values().stream().filter(cols -> cols.size() == 1).forEach(cols -> result.add(cols.get(0)));
        return result;
    }

    private List<ParsedColumn> readColumns(DatabaseMetaData meta, String catalog, String schema,
                                           String table, List<String> pkColumns,
                                           Set<String> uniqueColumns) throws SQLException {
        List<ParsedColumn> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int columnSize = rs.getInt("COLUMN_SIZE");
                int decimalDigits = rs.getInt("DECIMAL_DIGITS");
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                String defaultValue = rs.getString("COLUMN_DEF");

                String sqlType = formatSqlType(typeName, columnSize, decimalDigits);

                ParsedColumn.Builder b = new ParsedColumn.Builder();
                b.name = name;
                b.sqlType = sqlType;
                b.nullable = nullable;
                b.primaryKey = pkColumns.contains(name);
                b.unique = uniqueColumns.contains(name);
                b.defaultValue = defaultValue;
                columns.add(b.build());
            }
        }
        return columns;
    }

    private String formatSqlType(String typeName, int columnSize, int decimalDigits) {
        if (typeName == null) return "UNKNOWN";
        String upper = typeName.toUpperCase(Locale.ROOT);
        boolean sized = upper.contains("CHAR") || upper.contains("NUMERIC") || upper.contains("DECIMAL");
        if (sized && columnSize > 0) {
            if (decimalDigits > 0) {
                return upper + "(" + columnSize + "," + decimalDigits + ")";
            }
            return upper + "(" + columnSize + ")";
        }
        return upper;
    }

    private List<ParsedForeignKey> readForeignKeys(DatabaseMetaData meta, String catalog,
                                                    String schema, String table) throws SQLException {
        List<ParsedForeignKey> fks = new ArrayList<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                String fromColumn = rs.getString("FKCOLUMN_NAME");
                String toTable = rs.getString("PKTABLE_NAME");
                String toColumn = rs.getString("PKCOLUMN_NAME");
                String constraintName = rs.getString("FK_NAME");
                fks.add(new ParsedForeignKey(table, fromColumn, toTable, toColumn, constraintName));
            }
        }
        return fks;
    }

    private List<ParsedIndex> readIndexes(DatabaseMetaData meta, Connection conn, String catalog,
                                          String schema, String table, DatabaseType dbType) throws SQLException {
        Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();

        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) continue;
                columnsByIndex.computeIfAbsent(indexName, k -> new ArrayList<>()).add(columnName);
                uniqueByIndex.put(indexName, !rs.getBoolean("NON_UNIQUE"));
            }
        }

        Map<String, String> partialWhereByIndex = dbType == DatabaseType.POSTGRESQL
                ? readPostgresPartialIndexPredicates(conn, table)
                : Map.of();

        List<ParsedIndex> result = new ArrayList<>();
        columnsByIndex.forEach((name, cols) -> result.add(new ParsedIndex(
                name, table, cols, uniqueByIndex.getOrDefault(name, false),
                partialWhereByIndex.get(name))));
        return result;
    }

    /**
     * PostgreSQL-specific: {@link DatabaseMetaData#getIndexInfo} doesn't expose partial-index
     * predicates, so we query {@code pg_indexes} directly for the index definition and pull
     * the {@code WHERE} clause out of it.
     */
    private Map<String, String> readPostgresPartialIndexPredicates(Connection conn, String table) {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("indexname");
                    String indexDef = rs.getString("indexdef");
                    if (indexDef == null) continue;
                    int whereIdx = indexDef.toUpperCase(Locale.ROOT).indexOf(" WHERE ");
                    if (whereIdx >= 0) {
                        result.put(indexName, indexDef.substring(whereIdx + 7).trim());
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Could not read partial index predicates for table {}: {}", table, e.getMessage());
        }
        return result;
    }

    /** Outcome of a live introspection attempt: the schema (possibly empty) plus an optional warning. */
    public record Result(RelationalSchema schema, String warning) {
        public boolean succeeded() { return warning == null; }
    }
}
