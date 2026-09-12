package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.model.EntityInfo;
import com.devmanchego.contextextractor.java.model.MappingRow;
import com.devmanchego.contextextractor.java.model.PersistenceMapping;
import com.devmanchego.contextextractor.java.model.TraceabilityMapping;
import com.devmanchego.contextextractor.java.model.TraceabilityRow;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders the per-element index + detail documents for DTO ↔ Entity persistence mappings,
 * mirroring one mapping ({@code index-spec-persistence-mappings.md} entry) per
 * {@code detailed_persistence_mappings/<slug>.md} detail document.
 *
 * <p>Same content as {@code MarkdownRenderer}'s "4. PERSISTENCE MAPPING" section, split so
 * an AI assistant can load a single mapping instead of the whole document.
 */
public final class PersistenceMappingIndexRenderer {

    private final TraceabilityRenderer traceabilityRenderer = new TraceabilityRenderer();

    /** Stable per-mapping slug, shared between the category index and its detail file name. */
    public String mappingSlug(PersistenceMapping pm) {
        String raw = pm.getDto().getSimpleName() + "-" + pm.getEntity().getSimpleName();
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * Renders {@code index-spec-persistence-mappings.md}: a listing of every DTO ↔ Entity
     * mapping linking to its own detail document under {@code detailed_persistence_mappings/<slug>.md}.
     */
    public String renderCategoryIndex(List<PersistenceMapping> mappings) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Persistence Mappings Index\n\n");
        sb.append("*DTO ↔ JPA Entity field mapping, relations, and repository queries.*\n\n");

        if (mappings.isEmpty()) {
            sb.append("*No persistence mappings found.*\n");
            return sb.toString();
        }

        for (PersistenceMapping pm : mappings) {
            sb.append("- [`").append(pm.getDto().getSimpleName()).append("` ↔ `")
              .append(pm.getEntity().getSimpleName()).append("`](./detailed_persistence_mappings/")
              .append(mappingSlug(pm)).append(".md)\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** Renders one mapping as a standalone document for {@code detailed_persistence_mappings/<slug>.md}. */
    public String renderMappingDetail(PersistenceMapping pm) {
        return renderMappingDetail(pm, null, "");
    }

    /**
     * @param traceability   the matching UI ↔ DTO ↔ Entity ↔ Database traceability mapping for
     *                       this DTO/Entity pair, or {@code null} if none was extracted. When
     *                       present, its UI Label is folded into the Field Mapping table (rather
     *                       than duplicating a whole second table) and its calculation evidence
     *                       is appended as its own section — this is Field Traceability's content,
     *                       merged here instead of living in a separate indexed category.
     * @param relatedSection pre-rendered {@code ## Related} block to append (may be empty).
     */
    public String renderMappingDetail(PersistenceMapping pm, TraceabilityMapping traceability, String relatedSection) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(pm.getDto().getSimpleName()).append(" ↔ ")
          .append(pm.getEntity().getSimpleName()).append("\n\n");

        if (pm.hasMapper()) {
            sb.append("**Mapper:** `").append(pm.getMapperClass()).append("`\n\n");
        }

        Map<String, TraceabilityRow> uiRowsByDtoField = traceability == null ? Map.of()
                : traceability.getRows().stream().collect(Collectors.toMap(
                        TraceabilityRow::getDtoFieldName, r -> r, (a, b) -> a));
        boolean hasUiLabels = !uiRowsByDtoField.isEmpty();

        sb.append("## Field Mapping\n\n");
        if (hasUiLabels) {
            sb.append("| UI Label | DTO Field | DTO Type | Entity Field | Entity Type | Confidence |\n");
            sb.append("|---|---|---|---|---|---|\n");
        } else {
            sb.append("| DTO Field | DTO Type | Entity Field | Entity Type | Confidence |\n");
            sb.append("|---|---|---|---|---|\n");
        }
        for (MappingRow row : pm.getRows()) {
            String confidence = switch (row.getConfidence()) {
                case EXPLICIT  -> "explicit";
                case IMPLICIT  -> "(implicit)";
                case NO_MATCH  -> "[no match]";
            };
            if (row.isDtoOnly())    confidence += " [DTO-only]";
            if (row.isEntityOnly()) confidence += " [Entity-only]";
            if (hasUiLabels) {
                TraceabilityRow uiRow = uiRowsByDtoField.get(row.getDtoField());
                String uiLabel = uiRow != null && uiRow.getUiLabel() != null ? uiRow.getUiLabel() : "(auto-labeled)";
                sb.append("| ").append(uiLabel).append(" | ");
            } else {
                sb.append("| ");
            }
            sb.append("`").append(row.getDtoField()).append("` | `").append(row.getDtoType())
              .append("` | `").append(row.getEntityField()).append("` | `")
              .append(row.getEntityType()).append("` | ").append(confidence).append(" |\n");
        }
        sb.append("\n");

        if (traceability != null) {
            traceabilityRenderer.renderCalculationDetails(sb, traceability);
        }

        EntityInfo entity = pm.getEntity();
        if (!entity.getRelations().isEmpty()) {
            sb.append("## Relations\n\n");
            sb.append("| Field | Type | Kind | Join Column | Mapped By |\n");
            sb.append("|---|---|---|---|---|\n");
            entity.getRelations().forEach(r ->
                    sb.append("| `").append(r.getFieldName()).append("` | `")
                      .append(r.getTargetEntityType()).append("` | ")
                      .append(r.getKind()).append(" | ")
                      .append(r.getJoinColumn() != null ? "`" + r.getJoinColumn() + "`" : "—").append(" | ")
                      .append(r.getMappedBy() != null ? "`" + r.getMappedBy() + "`" : "—").append(" |\n"));
            sb.append("\n");
        }

        if (!entity.getRepositoryMethods().isEmpty()) {
            sb.append("## Repository\n\n");
            sb.append("| Method | Return Type | Query |\n");
            sb.append("|---|---|---|\n");
            entity.getRepositoryMethods().forEach(rm ->
                    sb.append("| `").append(rm.getMethodName()).append("` | `")
                      .append(rm.getReturnType()).append("` | ")
                      .append(rm.hasQuery() ? "`" + rm.getQueryLiteral().replace("|", "\\|") + "`" : "—")
                      .append(" |\n"));
            sb.append("\n");
        }

        if (relatedSection != null && !relatedSection.isBlank()) {
            sb.append(relatedSection);
        }
        return sb.toString();
    }
}
