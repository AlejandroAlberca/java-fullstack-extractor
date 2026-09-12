package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.model.CalculationEvidence;
import com.devmanchego.contextextractor.java.model.TraceabilityMapping;
import com.devmanchego.contextextractor.java.model.TraceabilityRow;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Renders field traceability tables: UI ↔ DTO ↔ Entity ↔ Database.
 * Output: {@code api-spec-traceability.md}
 *
 * One section per domain, with mappings between all four layers.
 * Includes transformation rules and confidence indicators for LLM consumption.
 */
public final class TraceabilityRenderer {

    public static Path traceabilityOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-traceability.md");
    }

    public String render(List<TraceabilityMapping> mappings) {
        return render(mappings, Map.of());
    }

    /**
     * @param orphanCalculations calculations detected on fields not present in any
     *                           persistence traceability row (invisible service-layer logic).
     */
    public String render(List<TraceabilityMapping> mappings,
                         Map<String, List<CalculationEvidence>> orphanCalculations) {
        boolean noMappings = mappings == null || mappings.isEmpty();
        boolean noOrphans = orphanCalculations == null || orphanCalculations.isEmpty();
        if (noMappings && noOrphans) {
            return renderNoTraceability();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# FIELD TRACEABILITY — UI ↔ DTO ↔ Entity ↔ Database\n\n");

        renderLegend(sb);
        sb.append("\n");

        if (!noMappings) {
            renderDoubleCalculations(sb, mappings);
            renderTableOfContents(sb, mappings);
            sb.append("\n");
            renderMappings(sb, mappings);
        }

        if (!noOrphans) {
            renderOrphanCalculations(sb, orphanCalculations);
        }

        return sb.toString();
    }

    /**
     * Phase 2c flagship: fields whose <em>value</em> is computed in BOTH backend and frontend.
     * These are the prime "el total no cuadra" divergence risks for an LLM to inspect first.
     */
    private void renderDoubleCalculations(StringBuilder sb, List<TraceabilityMapping> mappings) {
        // Dedupe by field name — the same field can appear in several DTO↔Entity mappings.
        List<TraceabilityRow> doubles = mappings.stream()
                .flatMap(m -> m.getRows().stream())
                .filter(TraceabilityRow::isCalculatedInMultipleLayers)
                .collect(java.util.stream.Collectors.toMap(
                        TraceabilityRow::getDtoFieldName, r -> r, (a, b) -> a,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();

        if (doubles.isEmpty()) {
            return;
        }

        sb.append("## ⚠️ Double Calculations (Backend + Frontend)\n\n");
        sb.append("These fields are computed **in both layers**. If the two formulas drift apart, ")
                .append("the value shown to the user won't match the persisted/derived value — ")
                .append("start here when a computed figure \"doesn't add up\".\n\n");

        for (TraceabilityRow row : doubles) {
            sb.append("### `").append(row.getDtoFieldName()).append("`\n\n");

            List<CalculationEvidence> evidences = row.getCalculationEvidence().stream()
                    .filter(CalculationEvidence::isValueComputation)
                    .sorted(Comparator.comparing(CalculationEvidence::layer))
                    .toList();

            // FORMULA evidence fits a compact table; SLICE evidence (no single formula) gets
            // its own fenced code block below — a table cell cannot hold multi-line source.
            List<CalculationEvidence> formulas = evidences.stream()
                    .filter(e -> e.getKind() == CalculationEvidence.Kind.FORMULA).toList();
            List<CalculationEvidence> slices = evidences.stream()
                    .filter(e -> e.getKind() == CalculationEvidence.Kind.SLICE).toList();

            if (!formulas.isEmpty()) {
                sb.append("| Layer | Locus | Expression | Location |\n");
                sb.append("|---|---|---|---|\n");
                formulas.forEach(ev -> {
                    String location = ev.getSourceClass();
                    if (ev.getSourceMethod() != null) location += "." + ev.getSourceMethod();
                    if (ev.getLineNumber() > 0) location += ":" + ev.getLineNumber();
                    sb.append("| ").append(ev.layer())
                            .append(" | ").append(ev.getLocusLabel())
                            .append(" | `").append(escapeInline(ev.getExpression())).append("`")
                            .append(" | `").append(location).append("` |\n");
                });
                sb.append("\n");
            }

            for (CalculationEvidence ev : slices) {
                String location = ev.getSourceClass();
                if (ev.getSourceMethod() != null) location += "." + ev.getSourceMethod();
                if (ev.getLineNumber() > 0) location += ":" + ev.getLineNumber();
                sb.append("**").append(ev.layer()).append("** — ").append(ev.getLocusLabel())
                        .append(" — `").append(location).append("` (no single formula; cited verbatim):\n\n");
                appendFencedSlice(sb, ev, "");
                sb.append("\n");
            }
        }
    }

    /**
     * Renders calculations for fields outside the DTO↔Entity persistence traceability
     * (e.g. computed fields of non-persisted DTOs). Surfaces otherwise-invisible logic.
     */
    private void renderOrphanCalculations(StringBuilder sb,
                                          Map<String, List<CalculationEvidence>> orphanCalculations) {
        sb.append("## Calculations Outside Persistence Traceability\n\n");
        sb.append("Fields computed in code that do not map to a persisted entity ")
                .append("(e.g. non-persisted DTOs, view models). Useful for LLMs to locate ")
                .append("derived logic that has no database column.\n\n");

        orphanCalculations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    sb.append("**`").append(entry.getKey()).append("`**\n\n");
                    for (CalculationEvidence ev : entry.getValue()) {
                        renderEvidenceBullet(sb, ev);
                    }
                    sb.append("\n");
                });
    }

    private String renderNoTraceability() {
        return """
                # FIELD TRACEABILITY — UI ↔ DTO ↔ Entity ↔ Database

                ⚠️ **No field mappings detected.**

                - No DTO-Entity correlations found.
                - Ensure you have JPA entities and data transfer objects defined.

                **Next step:** Define at least one `@Entity` and corresponding DTO class.
                """;
    }

    private void renderLegend(StringBuilder sb) {
        sb.append("## Legend\n\n");
        sb.append("**Mapping Types:**\n");
        sb.append("- **Direct:** Names match across all layers (low entropy).\n");
        sb.append("- **Renamed:** Mapped explicitly (MapStruct `@Mapping`) but names differ.\n");
        sb.append("- **Transient:** Exists in DTO/UI but not persisted to database.\n");
        sb.append("- **Enum/Constant:** Value stored as code in DB; translated in API.\n");
        sb.append("- **Calculated:** Derived from other fields (requires manual review).\n");
        sb.append("- **Unmapped:** Missing in one or more layers (⚠️ action required).\n\n");

        sb.append("**Confidence Levels:**\n");
        sb.append("- **HIGH:** Explicit `@Mapping` annotation or same name across all layers.\n");
        sb.append("- **MEDIUM:** Name matches with implicit mapping or standard conventions.\n");
        sb.append("- **LOW:** Best-effort guess; LLM should verify before using in diagnosis.\n");
        sb.append("- **UNKNOWN:** Could not determine; requires manual review.\n\n");
    }

    private void renderTableOfContents(StringBuilder sb, List<TraceabilityMapping> mappings) {
        sb.append("## Domains\n\n");
        for (TraceabilityMapping mapping : mappings) {
            sb.append("- [").append(mapping.getTitle()).append("](#").append(mappingAnchor(mapping)).append(")\n");
        }
    }

    private void renderMappings(StringBuilder sb, List<TraceabilityMapping> mappings) {
        sb.append("---\n\n");

        for (TraceabilityMapping mapping : mappings.stream()
                .sorted(Comparator.comparing(TraceabilityMapping::getDomain))
                .toList()) {

            renderMapping(sb, mapping);
            sb.append("\n");
        }
    }

    private void renderMapping(StringBuilder sb, TraceabilityMapping mapping) {
        // A domain can have several DTO↔Entity pairs (e.g. EmployeeRequest and EmployeeResponse
        // both under "EMPLOYEE") — anchor on domain+DTO+entity, not domain alone, or every pair
        // beyond the first collides on the same #domain-xxx anchor and becomes unreachable.
        sb.append("## Domain: ").append(mapping.getTitle()).append(" {#").append(mappingAnchor(mapping)).append("}\n\n");
        sb.append("**DTO:** `").append(mapping.getDtoSimpleName()).append("`  \n");
        sb.append("**Entity:** `").append(mapping.getEntitySimpleName()).append("`  \n\n");

        // Main traceability table
        sb.append("| UI Label | DTO Field | DTO Type | Entity Field | Column | Mapping Type | Confidence | Rules |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");

        for (TraceabilityRow row : mapping.getRows()) {
            String uiLabel = row.getUiLabel() != null ? row.getUiLabel() : "(auto-labeled)";
            String dtoField = "`" + row.getDtoFieldName() + "`";
            String dtoType = "`" + abbreviateType(row.getDtoType()) + "`";

            String entityField = row.getEntityFieldName() != null
                    ? "`" + row.getEntityFieldName() + "`"
                    : "[DTO-only]";

            String column = row.getColumnName() != null
                    ? "`" + row.getColumnName() + "`"
                    : "—";

            String mappingType = row.getMappingType().toString().toLowerCase()
                    .replace("_", " ");

            String confidence = row.getConfidence().toString().toLowerCase();
            if (row.getConfidence() == TraceabilityRow.Confidence.LOW) {
                confidence = "⚠️ " + confidence;
            }

            String rules = row.getTransformationRule();

            sb.append("| ").append(uiLabel)
                    .append(" | ").append(dtoField)
                    .append(" | ").append(dtoType)
                    .append(" | ").append(entityField)
                    .append(" | ").append(column)
                    .append(" | ").append(mappingType)
                    .append(" | ").append(confidence)
                    .append(" | ").append(rules)
                    .append(" |\n");
        }

        sb.append("\n");

        // Validation summary
        long withValidation = mapping.getRows().stream()
                .filter(r -> !r.getValidationRules().isEmpty())
                .count();

        if (withValidation > 0) {
            sb.append("**Validation Rules:** ").append(withValidation).append("/").append(mapping.getRows().size())
                    .append(" fields validated\n\n");
        }

        // Low-confidence warning
        long lowConfidence = mapping.getRows().stream()
                .filter(r -> r.getConfidence() == TraceabilityRow.Confidence.LOW)
                .count();

        if (lowConfidence > 0) {
            sb.append("⚠️ **Action Required:** ").append(lowConfidence)
                    .append(" field(s) marked with LOW confidence. Manual verification recommended.\n\n");
        }

        // Calculation details (Phase 2)
        renderCalculationDetails(sb, mapping);
    }

    /**
     * Renders calculation evidence per field (Phase 2a/2b): how and where each
     * calculated field is derived, with a warning when computed in multiple layers.
     *
     * <p>Package-private so {@link PersistenceMappingIndexRenderer} can embed this exact
     * section in {@code detailed_persistence_mappings/} without duplicating the DTO↔Entity
     * table both documents would otherwise render side by side.
     */
    void renderCalculationDetails(StringBuilder sb, TraceabilityMapping mapping) {
        List<TraceabilityRow> calculated = mapping.getRows().stream()
                .filter(TraceabilityRow::hasCalculationEvidence)
                .toList();

        if (calculated.isEmpty()) {
            return;
        }

        sb.append("#### Calculation Details\n\n");

        for (TraceabilityRow row : calculated) {
            // Only render evidence that carries a real formula (skip heuristic-only markers)
            List<CalculationEvidence> meaningful = row.getCalculationEvidence().stream()
                    .filter(ev -> ev.getExpression() != null && !ev.getExpression().isBlank())
                    .toList();
            if (meaningful.isEmpty()) {
                continue;
            }

            sb.append("**`").append(row.getDtoFieldName()).append("`**");
            if (row.isCalculatedInMultipleLayers()) {
                sb.append(" — ⚠️ calculated in multiple layers (backend & frontend), verify consistency");
            }
            sb.append("\n\n");

            for (CalculationEvidence ev : meaningful) {
                renderEvidenceBullet(sb, ev);
            }
            sb.append("\n");
        }
    }

    /**
     * Renders one calculation-evidence bullet with inputs, location, and (Tier 3) note.
     * FORMULA evidence is shown inline; SLICE evidence (no single formula exists) is cited
     * verbatim in a fenced code block — reading the original source beats any summary DSL.
     */
    private void renderEvidenceBullet(StringBuilder sb, CalculationEvidence ev) {
        String inputs = ev.getInputFields().isEmpty()
                ? "—" : "`" + String.join("`, `", ev.getInputFields()) + "`";
        String location = ev.getSourceClass();
        if (ev.getSourceMethod() != null) location += "." + ev.getSourceMethod() + "()";
        if (ev.getLineNumber() > 0) location += ":" + ev.getLineNumber();

        sb.append("- **[Tier ").append(ev.getTier()).append(", ")
                .append(ev.getConfidence().toString().toLowerCase()).append("]** via *")
                .append(ev.getLocusLabel()).append("*");

        if (ev.getKind() == CalculationEvidence.Kind.SLICE) {
            sb.append(":\n\n");
            appendFencedSlice(sb, ev, "  ");
        } else {
            sb.append(" — `").append(escapeInline(ev.getExpression())).append("`\n");
        }
        sb.append("  - Inputs: ").append(inputs).append("\n");
        sb.append("  - Location: `").append(location).append("`\n");
        if (ev.getTier() >= 3 && ev.getDescription() != null) {
            sb.append("  - _").append(ev.getDescription()).append("_\n");
        }
    }

    /** Appends a SLICE evidence's cited source as an indented fenced code block. */
    private void appendFencedSlice(StringBuilder sb, CalculationEvidence ev, String indent) {
        String lang = ev.getLanguage() != null ? ev.getLanguage() : "";
        sb.append(indent).append("```").append(lang).append("\n");
        for (String line : ev.getExpression().split("\n", -1)) {
            sb.append(indent).append(line).append("\n");
        }
        sb.append(indent).append("```\n");
    }

    private String escapeInline(String s) {
        if (s == null) return "";
        String trimmed = s.replace("\n", " ").replace("`", "'").trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 157) + "..." : trimmed;
    }

    private String abbreviateType(String type) {
        if (type == null) return "?";

        // Shorten common types for table readability
        return type
                .replaceAll("java\\.lang\\.", "")
                .replaceAll("java\\.util\\.", "")
                .replaceAll("java\\.time\\.", "");
    }

    private String slugify(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * Stable, unique anchor for one DTO↔Entity mapping — domain alone is not a unique key
     * (a domain typically has both a Request and a Response mapping).
     */
    private String mappingAnchor(TraceabilityMapping mapping) {
        return "domain-" + slugify(mapping.getDomain() + "-" + mapping.getDtoSimpleName()
                + "-" + mapping.getEntitySimpleName());
    }
}
