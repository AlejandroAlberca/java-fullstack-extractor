package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.TsFieldInfo;
import com.devmanchego.contextextractor.angular.model.TsModelInfo;
import com.devmanchego.contextextractor.java.model.DtoInfo;
import com.devmanchego.contextextractor.java.model.FieldInfo;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import com.devmanchego.contextextractor.matching.MatchedFlow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the per-element index + detail documents for TypeScript ↔ Java DTO data
 * contracts, mirroring {@code MarkdownRenderer}'s "3. DATA MODEL" section but collapsed
 * to one entry per unique (Java type, TS type) pair — the same DTO/model pair can appear
 * as the response of several matched flows, and should only get one detail document.
 */
public final class DataContractIndexRenderer {

    private static final List<String> NORMALIZE_SUFFIXES = List.of(
            "DTO", "Dto", "Response", "Request", "Command", "Query",
            "Payload", "Result", "Data", "Info", "Params", "Body", "Form");

    /** One unique Java type ↔ TypeScript model pairing, deduplicated across matched flows. */
    public record Contract(String javaType, TsModelInfo tsModel, DtoInfo dto) {}

    /**
     * Walks every matched flow's request/response types and collapses them into one
     * {@link Contract} per unique (Java type, TS model name) pair, in first-seen order.
     */
    public List<Contract> collectContracts(EndpointMatcher.MatchResult matchResult, Map<String, DtoInfo> dtosMap) {
        Map<String, Contract> byKey = new LinkedHashMap<>();

        for (MatchedFlow flow : matchResult.flows()) {
            var ep = flow.getJavaEndpoint();

            if (ep.getResponseType() != null && flow.getTsResponseModel().isPresent()) {
                addContract(byKey, ep.getResponseType(), flow.getTsResponseModel().get(), dtosMap);
            }
            if (ep.getBodyParameterType() != null && flow.getTsRequestModel().isPresent()) {
                addContract(byKey, ep.getBodyParameterType(), flow.getTsRequestModel().get(), dtosMap);
            }
        }

        return List.copyOf(byKey.values());
    }

    private void addContract(Map<String, Contract> byKey, String javaType, TsModelInfo tsModel,
                             Map<String, DtoInfo> dtosMap) {
        // A "DepartmentResponse" list endpoint and a "DepartmentResponse[]" single-item endpoint
        // are the same contract — collapse the array suffix so they dedupe (and slug) identically.
        String normalizedJavaType = javaType.endsWith("[]") ? javaType.substring(0, javaType.length() - 2) : javaType;
        String key = normalizedJavaType + "|" + tsModel.getName();
        byKey.computeIfAbsent(key, k -> new Contract(normalizedJavaType, tsModel, lookupDto(normalizedJavaType, dtosMap)));
    }

    /** Stable per-contract slug, shared between the category index and its detail file name. */
    public String contractSlug(Contract contract) {
        String raw = contract.javaType() + "-" + contract.tsModel().getName();
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * Renders {@code index-spec-data-contracts.md}: a listing of every unique data contract
     * linking to its own detail document under {@code detailed_data_contracts/<slug>.md}.
     */
    public String renderCategoryIndex(List<Contract> contracts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Data Contracts Index\n\n");
        sb.append("*Frontend (TypeScript) ↔ Backend (DTO) field contracts.*\n\n");

        if (contracts.isEmpty()) {
            sb.append("*No correlated data contracts found.*\n");
            return sb.toString();
        }

        for (Contract c : contracts) {
            sb.append("- [`").append(c.javaType()).append("` ↔ `").append(c.tsModel().getName())
              .append("`](./detailed_data_contracts/").append(contractSlug(c)).append(".md)\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** Renders one contract as a standalone document for {@code detailed_data_contracts/<slug>.md}. */
    public String renderContractDetail(Contract contract) {
        return renderContractDetail(contract, "");
    }

    /**
     * @param relatedSection pre-rendered {@code ## Related} block to append (may be empty).
     */
    public String renderContractDetail(Contract contract, String relatedSection) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(contract.javaType()).append(" ↔ ").append(contract.tsModel().getName()).append("\n\n");

        sb.append("| TS Field | TS Type | Java Field | Java Type | Rules |\n");
        sb.append("|---|---|---|---|---|\n");

        DtoInfo dto = contract.dto();
        Map<String, FieldInfo> dtoFieldMap = dto == null ? Map.of()
                : dto.getFields().stream().collect(java.util.stream.Collectors.toMap(
                        FieldInfo::getName, f -> f, (a, b) -> a));

        for (TsFieldInfo tsField : contract.tsModel().getFields()) {
            FieldInfo javaField = dtoFieldMap.get(tsField.getName());
            String javaFieldName = javaField != null ? javaField.getName() : "[no match]";
            String javaType = javaField != null ? javaField.getType() : "";
            String rules = javaField != null ? String.join(", ", javaField.getValidationRules()) : "";
            sb.append("| `").append(tsField.getName()).append(tsField.isOptional() ? "?" : "")
              .append("` | `").append(tsField.getType()).append("` | `")
              .append(javaFieldName).append("` | `").append(javaType)
              .append("` | ").append(rules).append(" |\n");
        }
        sb.append("\n");
        if (relatedSection != null && !relatedSection.isBlank()) {
            sb.append(relatedSection);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // DTO lookup — same normalization rules as MarkdownRenderer
    // -----------------------------------------------------------------------

    private DtoInfo lookupDto(String typeName, Map<String, DtoInfo> dtosMap) {
        if (typeName == null || dtosMap.isEmpty()) return null;
        DtoInfo found = dtosMap.get(typeName);
        if (found != null) return found;
        String base = typeName.endsWith("[]") ? typeName.substring(0, typeName.length() - 2) : typeName;
        found = dtosMap.get(base);
        if (found != null) return found;
        String normalizedTarget = normalizeDtoName(base);
        return dtosMap.values().stream()
                .filter(d -> normalizeDtoName(d.getSimpleName()).equalsIgnoreCase(normalizedTarget))
                .findFirst()
                .orElse(null);
    }

    private String normalizeDtoName(String name) {
        if (name == null) return "";
        for (String suffix : NORMALIZE_SUFFIXES) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                return name.substring(0, name.length() - suffix.length()).toLowerCase(Locale.ROOT);
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
