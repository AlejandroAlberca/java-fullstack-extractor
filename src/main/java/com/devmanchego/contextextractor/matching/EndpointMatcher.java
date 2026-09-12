package com.devmanchego.contextextractor.matching;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.java.model.EndpointInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Matches Angular HTTP calls to Java backend endpoints.
 *
 * Both sides are normalized to {@link NormalizedEndpoint} before comparison.
 * DTO ↔ TS Model correlation is derived as a consequence of endpoint matching
 * (not by name similarity).
 */
public final class EndpointMatcher {

    private static final Logger log = LoggerFactory.getLogger(EndpointMatcher.class);

    private EndpointMatcher() {}

    /**
     * @param angularProject  Angular analysis result (Strategy A or B)
     * @param javaEndpoints   All endpoints extracted from the Java backend
     * @param tsModels        All TypeScript models extracted from Angular
     * @return matching result with lists of matched flows, unmatched Angular calls,
     *         and unmatched Java endpoints
     */
    public static MatchResult match(AngularProject angularProject,
                                    List<EndpointInfo> javaEndpoints,
                                    List<TsModelInfo> tsModels) {
        return match(angularProject, javaEndpoints, tsModels, false);
    }

    /**
     * @param strictMatching when {@code true}, only exact canonical matches are accepted;
     *                       when {@code false} (default), a fuzzy fallback also matches
     *                       literal path segments against {@code {param}} variables.
     */
    public static MatchResult match(AngularProject angularProject,
                                    List<EndpointInfo> javaEndpoints,
                                    List<TsModelInfo> tsModels,
                                    boolean strictMatching) {

        // Build lookup: canonical NormalizedEndpoint → EndpointInfo (Java side).
        // We use PathNormalizer.canonicalize() so that {id} and {sessionId} in the
        // same path position are treated as equivalent during matching.
        Map<NormalizedEndpoint, EndpointInfo> javaIndex = new LinkedHashMap<>();
        Map<NormalizedEndpoint, NormalizedEndpoint> canonicalToNormalized = new LinkedHashMap<>();
        // Ordered list of canonical keys for fuzzy scan (preserves insertion order).
        List<NormalizedEndpoint> javaCanonicalList = new ArrayList<>();
        for (EndpointInfo ep : javaEndpoints) {
            NormalizedEndpoint normalized = PathNormalizer.normalize(ep.getHttpVerb(), ep.getPathTemplate());
            NormalizedEndpoint canonical  = PathNormalizer.canonicalize(normalized);
            if (javaIndex.put(canonical, ep) != null) {
                log.warn("Duplicate normalized Java endpoint: {} {} (only last occurrence kept in index)",
                        ep.getHttpVerb(), ep.getPathTemplate());
            } else {
                javaCanonicalList.add(canonical);
            }
            canonicalToNormalized.put(canonical, normalized);
        }

        // Build model index: typeName → all TsModelInfo with that name.
        // Multiple entries arise from re-exports (index.ts barrels) or genuinely
        // different types that share a name across modules.
        Map<String, List<TsModelInfo>> modelsByName = new LinkedHashMap<>();
        for (TsModelInfo m : tsModels) {
            modelsByName.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
        }
        modelsByName.forEach((name, list) -> {
            if (list.size() < 2) return;
            long distinctShapes = list.stream()
                    .map(m -> m.getFields().stream()
                            .map(f -> f.getName() + ":" + f.getType())
                            .sorted().collect(Collectors.joining(",")))
                    .distinct().count();
            if (distinctShapes > 1) {
                log.warn("TS model '{}' declared in {} files with different shapes — "
                        + "will pick closest to calling service: {}",
                        name, list.size(),
                        list.stream().map(TsModelInfo::getFilePath).collect(Collectors.joining(", ")));
            } else {
                log.debug("TS model '{}' appears in {} files with identical shape (re-export); using closest.",
                        name, list.size());
            }
        });

        List<MatchedFlow> flows = new ArrayList<>();
        List<UnmatchedAngularCall> unmatchedAngular = new ArrayList<>();
        Set<NormalizedEndpoint> matchedCanonicalKeys = new HashSet<>();

        for (ServiceInfo svc : angularProject.getServices()) {
            for (HttpCallInfo call : svc.getHttpCalls()) {
                NormalizedEndpoint normalized = PathNormalizer.normalize(call.getHttpVerb(), call.getUrlTemplate());
                NormalizedEndpoint canonical  = PathNormalizer.canonicalize(normalized);
                EndpointInfo javaEp = javaIndex.get(canonical);

                boolean fuzzy = false;
                if (javaEp == null && !strictMatching) {
                    // Fuzzy fallback: {param} on either side matches any literal segment.
                    NormalizedEndpoint fuzzyKey = javaCanonicalList.stream()
                            .filter(k -> PathNormalizer.fuzzyPathMatch(canonical, k))
                            .findFirst()
                            .orElse(null);
                    if (fuzzyKey != null) {
                        javaEp = javaIndex.get(fuzzyKey);
                        fuzzy = true;
                        log.debug("Fuzzy match: {} {} ↔ {}", call.getHttpVerb(),
                                call.getUrlTemplate(), fuzzyKey.pathTemplate());
                    }
                }

                if (javaEp == null) {
                    log.debug("No Java endpoint found for {} {}", call.getHttpVerb(), call.getUrlTemplate());
                    unmatchedAngular.add(new UnmatchedAngularCall(svc, call, normalized));
                } else {
                    matchedCanonicalKeys.add(canonical);
                    TsModelInfo responseModel = resolveModel(
                            stripArraySuffix(call.getResponseType()), svc.getFilePath(), modelsByName);
                    TsModelInfo requestModel  = resolveModel(
                            stripArraySuffix(call.getBodyType()), svc.getFilePath(), modelsByName);
                    flows.add(new MatchedFlow(svc, call, javaEp, responseModel, requestModel, fuzzy));
                    log.debug("Matched{}: {} {} ↔ {}#{}", fuzzy ? " (fuzzy)" : "",
                            call.getHttpVerb(), canonical.pathTemplate(),
                            javaEp.getControllerClass(), javaEp.getMethodName());
                }
            }
        }

        // Unmatched Java endpoints (no Angular call targets them).
        // Static routes (SPA forward controllers) are intentionally unmatched — exclude them.
        List<EndpointInfo> unmatchedJava = javaIndex.entrySet().stream()
                .filter(e -> !matchedCanonicalKeys.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .filter(ep -> !ep.isStaticRoute())
                .collect(Collectors.toList());

        log.info("Matching complete: {} flows, {} unmatched Angular calls, {} unmatched Java endpoints.",
                flows.size(), unmatchedAngular.size(), unmatchedJava.size());

        return new MatchResult(flows, unmatchedAngular, unmatchedJava);
    }

    /**
     * Picks the best {@link TsModelInfo} for {@code typeName} given the file path of the
     * service that references it. When there is only one candidate the choice is trivial.
     * When there are multiple (re-exports or same-named types in different modules) we
     * prefer the one whose file path shares the longest common prefix with {@code serviceFilePath},
     * which corresponds to the model that is "closest" in the directory tree.
     */
    private static TsModelInfo resolveModel(String typeName, String serviceFilePath,
                                             Map<String, List<TsModelInfo>> modelsByName) {
        if (typeName == null) return null;
        List<TsModelInfo> candidates = modelsByName.get(typeName);
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        return candidates.stream()
                .max(Comparator.comparingInt(m -> commonPrefixLength(m.getFilePath(), serviceFilePath)))
                .orElse(candidates.get(0));
    }

    private static int commonPrefixLength(String a, String b) {
        if (a == null || b == null) return 0;
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) {
            if (Character.toLowerCase(a.charAt(i)) != Character.toLowerCase(b.charAt(i))) return i;
        }
        return len;
    }

    private static String stripArraySuffix(String type) {
        if (type == null) return null;
        return type.endsWith("[]") ? type.substring(0, type.length() - 2) : type;
    }

    // -----------------------------------------------------------------------
    // Result types
    // -----------------------------------------------------------------------

    public record MatchResult(
            List<MatchedFlow> flows,
            List<UnmatchedAngularCall> unmatchedAngularCalls,
            List<EndpointInfo> unmatchedJavaEndpoints) {}

    public record UnmatchedAngularCall(
            ServiceInfo service,
            HttpCallInfo call,
            NormalizedEndpoint normalizedKey) {}
}
