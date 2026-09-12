package com.devmanchego.contextextractor.java.parser;

import com.devmanchego.contextextractor.java.model.RelationalSchema;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedColumn;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedForeignKey;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedIndex;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses Flyway SQL migration files ({@code V*__*.sql}, {@code R*__*.sql}) to recover
 * table structure without needing a live database connection. This is the offline
 * structural fallback: if the target application ships migrations, we get accurate
 * types, constraints, and indexes even when the database itself is unreachable.
 *
 * Best-effort regex-based parser covering common PostgreSQL/MySQL/Oracle DDL syntax
 * (CREATE TABLE, inline and table-level constraints, CREATE INDEX incl. partial
 * indexes). Not a full SQL grammar — statements it cannot confidently parse are
 * skipped with a debug log rather than failing the whole extraction.
 */
public class FlywayMigrationParser {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationParser.class);

    private static final Pattern MIGRATION_FILENAME = Pattern.compile("^[VR](\\d+(?:[._]\\d+)*)__.+\\.sql$");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?([\\w.]+)\"?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_INDEX = Pattern.compile(
            "CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?([\\w.]+)\"?\\s+ON\\s+\"?([\\w.]+)\"?\\s*\\(([^)]+)\\)(\\s+WHERE\\s+(.+?))?;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ALTER_TABLE_ADD_FK = Pattern.compile(
            "ALTER\\s+TABLE\\s+\"?([\\w.]+)\"?\\s+ADD\\s+(?:CONSTRAINT\\s+\"?[\\w]+\"?\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+\"?([\\w.]+)\"?\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    private final Path javaProjectPath;

    public FlywayMigrationParser(Path javaProjectPath) {
        this.javaProjectPath = javaProjectPath;
    }

    /**
     * Locates and parses all Flyway migration files under {@code src/main/resources/db/migration}
     * (default location), applied in version order.
     *
     * @return parsed schema; empty if no migration files were found
     */
    public RelationalSchema parse() {
        List<Path> files = locateMigrationFiles();
        if (files.isEmpty()) {
            log.info("No Flyway migration files found under {}/src/main/resources/db/migration",
                    javaProjectPath);
            return RelationalSchema.empty();
        }

        Map<String, ParsedTable> tables = new LinkedHashMap<>();
        List<ParsedIndex> indexes = new ArrayList<>();
        List<String> versionHistory = new ArrayList<>();

        for (Path file : files) {
            versionHistory.add(file.getFileName().toString());
            try {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                String normalized = stripComments(sql);
                parseCreateTables(normalized, tables);
                parseCreateIndexes(normalized, indexes);
                parseAlterTableForeignKeys(normalized, tables);
            } catch (IOException e) {
                log.warn("Could not read migration file {}: {}", file, e.getMessage());
            }
        }

        log.info("Parsed {} Flyway migration file(s): {} table(s), {} index(es)",
                files.size(), tables.size(), indexes.size());
        return new RelationalSchema(tables, indexes, versionHistory);
    }

    // -----------------------------------------------------------------------
    // File discovery
    // -----------------------------------------------------------------------

    private List<Path> locateMigrationFiles() {
        Path migrationDir = javaProjectPath.resolve("src/main/resources/db/migration");
        if (!Files.isDirectory(migrationDir)) {
            return List.of();
        }
        try (var stream = Files.walk(migrationDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> MIGRATION_FILENAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(this::versionKey))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Could not walk migration directory {}: {}", migrationDir, e.getMessage());
            return List.of();
        }
    }

    private String versionKey(Path file) {
        Matcher m = MIGRATION_FILENAME.matcher(file.getFileName().toString());
        if (m.matches()) {
            // Zero-pad each numeric segment so "V2" sorts before "V10" lexicographically.
            return Arrays.stream(m.group(1).split("[._]"))
                    .map(s -> String.format("%20s", s).replace(' ', '0'))
                    .collect(Collectors.joining("."));
        }
        return file.getFileName().toString();
    }

    // -----------------------------------------------------------------------
    // Comment stripping
    // -----------------------------------------------------------------------

    private String stripComments(String sql) {
        // Remove -- line comments and /* */ block comments (best-effort, ignores string literals).
        String noLineComments = sql.replaceAll("--[^\n]*", "");
        return noLineComments.replaceAll("/\\*.*?\\*/", "");
    }

    // -----------------------------------------------------------------------
    // CREATE TABLE
    // -----------------------------------------------------------------------

    private void parseCreateTables(String sql, Map<String, ParsedTable> tables) {
        Matcher m = CREATE_TABLE.matcher(sql);
        while (m.find()) {
            String tableName = stripQuotesAndSchema(m.group(1));
            int bodyStart = m.end();
            int bodyEnd = findMatchingParen(sql, bodyStart - 1);
            if (bodyEnd < 0) {
                log.debug("Could not find closing paren for CREATE TABLE {}", tableName);
                continue;
            }
            String body = sql.substring(bodyStart, bodyEnd);
            ParsedTable table = parseTableBody(tableName, body);
            tables.put(tableName, table);
        }
    }

    /** Finds the index of the ')' that matches the '(' at {@code openParenIdx}. */
    private int findMatchingParen(String s, int openParenIdx) {
        int depth = 0;
        for (int i = openParenIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Splits a comma-separated list respecting nested parentheses. */
    private List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = body.substring(start).trim();
        if (!last.isEmpty()) parts.add(last);
        return parts;
    }

    private static final Set<String> TABLE_LEVEL_KEYWORDS = Set.of(
            "PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "CONSTRAINT");

    private ParsedTable parseTableBody(String tableName, String body) {
        List<ParsedColumn.Builder> columnBuilders = new ArrayList<>();
        List<String> primaryKeyColumns = new ArrayList<>();
        List<ParsedForeignKey> foreignKeys = new ArrayList<>();
        List<List<String>> uniqueConstraints = new ArrayList<>();
        List<String> checkConstraints = new ArrayList<>();

        for (String item : splitTopLevel(body)) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            String firstWord = firstWord(trimmed).toUpperCase(Locale.ROOT);

            if ("CONSTRAINT".equals(firstWord)) {
                // CONSTRAINT name <PRIMARY KEY|FOREIGN KEY|UNIQUE|CHECK> ...
                trimmed = trimmed.replaceFirst("(?i)^CONSTRAINT\\s+\"?[\\w]+\"?\\s+", "");
                firstWord = firstWord(trimmed).toUpperCase(Locale.ROOT);
            }

            if ("PRIMARY".equals(firstWord)) {
                primaryKeyColumns.addAll(extractColumnList(trimmed));
            } else if ("FOREIGN".equals(firstWord)) {
                parseTableLevelForeignKey(tableName, trimmed).ifPresent(foreignKeys::add);
            } else if ("UNIQUE".equals(firstWord)) {
                uniqueConstraints.add(extractColumnList(trimmed));
            } else if ("CHECK".equals(firstWord)) {
                extractParenContent(trimmed, "CHECK").ifPresent(checkConstraints::add);
            } else {
                parseColumnDefinition(tableName, trimmed, foreignKeys)
                        .ifPresent(columnBuilders::add);
            }
        }

        // A column marked inline PRIMARY KEY also counts toward the table's PK list.
        for (ParsedColumn.Builder cb : columnBuilders) {
            if (cb.primaryKey) primaryKeyColumns.add(cb.name);
        }
        for (ParsedColumn.Builder cb : columnBuilders) {
            if (primaryKeyColumns.contains(cb.name)) cb.primaryKey = true;
        }
        // A column referenced by a single-column UNIQUE(...) is marked unique.
        for (List<String> unique : uniqueConstraints) {
            if (unique.size() == 1) {
                columnBuilders.stream()
                        .filter(cb -> cb.name.equalsIgnoreCase(unique.get(0)))
                        .forEach(cb -> cb.unique = true);
            }
        }

        List<ParsedColumn> columns = columnBuilders.stream().map(ParsedColumn.Builder::build).toList();
        return new ParsedTable(tableName, columns, primaryKeyColumns, foreignKeys,
                uniqueConstraints, checkConstraints);
    }

    private String firstWord(String s) {
        Matcher m = Pattern.compile("^\\s*\"?(\\w+)").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private List<String> extractColumnList(String tableConstraintClause) {
        Matcher m = Pattern.compile("\\(([^)]+)\\)").matcher(tableConstraintClause);
        if (m.find()) {
            return Arrays.stream(m.group(1).split(","))
                    .map(s -> stripQuotesAndSchema(s.trim()))
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return List.of();
    }

    private Optional<String> extractParenContent(String clause, String keyword) {
        int idx = clause.toUpperCase(Locale.ROOT).indexOf(keyword.toUpperCase(Locale.ROOT));
        if (idx < 0) return Optional.empty();
        int openIdx = clause.indexOf('(', idx);
        if (openIdx < 0) return Optional.empty();
        int closeIdx = findMatchingParen(clause, openIdx);
        if (closeIdx < 0) return Optional.empty();
        return Optional.of(clause.substring(openIdx + 1, closeIdx).trim());
    }

    private Optional<ParsedForeignKey> parseTableLevelForeignKey(String fromTable, String clause) {
        Matcher m = Pattern.compile(
                "FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+\"?([\\w.]+)\"?\\s*\\(([^)]+)\\)",
                Pattern.CASE_INSENSITIVE).matcher(clause);
        if (!m.find()) return Optional.empty();
        String fromColumn = stripQuotesAndSchema(m.group(1).split(",")[0].trim());
        String toTable = stripQuotesAndSchema(m.group(2));
        String toColumn = stripQuotesAndSchema(m.group(3).split(",")[0].trim());
        return Optional.of(new ParsedForeignKey(fromTable, fromColumn, toTable, toColumn, null));
    }

    private static final Pattern COLUMN_DEF = Pattern.compile(
            "^\"?([\\w]+)\"?\\s+([\\w]+(?:\\s*\\([^)]*\\))?(?:\\s+VARYING)?)(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private Optional<ParsedColumn.Builder> parseColumnDefinition(String tableName, String def,
                                                                  List<ParsedForeignKey> foreignKeys) {
        Matcher m = COLUMN_DEF.matcher(def.trim());
        if (!m.matches()) {
            log.debug("Could not parse column definition in table {}: '{}'", tableName, def);
            return Optional.empty();
        }
        String name = stripQuotesAndSchema(m.group(1));
        String type = m.group(2).trim().toUpperCase(Locale.ROOT);
        String rest = m.group(3);

        ParsedColumn.Builder b = new ParsedColumn.Builder();
        b.name = name;
        b.sqlType = type;
        b.nullable = !containsWord(rest, "NOT NULL");
        b.primaryKey = containsWord(rest, "PRIMARY KEY");
        b.unique = containsWord(rest, "UNIQUE");

        Matcher defaultM = Pattern.compile(
                "DEFAULT\\s+(.+?)(?=\\s+(?:NOT\\s+NULL|NULL|PRIMARY\\s+KEY|UNIQUE|REFERENCES|CHECK)\\b|$)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(rest);
        if (defaultM.find()) {
            b.defaultValue = defaultM.group(1).trim();
        }

        Matcher checkM = Pattern.compile("CHECK\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE).matcher(rest);
        if (checkM.find()) {
            b.checkRaw = checkM.group(1).trim();
        }

        Matcher refM = Pattern.compile(
                "REFERENCES\\s+\"?([\\w.]+)\"?\\s*(?:\\(([^)]+)\\))?",
                Pattern.CASE_INSENSITIVE).matcher(rest);
        if (refM.find()) {
            String toTable = stripQuotesAndSchema(refM.group(1));
            String toColumn = refM.group(2) != null ? stripQuotesAndSchema(refM.group(2).trim()) : "id";
            foreignKeys.add(new ParsedForeignKey(tableName, name, toTable, toColumn, null));
        }

        return Optional.of(b);
    }

    private boolean containsWord(String haystack, String phrase) {
        return Pattern.compile("\\b" + phrase.replace(" ", "\\s+") + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(haystack).find();
    }

    private String stripQuotesAndSchema(String raw) {
        String s = raw.trim().replaceAll("\"", "");
        // Keep schema-qualified names as-is for tables; for plain identifiers this is a no-op.
        return s;
    }

    // -----------------------------------------------------------------------
    // CREATE INDEX
    // -----------------------------------------------------------------------

    private void parseCreateIndexes(String sql, List<ParsedIndex> indexes) {
        Matcher m = CREATE_INDEX.matcher(sql);
        while (m.find()) {
            boolean unique = m.group(1) != null;
            String indexName = stripQuotesAndSchema(m.group(2));
            String table = stripQuotesAndSchema(m.group(3));
            List<String> columns = Arrays.stream(m.group(4).split(","))
                    .map(s -> stripQuotesAndSchema(s.trim()))
                    .toList();
            String whereClause = m.group(6) != null ? m.group(6).trim() : null;
            indexes.add(new ParsedIndex(indexName, table, columns, unique, whereClause));
        }
    }

    // -----------------------------------------------------------------------
    // ALTER TABLE ... ADD FOREIGN KEY (some migrations add FKs post-creation)
    // -----------------------------------------------------------------------

    private void parseAlterTableForeignKeys(String sql, Map<String, ParsedTable> tables) {
        Matcher m = ALTER_TABLE_ADD_FK.matcher(sql);
        while (m.find()) {
            String fromTable = stripQuotesAndSchema(m.group(1));
            String fromColumn = stripQuotesAndSchema(m.group(2).split(",")[0].trim());
            String toTable = stripQuotesAndSchema(m.group(3));
            String toColumn = stripQuotesAndSchema(m.group(4).split(",")[0].trim());
            ParsedTable table = tables.get(fromTable);
            if (table != null) {
                table.foreignKeys().add(new ParsedForeignKey(fromTable, fromColumn, toTable, toColumn, null));
            }
        }
    }

}
