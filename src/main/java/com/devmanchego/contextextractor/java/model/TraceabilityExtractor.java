package com.devmanchego.contextextractor.java.model;

import com.devmanchego.contextextractor.angular.model.TsModelInfo;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Correlates UI ↔ DTO ↔ Entity ↔ Database for traceability documentation.
 *
 * Strategy:
 * 1. Start from PersistenceMapping (DTO ↔ Entity layer)
 * 2. Enrich DTO fields with TypeScript model info (if available)
 * 3. For each DTO field, attempt to find UI label from Angular form (by field name)
 * 4. Determine mapping type and confidence based on match quality and metadata
 */
public final class TraceabilityExtractor {

    private static final Logger log = LoggerFactory.getLogger(TraceabilityExtractor.class);

    /**
     * Authoritative calculation evidence for fields that did NOT map to any persistence
     * traceability row (e.g. computed fields of non-persisted DTOs). Populated by the
     * last {@link #extract} call; surfaced by the renderer as "invisible logic".
     */
    private final Map<String, List<CalculationEvidence>> orphanCalculations = new LinkedHashMap<>();

    /**
     * @return calculations detected on fields that don't appear in any traceability row.
     */
    public Map<String, List<CalculationEvidence>> getOrphanCalculations() {
        return orphanCalculations;
    }

    /**
     * Extracts traceability mappings from a collection of persistence mappings and TypeScript models.
     * Includes Phase 2a: detects literal field calculations in Java and Angular.
     *
     * @param persistenceMappings DTO ↔ Entity correlations
     * @param tsModels TypeScript models for UI
     * @param uiLabels UI field labels (controlName → label text)
     * @param compilationUnits All Java compilation units (for Phase 2a detection)
     * @return List of traceability mappings, grouped by domain
     */
    public List<TraceabilityMapping> extract(List<PersistenceMapping> persistenceMappings,
                                             List<TsModelInfo> tsModels,
                                             Map<String, Map<String, String>> uiLabels,
                                             List<CompilationUnit> compilationUnits) {
        return extract(persistenceMappings, tsModels, uiLabels, compilationUnits, Map.of());
    }

    /**
     * Phase 2c overload: also merges frontend value computations so a field computed in
     * both backend and frontend surfaces as a double-calculation.
     *
     * @param frontendCalculations field name → frontend value-computation evidences
     */
    public List<TraceabilityMapping> extract(List<PersistenceMapping> persistenceMappings,
                                             List<TsModelInfo> tsModels,
                                             Map<String, Map<String, String>> uiLabels,
                                             List<CompilationUnit> compilationUnits,
                                             Map<String, List<CalculationEvidence>> frontendCalculations) {
        // Phase 2a: Detect calculations (Tier 1 literal)
        Map<String, List<CalculationEvidence>> fieldCalculations =
                new FieldCalculationDetector().detect(compilationUnits);

        // Phase 2b: Detect Tier 2 write-site calculations (service layer) and merge
        Map<String, List<CalculationEvidence>> tier2 =
                new Tier2CalculationDetector().detect(compilationUnits);
        tier2.forEach((field, evidences) ->
                fieldCalculations.computeIfAbsent(field, k -> new ArrayList<>()).addAll(evidences));

        // Phase 3a/3c: Reconstruct calculations Tier 2 missed (intermediate vars, helpers)
        Map<String, List<CalculationEvidence>> tier3 =
                new Tier3CalculationDetector().detect(compilationUnits);
        tier3.forEach((field, evidences) ->
                fieldCalculations.computeIfAbsent(field, k -> new ArrayList<>()).addAll(evidences));

        // Phase 2c: Merge frontend value computations (for double-calculation detection)
        if (frontendCalculations != null) {
            frontendCalculations.forEach((field, evidences) ->
                    fieldCalculations.computeIfAbsent(field, k -> new ArrayList<>()).addAll(evidences));
        }

        List<TraceabilityMapping> results = new ArrayList<>();

        for (PersistenceMapping pm : persistenceMappings) {
            String domain = inferDomain(pm.getDto().getSimpleName());
            String dtoSimpleName = pm.getDto().getSimpleName();
            String entitySimpleName = pm.getEntity().getSimpleName();

            List<TraceabilityRow> rows = new ArrayList<>();

            // Find TypeScript model matching this DTO (by name heuristic)
            TsModelInfo tsModel = findTsModelForDto(tsModels, pm.getDto());
            Map<String, String> controlLabels = uiLabels.getOrDefault(dtoSimpleName, Map.of());

            // Correlate each DTO field across all layers
            for (MappingRow dtoEntityRow : pm.getRows()) {
                String dtoFieldName = dtoEntityRow.getDtoField();
                if (dtoFieldName.startsWith("[")) continue; // Skip special markers like [no match]

                // UI layer: look for label by field name
                String uiLabel = controlLabels.getOrDefault(dtoFieldName, null);
                String uiFieldName = tsModel != null ? dtoFieldName : null;

                // DTO layer
                String dtoType = dtoEntityRow.getDtoType();

                // Entity layer
                String entityFieldName = dtoEntityRow.getEntityField().startsWith("[")
                        ? null : dtoEntityRow.getEntityField();
                String entityType = dtoEntityRow.getEntityType();

                // Database layer
                String columnName = null;
                String columnType = null;
                boolean isNullable = true;
                boolean isPrimaryKey = false;
                boolean isGeneratedValue = false;
                List<String> validationRules = new ArrayList<>();

                // Enrich with entity field metadata
                if (entityFieldName != null) {
                    Optional<FieldInfo> entityFieldOpt = pm.getEntity().getFields().stream()
                            .filter(f -> f.getName().equals(entityFieldName))
                            .findFirst();

                    if (entityFieldOpt.isPresent()) {
                        FieldInfo ef = entityFieldOpt.get();
                        columnName = ef.getColumnName() != null ? ef.getColumnName() : toSnakeCase(entityFieldName);
                        columnType = ef.getType();
                        isNullable = ef.isNullable();
                        isPrimaryKey = ef.isPrimaryKey();
                        isGeneratedValue = ef.isGeneratedValue();
                        validationRules = new ArrayList<>(ef.getValidationRules());
                    }
                }

                // Determine mapping type and confidence
                TraceabilityRow.MappingType mappingType = determineMappingType(dtoEntityRow, validationRules);
                TraceabilityRow.Confidence confidence = determineConfidence(dtoEntityRow, mappingType, uiLabel);

                String transformationRule = buildTransformationRule(mappingType, dtoFieldName,
                        entityFieldName, isGeneratedValue, validationRules);

                // Phase 2a: Attach calculation evidence (Tier 1)
                TraceabilityRow.Builder rowBuilder = TraceabilityRow.builder()
                        .uiLabel(uiLabel)
                        .uiFieldName(uiFieldName)
                        .dtoFieldName(dtoFieldName)
                        .dtoType(dtoType)
                        .entityFieldName(entityFieldName)
                        .entityType(entityType)
                        .columnName(columnName)
                        .columnType(columnType)
                        .mappingType(mappingType)
                        .confidence(confidence)
                        .nullable(isNullable)
                        .primaryKey(isPrimaryKey)
                        .generatedValue(isGeneratedValue)
                        .transformationRule(transformationRule);

                // Attach backend calculation evidence
                if (fieldCalculations.containsKey(dtoFieldName)) {
                    fieldCalculations.get(dtoFieldName).forEach(rowBuilder::calculationEvidence);
                }

                // Mark as CALCULATED only if we found authoritative value-computation
                // evidence (a real formula, not the weak DTO-name heuristic or a display pipe).
                boolean hasAuthoritativeCalc = fieldCalculations.getOrDefault(dtoFieldName, List.of())
                        .stream()
                        .anyMatch(CalculationEvidence::isValueComputation);
                if (hasAuthoritativeCalc &&
                    mappingType != TraceabilityRow.MappingType.CALCULATED &&
                    mappingType != TraceabilityRow.MappingType.TRANSIENT) {
                    rowBuilder.mappingType(TraceabilityRow.MappingType.CALCULATED);
                }

                rows.add(rowBuilder.build());

                // Add validation rules
                validationRules.forEach(rule -> {
                    // Already added via builder, but could add more context here
                });
            }

            if (!rows.isEmpty()) {
                results.add(new TraceabilityMapping(domain, dtoSimpleName, entitySimpleName, rows));
            }
        }

        // Collect authoritative calculations for fields that never mapped to a row.
        Set<String> mappedFields = results.stream()
                .flatMap(m -> m.getRows().stream())
                .map(TraceabilityRow::getDtoFieldName)
                .collect(java.util.stream.Collectors.toSet());
        orphanCalculations.clear();
        fieldCalculations.forEach((field, evidences) -> {
            if (mappedFields.contains(field)) return;
            List<CalculationEvidence> authoritative = evidences.stream()
                    .filter(CalculationEvidence::isValueComputation)
                    .toList();
            if (!authoritative.isEmpty()) {
                orphanCalculations.put(field, authoritative);
            }
        });

        log.info("TraceabilityExtractor: extracted {} traceability mapping(s), {} orphan calculation field(s)",
                results.size(), orphanCalculations.size());
        return results;
    }

    private TraceabilityRow.MappingType determineMappingType(MappingRow row, List<String> validationRules) {
        // Transient: DTO field but no entity field
        if (row.isDtoOnly()) {
            return TraceabilityRow.MappingType.TRANSIENT;
        }

        // Enum: entity type looks like an enum
        if (row.getEntityType() != null && (row.getEntityType().endsWith("Status") ||
                row.getEntityType().endsWith("Type") ||
                row.getEntityType().endsWith("State"))) {
            return TraceabilityRow.MappingType.ENUM_CONSTANT;
        }

        // Direct: explicit mapping with same names
        if (row.getDtoField().equals(row.getEntityField()) &&
            row.getConfidence() == MappingRow.Confidence.EXPLICIT) {
            return TraceabilityRow.MappingType.DIRECT;
        }

        // Renamed: explicit mapping with different names
        if (row.getConfidence() == MappingRow.Confidence.EXPLICIT &&
            !row.getDtoField().equals(row.getEntityField())) {
            return TraceabilityRow.MappingType.RENAMED;
        }

        // Direct via implicit match
        if (row.getDtoField().equals(row.getEntityField()) &&
            row.getConfidence() == MappingRow.Confidence.IMPLICIT) {
            return TraceabilityRow.MappingType.DIRECT;
        }

        // Unmapped
        if (row.getConfidence() == MappingRow.Confidence.NO_MATCH) {
            return TraceabilityRow.MappingType.UNMAPPED;
        }

        return TraceabilityRow.MappingType.DIRECT;
    }

    private TraceabilityRow.Confidence determineConfidence(MappingRow row,
                                                           TraceabilityRow.MappingType mappingType,
                                                           String uiLabel) {
        if (row.getConfidence() == MappingRow.Confidence.EXPLICIT) {
            return TraceabilityRow.Confidence.HIGH;
        }

        if (row.getConfidence() == MappingRow.Confidence.IMPLICIT && mappingType == TraceabilityRow.MappingType.DIRECT) {
            return TraceabilityRow.Confidence.HIGH;
        }

        if (mappingType == TraceabilityRow.MappingType.TRANSIENT) {
            return TraceabilityRow.Confidence.MEDIUM;
        }

        if (row.getConfidence() == MappingRow.Confidence.NO_MATCH) {
            return TraceabilityRow.Confidence.LOW;
        }

        return TraceabilityRow.Confidence.MEDIUM;
    }

    private String buildTransformationRule(TraceabilityRow.MappingType type, String dtoField,
                                           String entityField, boolean isGenerated,
                                           List<String> validationRules) {
        StringBuilder rule = new StringBuilder();

        switch (type) {
            case DIRECT:
                rule.append("Direct mapping");
                break;
            case RENAMED:
                rule.append("Renamed: `").append(dtoField).append("` → `").append(entityField).append("`");
                break;
            case TRANSIENT:
                rule.append("Transient: not persisted to database");
                break;
            case ENUM_CONSTANT:
                rule.append("Enum/Constant");
                break;
            case CALCULATED:
                rule.append("Calculated: requires review");
                break;
            case UNMAPPED:
                rule.append("Unmapped (review)");
                break;
        }

        if (isGenerated) {
            rule.append(" (auto-generated)");
        }

        if (!validationRules.isEmpty()) {
            rule.append(" | Validations: ").append(String.join(", ", validationRules));
        }

        return rule.toString();
    }

    private TsModelInfo findTsModelForDto(List<TsModelInfo> tsModels, DtoInfo dto) {
        String dtoSimpleName = dto.getSimpleName();

        // Exact name match
        for (TsModelInfo ts : tsModels) {
            if (ts.getName().equalsIgnoreCase(dtoSimpleName)) {
                return ts;
            }
        }

        // Heuristic: match by removing Request/Response/Dto suffix
        String baseName = dtoSimpleName
                .replaceAll("(?:Request|Response|Dto|DTO)$", "");

        for (TsModelInfo ts : tsModels) {
            if (ts.getName().equalsIgnoreCase(baseName)) {
                return ts;
            }
        }

        return null;
    }

    private String inferDomain(String dtoSimpleName) {
        return dtoSimpleName
                .replaceAll("(?:Request|Response|Dto|DTO)$", "")
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase();
    }

    private String toSnakeCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
