package com.devmanchego.contextextractor.matching;

import com.devmanchego.contextextractor.java.model.HttpVerb;

import java.util.regex.Pattern;

/**
 * Converts raw path strings from various frameworks into a canonical
 * {@link NormalizedEndpoint} with {param} placeholders.
 *
 * Supported input forms:
 * - Spring/JAX-RS:   /api/items/{id}          → /api/items/{id}
 * - Micronaut:       /api/items/{id}           → /api/items/{id}  (same)
 * - Angular TS:      /api/items/${id}          → /api/items/{id}
 * - Angular TS:      /api/items/ + id          → /api/items/{dynamic}
 */
public final class PathNormalizer {

    // ${expression} → {expression}
    private static final Pattern TS_TEMPLATE_EXPR = Pattern.compile("\\$\\{([^}]+)}");
    // :param (Express/other)
    private static final Pattern COLON_PARAM = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    private PathNormalizer() {}

    /**
     * Returns a fully-canonical endpoint where every path parameter placeholder
     * (regardless of name) is replaced with the generic {@code {param}} token.
     *
     * Use this for map-key lookups so that {@code /session/{id}} and
     * {@code /session/{sessionId}} are treated as the same route.
     * Display and call-graph code should use the original {@link #normalize} result.
     */
    public static NormalizedEndpoint canonicalize(NormalizedEndpoint normalized) {
        String canonical = normalized.pathTemplate().replaceAll("\\{[^}]+}", "{param}");
        return new NormalizedEndpoint(normalized.verb(), canonical);
    }

    /**
     * Returns true when two already-canonicalized paths match with fuzzy semantics:
     * a {@code {param}} token on either side matches any single segment (including
     * a literal value like {@code AVAILABLE}).
     *
     * Both paths must have the same number of segments and the same HTTP verb.
     */
    public static boolean fuzzyPathMatch(NormalizedEndpoint a, NormalizedEndpoint b) {
        if (a.verb() != b.verb()) return false;
        String[] segsA = a.pathTemplate().split("/", -1);
        String[] segsB = b.pathTemplate().split("/", -1);
        if (segsA.length != segsB.length) return false;
        for (int i = 0; i < segsA.length; i++) {
            String sa = segsA[i];
            String sb = segsB[i];
            if (sa.equals(sb)) continue;
            if (sa.equals("{param}") || sb.equals("{param}")) continue;
            return false;
        }
        return true;
    }

    public static NormalizedEndpoint normalize(HttpVerb verb, String rawPath) {
        String path = rawPath == null ? "/" : rawPath.trim();

        // Normalize TS template literal placeholders
        path = TS_TEMPLATE_EXPR.matcher(path).replaceAll("{$1}");

        // Normalize :param style
        path = COLON_PARAM.matcher(path).replaceAll("{$1}");

        // Collapse double slashes
        path = path.replaceAll("/+", "/");

        // Ensure leading slash
        if (!path.startsWith("/")) path = "/" + path;

        // Remove trailing slash (except root)
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        return new NormalizedEndpoint(verb, path);
    }
}
