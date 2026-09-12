package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.security.SecurityMatrix;
import com.devmanchego.contextextractor.java.security.SecurityRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders a {@link SecurityMatrix} as Markdown for documentation.
 * Output: {@code api-spec-security-matrix.md}
 *
 * Contains:
 * - Summary of detected security strategies (RequestMatcher, @Secured, @RolesAllowed, @PreAuthorize, UI-fragment)
 * - List of identified roles
 * - Access control matrix (endpoint → required roles)
 * - List of unprotected endpoints
 * - UI-fragment authorisation (JSP {@code <sec:authorize>}), when the frontend is JSP + jQuery
 */
public final class SecurityMatrixRenderer {

    public static Path securityMatrixOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-security-matrix.md");
    }

    public String render(SecurityMatrix matrix) {
        if (!matrix.hasSecurityConfig()) {
            return renderNoSecurityConfig();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# SECURITY — ROLE-BASED ACCESS CONTROL MATRIX\n\n");

        renderSummary(sb, matrix);
        sb.append("\n");

        renderStrategiesDetected(sb, matrix);
        sb.append("\n");

        renderIdentifiedRoles(sb, matrix);
        sb.append("\n");

        renderAccessControlMatrix(sb, matrix);
        sb.append("\n");

        if (!matrix.unprotectedEndpoints().isEmpty()) {
            renderUnprotectedEndpoints(sb, matrix);
            sb.append("\n");
        }

        if (!matrix.uiFragmentRules().isEmpty()) {
            renderUiFragmentRules(sb, matrix);
            sb.append("\n");
        }

        return sb.toString();
    }

    private String renderNoSecurityConfig() {
        return """
                # SECURITY — ROLE-BASED ACCESS CONTROL MATRIX

                ⚠️ **No Spring Security configuration detected.**

                - No `SecurityFilterChain` bean found.
                - No method-level security annotations detected (`@Secured`, `@RolesAllowed`, `@PreAuthorize`).
                - All endpoints are accessible without authentication.

                **Recommendation:** Add Spring Security configuration if RBAC (role-based access control) is required.
                """;
    }

    private void renderSummary(StringBuilder sb, SecurityMatrix matrix) {
        sb.append("## Summary\n\n");
        sb.append("- **Security strategies detected:** ")
                .append(matrix.strategiesDetected().stream()
                        .map(s -> s.toString().replace("_", "-").toLowerCase())
                        .sorted()
                        .collect(Collectors.joining(", ")))
                .append("\n");
        sb.append("- **Total identified roles:** ").append(matrix.allIdentifiedRoles().size()).append("\n");
        sb.append("- **Protected endpoints:** ").append(matrix.endpointToRule().size()).append("\n");
        sb.append("- **Unprotected endpoints:** ").append(matrix.unprotectedEndpoints().size()).append("\n");
    }

    private void renderStrategiesDetected(StringBuilder sb, SecurityMatrix matrix) {
        sb.append("## Security Strategies Detected\n\n");
        sb.append("| Strategy | Detected | Notes |\n");
        sb.append("|----------|----------|-------|\n");

        for (SecurityRule.RuleStrategy strategy : SecurityRule.RuleStrategy.values()) {
            boolean detected = matrix.strategiesDetected().contains(strategy);
            String icon = detected ? "✓" : "✗";
            String notes = getStrategyNotes(strategy);
            sb.append("| ").append(strategy.toString().replace("_", "-").toLowerCase())
                    .append(" | ").append(icon).append(" | ").append(notes).append(" |\n");
        }
    }

    private String getStrategyNotes(SecurityRule.RuleStrategy strategy) {
        return switch (strategy) {
            case REQUEST_MATCHER -> "URL patterns in `SecurityFilterChain`";
            case SECURED -> "@Secured annotation";
            case ROLES_ALLOWED -> "@RolesAllowed (JSR-250) annotation";
            case PRE_AUTHORIZE -> "@PreAuthorize with SpEL";
            case UI_FRAGMENT -> "JSP `<sec:authorize>` gating a page fragment (not an HTTP endpoint)";
        };
    }

    private void renderIdentifiedRoles(StringBuilder sb, SecurityMatrix matrix) {
        sb.append("## Identified Roles\n\n");
        sb.append("| Role | Count |\n");
        sb.append("|------|-------|\n");

        Map<String, Integer> roleCounts = new LinkedHashMap<>();
        for (SecurityRule rule : matrix.endpointToRule().values()) {
            for (String role : rule.requiredRoles()) {
                roleCounts.merge(role, 1, Integer::sum);
            }
        }

        roleCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> sb.append("| `").append(e.getKey()).append("` | ").append(e.getValue())
                        .append(" endpoint(s) |\n"));

        if (roleCounts.isEmpty()) {
            sb.append("| (none identified) | — |\n");
        }
    }

    private void renderAccessControlMatrix(StringBuilder sb, SecurityMatrix matrix) {
        sb.append("## Access Control Matrix — Protected Endpoints\n\n");
        sb.append("| Endpoint | HTTP Method | Required Role(s) | Strategy | Source |\n");
        sb.append("|----------|-------------|------------------|----------|--------|\n");

        // Sort by endpoint for readability
        Map<String, SecurityRule> sorted = new TreeMap<>(matrix.endpointToRule());
        for (Map.Entry<String, SecurityRule> entry : sorted.entrySet()) {
            SecurityRule rule = entry.getValue();
            String endpoint = rule.urlPattern();
            String method = rule.httpMethod() != null ? rule.httpMethod() : "ANY";
            String roles = renderRequiredRoles(rule);
            String strategy = rule.strategy().toString().replace("_", "-").toLowerCase();
            String source = rule.source();

            sb.append("| ").append(endpoint).append(" | ").append(method).append(" | ")
                    .append(roles).append(" | ").append(strategy).append(" | ").append(source).append(" |\n");
        }

        if (matrix.endpointToRule().isEmpty()) {
            sb.append("| (no endpoints protected) | — | — | — | — |\n");
        }
    }

    /**
     * Renders the "Required Role(s)" cell. Never renders {@code (public)} for a rule that
     * carries an unparsed expression — the endpoint is protected by something this tool
     * couldn't fully decompose, and saying "public" would assert the opposite of what the
     * source code enforces. See {@link SecurityRule#isPublic()}.
     */
    private String renderRequiredRoles(SecurityRule rule) {
        if (!rule.requiredRoles().isEmpty()) {
            String roles = rule.requiredRoles().stream().sorted().collect(Collectors.joining("`, `", "`", "`"));
            if (rule.hasUnparsedExpression()) {
                roles += " (+ unparsed: `" + rule.unparsedExpression() + "`)";
            }
            return roles;
        }
        if (rule.hasUnparsedExpression()) {
            return "⚠ unparsed — not public: `" + rule.unparsedExpression() + "`";
        }
        return "(public)";
    }

    private void renderUnprotectedEndpoints(StringBuilder sb, SecurityMatrix matrix) {
        sb.append("## Unprotected Endpoints\n\n");
        sb.append("⚠️ The following endpoints have no explicit security constraints:\n\n");
        sb.append("| Endpoint |\n");
        sb.append("|----------|\n");

        for (String endpoint : matrix.unprotectedEndpoints().stream().sorted().collect(Collectors.toList())) {
            sb.append("| `").append(endpoint).append("` |\n");
        }
    }

    /**
     * A JSP {@code <sec:authorize>} gates whether a fragment of a page renders, not whether the
     * page itself is reachable — a materially weaker, different claim than the endpoint matrix
     * above, so it gets its own section rather than being folded into
     * "Protected Endpoints"/"Unprotected Endpoints" and diluting what those two already mean.
     */
    private void renderUiFragmentRules(StringBuilder sb, SecurityMatrix matrix) {
        sb.append("## UI-Fragment Authorisation (JSP `<sec:authorize>`)\n\n");
        sb.append("⚠️ These gate whether a fragment of a page renders for the current user — ")
                .append("not whether the page itself, or any endpoint it calls, is reachable. ")
                .append("A page with no listed fragment rule may still be reachable only through ")
                .append("its own URL-level or endpoint-level protection, if any (see above).\n\n");
        sb.append("| Page | Required Role(s) | Source |\n");
        sb.append("|------|------------------|--------|\n");

        for (SecurityRule rule : matrix.uiFragmentRules()) {
            sb.append("| ").append(rule.urlPattern()).append(" | ").append(renderRequiredRoles(rule))
                    .append(" | ").append(rule.source()).append(" |\n");
        }
    }
}
