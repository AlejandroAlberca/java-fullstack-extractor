package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.model.*;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Renders the unified database schema (live DB / SQL migrations / JPA entities, merged
 * by {@code SchemaBuilder}) as an LLM-friendly Markdown data model document.
 * Output: {@code <output>-data-model.md}.
 *
 * Every fact is tagged with its provenance (db / sql / jpa / merged) so a reader —
 * human or LLM — can tell verified structure apart from best-effort inference.
 * Deliberately omits operational metrics (growth rate, hot-query notes): those require
 * live database statistics this tool does not collect, and guessing them would be
 * indistinguishable from ground truth without a provenance marker misleading enough
 * to do more harm than leaving them out.
 */
public final class DatabaseSchemaRenderer {

    public static Path dataModelOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-data-model.md");
    }

    public String render(DatabaseSchema schema) {
        if (schema.isEmpty()) {
            return renderEmpty(schema);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Data Model\n\n");

        renderExtractionNotes(sb, schema);
        renderProvenanceLegend(sb, schema);
        renderRelationGraph(sb, schema);
        renderTableOfContents(sb, schema);

        List<SchemaTable> sortedTables = schema.getTablesByName().values().stream()
                .sorted(Comparator.comparing(SchemaTable::qualifiedName))
                .toList();

        for (SchemaTable table : sortedTables) {
            renderTable(sb, table, schema);
        }

        renderEnumerations(sb, schema);

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Per-element index + detail documents (indexed_specs/)
    // -----------------------------------------------------------------------

    /** Stable per-table slug, shared between the category index and its detail file name. */
    public String tableSlug(SchemaTable table) {
        return slugify(table.qualifiedName());
    }

    /**
     * Renders {@code index-spec-data-model.md}: everything table-independent (extraction
     * notes, provenance legend, global relation graph with Mermaid ER diagram, enumerations)
     * plus a listing of every table linking to its own detail document under
     * {@code detailed_tables/<slug>.md}.
     */
    public String renderCategoryIndex(DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Data Model Index\n\n");

        renderExtractionNotes(sb, schema);
        renderProvenanceLegend(sb, schema);
        renderRelationGraph(sb, schema);

        sb.append("## Tables\n\n");
        schema.getTablesByName().values().stream()
                .sorted(Comparator.comparing(SchemaTable::qualifiedName))
                .forEach(t -> sb.append("- [`").append(t.qualifiedName()).append("`](./detailed_tables/")
                        .append(tableSlug(t)).append(".md)\n"));
        sb.append("\n");

        renderEnumerations(sb, schema);
        return sb.toString();
    }

    /** Renders one table as a standalone document for {@code detailed_tables/<slug>.md}. */
    public String renderTableDetail(SchemaTable table, DatabaseSchema schema) {
        return renderTableDetail(table, schema, "");
    }

    /**
     * @param relatedSection pre-rendered {@code ## Related} block to append (may be empty).
     */
    public String renderTableDetail(SchemaTable table, DatabaseSchema schema, String relatedSection) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Table: `").append(table.qualifiedName()).append("`\n\n");

        if (table.getEntitySimpleName() != null) {
            sb.append("**Entity:** `").append(table.getEntitySimpleName()).append("`  \n");
        } else {
            sb.append("**Entity:** _(no JPA entity mapped — table only, e.g. a join table or unmapped legacy table)_  \n");
        }
        sb.append("**Description:** ")
                .append(table.getDescription() != null ? table.getDescription()
                        : "_(no business description available — add Javadoc to the JPA entity class)_")
                .append("  \n");
        sb.append("**Structural source:** ").append(tag(table.getSource())).append("\n\n");

        renderColumns(sb, table);
        renderDerivedFields(sb, table);
        renderConstraintsAndIndexes(sb, table, schema);

        if (relatedSection != null && !relatedSection.isBlank()) {
            sb.append(relatedSection);
        }
        return sb.toString();
    }

    private String renderEmpty(DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Data Model\n\n");
        sb.append("⚠️ **No data model could be extracted.**\n\n");
        sb.append("- No JPA `@Entity` classes were found.\n");
        sb.append("- No live database connection succeeded.\n");
        sb.append("- No Flyway SQL migrations were found under `src/main/resources/db/migration`.\n\n");
        if (!schema.getWarnings().isEmpty()) {
            sb.append("**Details:**\n\n");
            schema.getWarnings().forEach(w -> sb.append("- ").append(w).append("\n"));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Header sections
    // -----------------------------------------------------------------------

    private void renderExtractionNotes(StringBuilder sb, DatabaseSchema schema) {
        if (schema.getWarnings().isEmpty()) return;
        sb.append("## Extraction Notes\n\n");
        sb.append("Read this before trusting anything below at face value:\n\n");
        for (String warning : schema.getWarnings()) {
            sb.append("- ⚠️ ").append(warning).append("\n");
        }
        sb.append("\n");
    }

    private void renderProvenanceLegend(StringBuilder sb, DatabaseSchema schema) {
        sb.append("## Sources\n\n");
        sb.append("Every fact below is tagged with where it came from. Structural facts ")
                .append("(type, nullable, default, indexes) prefer the live database over SQL ")
                .append("migrations over JPA; semantic facts (business meaning, constraints in prose, ")
                .append("enum labels) always come from JPA/Bean Validation.\n\n");
        sb.append("| Tag | Meaning |\n|---|---|\n");
        sb.append("| `db` | Read live from the database catalog (`DatabaseMetaData`) — ground truth. |\n");
        sb.append("| `sql` | Parsed from a Flyway SQL migration file — accurate if migrations are current. |\n");
        sb.append("| `jpa` | Parsed from JPA/Hibernate annotations or Bean Validation — may drift from the real schema if `ddl-auto` isn't `validate`/`none`. |\n");
        sb.append("| `merged` | Confirmed by two independent sources (e.g. a SQL foreign key AND a JPA `@ManyToOne`). |\n\n");
        sb.append("Sources actually used in this document: ")
                .append(schema.getSourcesUsed().stream().map(this::tag).reduce((a, b) -> a + ", " + b).orElse("none"))
                .append(".\n\n");
    }

    private void renderRelationGraph(StringBuilder sb, DatabaseSchema schema) {
        sb.append("## Global Relation Graph (Cardinality)\n\n");
        if (schema.getRelationships().isEmpty()) {
            sb.append("_No foreign-key relationships detected._\n\n");
            return;
        }

        List<SchemaRelationship> sorted = schema.getRelationships().stream()
                .sorted(Comparator.comparing(SchemaRelationship::getFromTable)
                        .thenComparing(SchemaRelationship::getToTable))
                .toList();

        for (SchemaRelationship rel : sorted) {
            sb.append("* `").append(rel.getFromTable()).append("` (")
                    .append(rel.cardinalityNotation()).append(") → `")
                    .append(rel.getToTable()).append("` _(")
                    .append(tag(rel.getProvenance())).append(", cardinality: ")
                    .append(rel.getCardinalitySource() == SchemaRelationship.CardinalitySource.DECLARED_JPA
                            ? "declared" : "inferred from SQL")
                    .append(")_\n");
        }
        sb.append("\n");

        renderMermaidErDiagram(sb, sorted);
    }

    private void renderMermaidErDiagram(StringBuilder sb, List<SchemaRelationship> relationships) {
        sb.append("<details>\n<summary>Mermaid ER diagram</summary>\n\n");
        sb.append("```mermaid\nerDiagram\n");
        for (SchemaRelationship rel : relationships) {
            String notation = switch (rel.getCardinality()) {
                case ONE_TO_ONE -> "||--||";
                case ONE_TO_MANY -> "||--o{";
                case MANY_TO_ONE -> "}o--||";
                case MANY_TO_MANY -> "}o--o{";
            };
            sb.append("    ").append(mermaidId(rel.getFromTable())).append(" ").append(notation)
                    .append(" ").append(mermaidId(rel.getToTable())).append(" : \"")
                    .append(rel.getFromColumn()).append("\"\n");
        }
        sb.append("```\n\n</details>\n\n");
    }

    private String mermaidId(String tableName) {
        return tableName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private void renderTableOfContents(StringBuilder sb, DatabaseSchema schema) {
        sb.append("## Tables\n\n");
        schema.getTablesByName().values().stream()
                .sorted(Comparator.comparing(SchemaTable::qualifiedName))
                .forEach(t -> sb.append("- [`").append(t.qualifiedName()).append("`](#table-")
                        .append(slugify(t.qualifiedName())).append(")\n"));
        sb.append("\n---\n\n");
    }

    // -----------------------------------------------------------------------
    // Per-table section
    // -----------------------------------------------------------------------

    private void renderTable(StringBuilder sb, SchemaTable table, DatabaseSchema schema) {
        sb.append("### Table: `").append(table.qualifiedName())
                .append("` {#table-").append(slugify(table.qualifiedName())).append("}\n\n");

        if (table.getEntitySimpleName() != null) {
            sb.append("**Entity:** `").append(table.getEntitySimpleName()).append("`  \n");
        } else {
            sb.append("**Entity:** _(no JPA entity mapped — table only, e.g. a join table or unmapped legacy table)_  \n");
        }

        sb.append("**Description:** ")
                .append(table.getDescription() != null ? table.getDescription()
                        : "_(no business description available — add Javadoc to the JPA entity class)_")
                .append("  \n");
        sb.append("**Structural source:** ").append(tag(table.getSource())).append("\n\n");

        renderColumns(sb, table);
        renderDerivedFields(sb, table);
        renderConstraintsAndIndexes(sb, table, schema);

        sb.append("---\n\n");
    }

    private void renderColumns(StringBuilder sb, SchemaTable table) {
        List<SchemaColumn> physicalCols = table.getColumns().stream()
                .filter(c -> !c.isDerived())
                .toList();

        if (physicalCols.isEmpty()) return;

        sb.append("#### Columns & Business Logic\n\n");
        sb.append("| # | Column | Type | Nullable | Default | Business Meaning / Constraints | Source |\n");
        sb.append("|---|---|---|---|---|---|---|\n");

        // Number the displayed rows independently of SchemaColumn.ordinalPosition: a derived
        // field (JPA-only tables) still consumes a position when the model is built, but it
        // renders in its own "Derived Fields" table below, not here. Reusing that raw ordinal
        // would leave a gap in this table with no explanation — indistinguishable from a
        // genuinely missing column. A fresh counter over exactly what's shown keeps a gap
        // meaning only one thing: information this tool couldn't recover.
        int displayPosition = 1;
        for (SchemaColumn col : physicalCols) {
            String type = col.getSqlType() != null ? col.getSqlType()
                    : (col.getJavaType() != null ? col.getJavaType() + " _(Java type, DB type unconfirmed)_" : "?");
            String nullableMark = col.isNullable() ? "" : "❌";
            String defaultVal = col.getDefaultValue() != null ? "`" + col.getDefaultValue() + "`" : "—";

            StringBuilder meaning = new StringBuilder();
            if (col.isPrimaryKey()) meaning.append("Primary Key. ");
            if (col.getBusinessMeaning() != null) meaning.append(col.getBusinessMeaning()).append(" ");
            if (col.isEnum()) {
                meaning.append("Lifecycle: ")
                        .append(col.getEnumValues().stream()
                                .map(v -> "`" + v + "`")
                                .reduce((a, b) -> a + ", " + b).orElse(""))
                        .append(". ");
            }
            col.getCheckConstraintProse().forEach(p -> meaning.append(p).append(" "));
            if (col.getCheckConstraintRaw() != null) {
                meaning.append("Raw: `").append(col.getCheckConstraintRaw()).append("` ");
            }
            if (meaning.isEmpty()) meaning.append("—");

            String source = col.getBusinessMeaning() != null || col.isEnum() || !col.getCheckConstraintProse().isEmpty()
                    ? tag(col.getStructuralSource()) + " + jpa"
                    : tag(col.getStructuralSource());

            sb.append("| ").append(displayPosition++)
                    .append(" | `").append(col.getName()).append("`")
                    .append(" | `").append(type).append("`")
                    .append(" | ").append(nullableMark)
                    .append(" | ").append(defaultVal)
                    .append(" | ").append(meaning.toString().trim())
                    .append(" | ").append(source)
                    .append(" |\n");
        }
        sb.append("\n");
    }

    private void renderDerivedFields(StringBuilder sb, SchemaTable table) {
        List<SchemaColumn> derivedCols = table.getColumns().stream()
                .filter(SchemaColumn::isDerived)
                .toList();

        if (derivedCols.isEmpty()) return;

        sb.append("#### Derived Fields / Virtual Attributes\n\n");
        sb.append("These fields are computed at runtime and do not exist in the database.\n\n");
        sb.append("| Field | Java Type | Derivation Logic | Note |\n");
        sb.append("|---|---|---|---|\n");

        for (SchemaColumn col : derivedCols) {
            String logic = col.getDerivationLogic() != null ? col.getDerivationLogic() : "Computed (Hibernate @Formula or @Transient)";
            sb.append("| `").append(col.getName()).append("` | ")
                    .append(col.getJavaType() != null ? col.getJavaType() : "?").append(" | ")
                    .append(logic).append(" | ")
                    .append("Not a database column. For queries, see application logic. |\n");
        }
        sb.append("\n");
    }

    private void renderConstraintsAndIndexes(StringBuilder sb, SchemaTable table, DatabaseSchema schema) {
        sb.append("#### Constraints & Indexes\n\n");
        sb.append("| Type | Logic / Details | Source |\n|---|---|---|\n");

        List<SchemaColumn> pkColumns = table.getColumns().stream()
                .filter(SchemaColumn::isPrimaryKey).toList();
        if (!pkColumns.isEmpty()) {
            String pkNames = pkColumns.stream().map(SchemaColumn::getName)
                    .collect(Collectors.joining(", "));
            String origins = pkColumns.stream()
                    .filter(c -> c.getKeyOrigin() != null)
                    .map(c -> "`" + c.getName() + "` inherited from `" + c.getKeyOrigin() + "`")
                    .collect(Collectors.joining("; "));
            sb.append("| **PK** | `").append(pkNames).append("` | ")
                    .append(tag(table.getSource()))
                    .append(origins.isEmpty() ? "" : " — " + origins)
                    .append(" |\n");
        }

        table.getColumns().stream().filter(SchemaColumn::isUnique).forEach(c ->
                sb.append("| **Unique** | `").append(c.getName()).append("` | ")
                        .append(tag(c.getUniqueSource())).append(" |\n"));

        for (SchemaRelationship rel : schema.outgoingFrom(table.qualifiedName())) {
            sb.append("| **FK** | `").append(rel.getFromColumn()).append("` → `")
                    .append(rel.getToTable()).append("(").append(rel.getToColumn()).append(")")
                    .append("` (").append(rel.cardinalityNotation()).append(") | ")
                    .append(tag(rel.getProvenance())).append(" |\n");
        }

        List<SchemaRelationship> incoming = schema.incomingTo(table.qualifiedName());
        for (SchemaRelationship rel : incoming) {
            sb.append("| **Referenced by** | `").append(rel.getFromTable()).append(".")
                    .append(rel.getFromColumn()).append("` (").append(rel.cardinalityNotation())
                    .append(") | ").append(tag(rel.getProvenance())).append(" |\n");
        }

        for (SchemaIndex idx : table.getIndexes()) {
            StringBuilder detail = new StringBuilder("`").append(idx.getName()).append("` (")
                    .append(String.join(", ", idx.getColumns())).append(")");
            if (idx.isUnique()) detail.append(" UNIQUE");
            if (idx.getPartialWhere() != null) {
                detail.append(" WHERE `").append(idx.getPartialWhere()).append("`");
            }
            sb.append("| **Index** | ").append(detail).append(" | ")
                    .append(tag(idx.getSource())).append(" |\n");
        }

        sb.append("\n");
    }

    private void renderEnumerations(StringBuilder sb, DatabaseSchema schema) {
        java.util.Set<String> enumsCollected = new java.util.LinkedHashSet<>();
        java.util.Map<String, java.util.List<String>> enumMap = new java.util.LinkedHashMap<>();

        for (SchemaTable table : schema.getTablesByName().values()) {
            for (SchemaColumn col : table.getColumns()) {
                if (col.isEnum() && !col.getEnumValues().isEmpty()) {
                    String enumKey = table.qualifiedName() + "." + col.getName();
                    enumsCollected.add(enumKey);
                    enumMap.put(enumKey, col.getEnumValues());
                }
            }
        }

        if (enumsCollected.isEmpty()) return;

        sb.append("## Enumerations & Global Constraints\n\n");
        sb.append("### Enumerated Fields\n\n");
        sb.append("These fields are backed by `@Enumerated` JPA annotations with discrete allowed values.\n\n");

        for (java.util.Map.Entry<String, java.util.List<String>> entry : enumMap.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            String table = parts[0];
            String field = parts[1];
            java.util.List<String> values = entry.getValue();

            sb.append("**`").append(table).append(".").append(field).append("`**\n");
            sb.append("```\n");
            values.forEach(v -> sb.append("  - ").append(v).append("\n"));
            sb.append("```\n\n");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String tag(DataProvenance provenance) {
        return switch (provenance) {
            case LIVE_DB -> "`db`";
            case SQL_MIGRATION -> "`sql`";
            case JPA -> "`jpa`";
            case MERGED -> "`merged`";
        };
    }

    private String slugify(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }
}
