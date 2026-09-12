package com.devmanchego.contextextractor.java.schema;

import com.devmanchego.contextextractor.java.model.*;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedColumn;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedForeignKey;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedIndex;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedTable;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.EnumDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Merges live-DB introspection, static SQL migration parsing, and JPA entity
 * annotations into one {@link DatabaseSchema}, tagging every fact with its
 * {@link DataProvenance} so a downstream reader (human or LLM) can tell
 * verified structure apart from best-effort inference.
 *
 * Precedence for structural facts (type, nullable, default, PK/unique, indexes):
 * live DB &gt; SQL migration &gt; JPA (used only when nothing else is available).
 * Semantic facts (business meaning, enum labels, human-readable constraints) always
 * come from JPA/Bean Validation — the database has no concept of "business meaning".
 */
public class SchemaBuilder {

    private static final Logger log = LoggerFactory.getLogger(SchemaBuilder.class);

    /**
     * @param entities   JPA entities discovered by {@code PersistenceMappingExtractor}
     * @param allCUs     all parsed compilation units, used to resolve enum constants for @Enumerated fields
     * @param liveSchema result of {@code LiveSchemaIntrospector} — empty if no DB connection was available
     * @param sqlSchema  result of {@code FlywayMigrationParser} — empty if no migrations were found
     * @param extraWarnings   e.g. the live-introspection failure message, to surface in the output
     */
    public DatabaseSchema build(List<EntityInfo> entities, List<CompilationUnit> allCUs,
                                RelationalSchema liveSchema, RelationalSchema sqlSchema,
                                List<String> extraWarnings) {

        List<String> warnings = new ArrayList<>(extraWarnings);
        Set<DataProvenance> sourcesUsed = new LinkedHashSet<>();

        RelationalSchema structural;
        DataProvenance structuralSource;
        if (!liveSchema.isEmpty()) {
            structural = liveSchema;
            structuralSource = DataProvenance.LIVE_DB;
        } else if (!sqlSchema.isEmpty()) {
            structural = sqlSchema;
            structuralSource = DataProvenance.SQL_MIGRATION;
        } else {
            structural = RelationalSchema.empty();
            structuralSource = DataProvenance.JPA;
            warnings.add("No live database connection and no Flyway migrations found — "
                    + "schema derived entirely from JPA entity annotations. Structural facts "
                    + "such as exact SQL types, indexes, and DB-level defaults may be incomplete "
                    + "or absent; only what JPA annotations declare is available.");
        }
        if (structural.isEmpty() && entities.isEmpty()) {
            log.warn("No structural source (DB/SQL) and no JPA entities found — schema will be empty.");
        }

        Map<String, EntityInfo> entityByTableNameLower = entities.stream()
                .collect(Collectors.toMap(e -> e.getTableName().toLowerCase(Locale.ROOT), e -> e,
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, SchemaTable> tables = new LinkedHashMap<>();

        // 1. Tables present in the structural source (live DB or SQL migrations), enriched with JPA.
        for (ParsedTable parsedTable : structural.tables().values()) {
            EntityInfo entity = entityByTableNameLower.get(parsedTable.name().toLowerCase(Locale.ROOT));
            SchemaTable table = mergeTable(parsedTable, entity, structuralSource, allCUs, structural.indexes());
            tables.put(table.qualifiedName(), table);
            sourcesUsed.add(structuralSource);
            if (entity != null) sourcesUsed.add(DataProvenance.JPA);
        }

        // 2. JPA entities whose table wasn't found in the structural source (e.g. no migration
        //    written yet, DB unreachable, or ddl-auto=create-drop with no captured DDL).
        for (EntityInfo entity : entities) {
            boolean alreadyCovered = structural.tables().values().stream()
                    .anyMatch(t -> t.name().equalsIgnoreCase(entity.getTableName()));
            if (alreadyCovered) continue;
            SchemaTable table = tableFromJpaOnly(entity, allCUs);
            tables.put(table.qualifiedName(), table);
            sourcesUsed.add(DataProvenance.JPA);
        }

        List<SchemaRelationship> relationships = buildRelationships(
                entities, structural, tables, structuralSource, sourcesUsed);

        return new DatabaseSchema(tables, relationships, sourcesUsed, warnings);
    }

    // -----------------------------------------------------------------------
    // Table merging
    // -----------------------------------------------------------------------

    private SchemaTable mergeTable(ParsedTable parsedTable, EntityInfo entity,
                                   DataProvenance structuralSource, List<CompilationUnit> allCUs,
                                   List<ParsedIndex> allIndexes) {
        Map<String, FieldInfo> fieldByColumnLower = entity == null ? Map.of() :
                entity.getFields().stream()
                        .filter(f -> !f.isTransient())
                        .collect(Collectors.toMap(
                                f -> resolveColumnName(f).toLowerCase(Locale.ROOT), f -> f,
                                (a, b) -> a, LinkedHashMap::new));

        List<SchemaColumn> columns = new ArrayList<>();
        int position = 1;
        for (ParsedColumn pc : parsedTable.columns()) {
            FieldInfo field = fieldByColumnLower.get(pc.name().toLowerCase(Locale.ROOT));
            columns.add(mergeColumn(pc, field, position++, structuralSource, allCUs));
        }
        // JPA fields not present in the structural source at all (rare: entity ahead of DB).
        if (entity != null) {
            Set<String> structuralColumnNamesLower = parsedTable.columns().stream()
                    .map(c -> c.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            for (FieldInfo field : entity.getFields()) {
                // @Transient fields still get a row — as a derived attribute, not a silently
                // dropped one. columnFromJpaOnly() below is what actually routes them there.
                String columnName = resolveColumnName(field);
                if (!field.isTransient() && structuralColumnNamesLower.contains(columnName.toLowerCase(Locale.ROOT))) continue;
                columns.add(columnFromJpaOnly(field, columnName, position++, allCUs));
            }
        }

        List<SchemaIndex> indexes = allIndexes.stream()
                .filter(idx -> idx.table().equalsIgnoreCase(parsedTable.name()))
                .map(idx -> new SchemaIndex(idx.name(), idx.columns(), idx.unique(),
                        null, idx.partialWhere(), structuralSource))
                .toList();
        String description = entity != null ? entity.getDescription() : null;
        String entitySimpleName = entity != null ? entity.getSimpleName() : null;

        return new SchemaTable(parsedTable.name(), null, description, entitySimpleName,
                columns, indexes, structuralSource);
    }

    private SchemaColumn mergeColumn(ParsedColumn pc, FieldInfo field, int position,
                                     DataProvenance structuralSource, List<CompilationUnit> allCUs) {
        SchemaColumn.Builder b = SchemaColumn.builder()
                .name(pc.name())
                .ordinalPosition(position)
                .sqlType(pc.sqlType())
                .nullable(pc.nullable(), structuralSource)
                .primaryKey(pc.primaryKey())
                .unique(pc.unique(), structuralSource)
                .checkConstraintRaw(pc.checkRaw())
                .structuralSource(structuralSource)
                .defaultValue(pc.defaultValue(), pc.defaultValue() != null ? structuralSource : null);

        if (field != null) {
            b.javaType(field.getType());
            b.businessMeaning(field.getDescription());
            b.keyOrigin(field.getKeyOrigin());
            if (field.getMaxLength() != null) b.maxLength(field.getMaxLength());
            translateValidationsToProse(field.getValidationRules()).forEach(b::addCheckConstraintProse);
            List<String> enumValues = field.getEnumValues();
            if (enumValues.isEmpty() && field.getValidationRules().contains("@Enumerated")) {
                enumValues = resolveEnumConstants(field.getType(), allCUs);
            }
            if (!enumValues.isEmpty()) b.enumValues(enumValues);
        }

        return b.build();
    }

    /** Table exists only via JPA — no live DB and no matching SQL migration found. */
    private SchemaTable tableFromJpaOnly(EntityInfo entity, List<CompilationUnit> allCUs) {
        List<SchemaColumn> columns = new ArrayList<>();
        int position = 1;
        for (FieldInfo field : entity.getFields()) {
            // @Transient fields still get a row — as a derived attribute, not a silently
            // dropped one. columnFromJpaOnly() below is what actually routes them there.
            columns.add(columnFromJpaOnly(field, resolveColumnName(field), position++, allCUs));
        }
        return new SchemaTable(entity.getTableName(), null, entity.getDescription(),
                entity.getSimpleName(), columns, List.of(), DataProvenance.JPA);
    }

    private SchemaColumn columnFromJpaOnly(FieldInfo field, String columnName, int position,
                                           List<CompilationUnit> allCUs) {
        // A field is derived — has no real database column — only when JPA itself says so
        // (@Transient or @Formula). The *absence* of an explicit @Column(name=...) is not
        // that signal: JPA's default naming strategy still maps an unannotated field to a
        // real column, so treating "no @Column" as "derived" mislabeled every plain field as
        // a computed one. resolveColumnName() (called by every caller of this method) already
        // supplies the correct default-naming fallback for such fields.
        boolean isDerived = field.isTransient() || field.isFormula();
        String derivationLogic = derivationLogic(field, isDerived);

        SchemaColumn.Builder b = SchemaColumn.builder()
                .name(columnName)
                .ordinalPosition(position)
                .sqlType(null)
                .javaType(field.getType())
                .nullable(field.isNullable(), DataProvenance.JPA)
                .primaryKey(field.isPrimaryKey())
                .unique(field.isUnique(), DataProvenance.JPA)
                .businessMeaning(field.getDescription())
                .structuralSource(DataProvenance.JPA)
                .derivationLogic(derivationLogic)
                .keyOrigin(field.getKeyOrigin());
        if (field.getMaxLength() != null) b.maxLength(field.getMaxLength());
        translateValidationsToProse(field.getValidationRules()).forEach(b::addCheckConstraintProse);
        List<String> enumValues = field.getEnumValues();
        if (enumValues.isEmpty() && field.getValidationRules().contains("@Enumerated")) {
            enumValues = resolveEnumConstants(field.getType(), allCUs);
        }
        if (!enumValues.isEmpty()) b.enumValues(enumValues);
        return b.build();
    }

    /**
     * Describes how a derived field is actually computed. A {@code @Formula} field with its
     * expression captured reports that expression verbatim — a reader can see exactly what
     * "derived" means for this field, rather than a label that's identical whether it's a
     * database-evaluated formula or a plain Java-only {@code @Transient} field.
     */
    private String derivationLogic(FieldInfo field, boolean isDerived) {
        if (!isDerived) return null;
        if (field.isFormula() && field.getFormulaExpression() != null) {
            return "Computed by database expression (`@Formula`): `" + field.getFormulaExpression() + "`";
        }
        if (field.isFormula()) {
            return "Computed by a database expression (`@Formula` — expression not captured)";
        }
        return "Computed field (Java-only, `@Transient` — never persisted)";
    }

    private String resolveColumnName(FieldInfo field) {
        return field.getColumnName() != null ? field.getColumnName() : toSnakeCase(field.getName());
    }

    // -----------------------------------------------------------------------
    // Relationships
    // -----------------------------------------------------------------------

    private List<SchemaRelationship> buildRelationships(List<EntityInfo> entities, RelationalSchema structural,
                                                         Map<String, SchemaTable> tables,
                                                         DataProvenance structuralSource,
                                                         Set<DataProvenance> sourcesUsed) {
        List<SchemaRelationship> result = new ArrayList<>();

        // Structural FKs first (live DB / SQL migration), since they're the physical ground truth.
        Map<String, SchemaRelationship> byFromTableColumn = new LinkedHashMap<>();
        for (ParsedTable pt : structural.tables().values()) {
            for (ParsedForeignKey fk : pt.foreignKeys()) {
                boolean fromColumnUnique = pt.columns().stream()
                        .filter(c -> c.name().equalsIgnoreCase(fk.fromColumn()))
                        .anyMatch(c -> c.unique() || c.primaryKey());
                SchemaRelationship.Cardinality cardinality = fromColumnUnique
                        ? SchemaRelationship.Cardinality.ONE_TO_ONE
                        : SchemaRelationship.Cardinality.MANY_TO_ONE;
                SchemaRelationship rel = new SchemaRelationship(
                        fk.fromTable(), fk.fromColumn(), fk.toTable(), fk.toColumn(),
                        cardinality, SchemaRelationship.CardinalitySource.INFERRED_SQL,
                        fk.constraintName(), structuralSource);
                byFromTableColumn.put(key(fk.fromTable(), fk.fromColumn()), rel);
            }
        }

        // JPA relations overlay: upgrade cardinality/provenance where a structural FK matches,
        // otherwise add as a JPA-only declared relationship (no physical FK confirmed).
        for (EntityInfo entity : entities) {
            for (RelationInfo rel : entity.getRelations()) {
                if (rel.getJoinColumn() == null && rel.getKind() != RelationInfo.Kind.ONE_TO_ONE) {
                    // Inverse side (mappedBy) or unowned — the owning side carries the physical FK.
                    if (rel.getMappedBy() != null) continue;
                }
                String fromTable = entity.getTableName();
                String fromColumn = rel.getJoinColumn() != null
                        ? rel.getJoinColumn() : toSnakeCase(rel.getFieldName()) + "_id";
                String toTable = toSnakeCase(simpleTypeName(rel.getTargetEntityType())) + "s"; // best-effort default
                // Prefer an actual matching table name over the pluralization guess.
                String resolvedToTable = tables.values().stream()
                        .filter(t -> rel.getTargetEntityType().endsWith(t.getEntitySimpleName() == null ? "\0" : t.getEntitySimpleName()))
                        .map(SchemaTable::getName)
                        .findFirst()
                        .orElse(toTable);

                String k = key(fromTable, fromColumn);
                SchemaRelationship existing = byFromTableColumn.get(k);
                if (existing != null) {
                    byFromTableColumn.put(k, new SchemaRelationship(
                            existing.getFromTable(), existing.getFromColumn(),
                            existing.getToTable(), existing.getToColumn(),
                            mapCardinality(rel.getKind()), SchemaRelationship.CardinalitySource.DECLARED_JPA,
                            existing.getConstraintName(), DataProvenance.MERGED));
                    sourcesUsed.add(DataProvenance.MERGED);
                } else if (rel.getJoinColumn() != null || rel.getKind() == RelationInfo.Kind.MANY_TO_ONE
                        || rel.getKind() == RelationInfo.Kind.ONE_TO_ONE) {
                    byFromTableColumn.put(k, new SchemaRelationship(
                            fromTable, fromColumn, resolvedToTable, "id",
                            mapCardinality(rel.getKind()), SchemaRelationship.CardinalitySource.DECLARED_JPA,
                            null, DataProvenance.JPA));
                    sourcesUsed.add(DataProvenance.JPA);
                }
            }
        }

        result.addAll(byFromTableColumn.values());
        return result;
    }

    private String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "#" + column.toLowerCase(Locale.ROOT);
    }

    private String simpleTypeName(String possiblyGenericType) {
        String t = possiblyGenericType;
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private SchemaRelationship.Cardinality mapCardinality(RelationInfo.Kind kind) {
        return switch (kind) {
            case ONE_TO_MANY -> SchemaRelationship.Cardinality.ONE_TO_MANY;
            case MANY_TO_ONE -> SchemaRelationship.Cardinality.MANY_TO_ONE;
            case ONE_TO_ONE -> SchemaRelationship.Cardinality.ONE_TO_ONE;
            case MANY_TO_MANY -> SchemaRelationship.Cardinality.MANY_TO_MANY;
        };
    }

    // -----------------------------------------------------------------------
    // Bean Validation → business-readable prose
    // -----------------------------------------------------------------------

    private List<String> translateValidationsToProse(List<String> validationRules) {
        List<String> prose = new ArrayList<>();
        for (String rule : validationRules) {
            String p = translateOne(rule);
            if (p != null) prose.add(p);
        }
        return prose;
    }

    private String translateOne(String rule) {
        if (rule.equals("@NotNull") || rule.equals("@NotBlank")) return "Required (not null).";
        if (rule.equals("@Email")) return "Must be a valid email address.";
        if (rule.equals("@Positive")) return "Must be positive (> 0).";
        if (rule.equals("@PositiveOrZero")) return "Must be >= 0.";
        if (rule.equals("@Negative")) return "Must be negative (< 0).";
        if (rule.equals("@Enumerated")) return null; // surfaced via enum values instead

        java.util.regex.Matcher m;
        m = java.util.regex.Pattern.compile("@Size\\(([^)]*)\\)").matcher(rule);
        if (m.matches()) return "Length constraint: " + m.group(1) + ".";
        m = java.util.regex.Pattern.compile("@Min\\((.+)\\)").matcher(rule);
        if (m.matches()) return "Must be >= " + m.group(1) + ".";
        m = java.util.regex.Pattern.compile("@Max\\((.+)\\)").matcher(rule);
        if (m.matches()) return "Must be <= " + m.group(1) + ".";
        m = java.util.regex.Pattern.compile("@DecimalMin\\((.+)\\)").matcher(rule);
        if (m.matches()) return "Must be >= " + m.group(1) + ".";
        m = java.util.regex.Pattern.compile("@DecimalMax\\((.+)\\)").matcher(rule);
        if (m.matches()) return "Must be <= " + m.group(1) + ".";
        m = java.util.regex.Pattern.compile("@Pattern\\((.+)\\)").matcher(rule);
        if (m.matches()) return "Must match pattern: " + m.group(1) + ".";
        m = java.util.regex.Pattern.compile("@Digits\\(([^)]*)\\)").matcher(rule);
        if (m.matches()) return "Numeric precision: " + m.group(1) + ".";
        return null;
    }

    // -----------------------------------------------------------------------
    // Enum resolution
    // -----------------------------------------------------------------------

    private List<String> resolveEnumConstants(String typeSimpleName, List<CompilationUnit> allCUs) {
        for (CompilationUnit cu : allCUs) {
            for (EnumDeclaration enumDecl : cu.findAll(EnumDeclaration.class)) {
                if (enumDecl.getNameAsString().equals(typeSimpleName)) {
                    return enumDecl.getEntries().stream()
                            .map(e -> e.getNameAsString())
                            .toList();
                }
            }
        }
        return List.of();
    }

    private String toSnakeCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
