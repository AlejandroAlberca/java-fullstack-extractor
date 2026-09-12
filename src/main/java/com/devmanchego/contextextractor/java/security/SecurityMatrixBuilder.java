package com.devmanchego.contextextractor.java.security;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a consolidated {@link SecurityMatrix} by merging:
 * 1. URL-pattern rules from SecurityFilterChain
 * 2. Method-annotation rules from @Secured, @RolesAllowed, @PreAuthorize
 *
 * Precedence: Method annotations override URL patterns (more specific).
 */
public final class SecurityMatrixBuilder {

    private static final Logger log = LoggerFactory.getLogger(SecurityMatrixBuilder.class);

    /**
     * Builds the security matrix from extracted endpoints and configuration rules.
     *
     * @param endpoints all extracted HTTP endpoints
     * @param urlRules  URL-pattern security rules from SecurityFilterChain
     * @return consolidated security matrix
     */
    public static SecurityMatrix build(List<EndpointInfo> endpoints, List<SecurityRule> urlRules) {
        return build(endpoints, urlRules, List.of());
    }

    /**
     * @param uiFragmentRules JSP {@code <sec:authorize>} rules (Phase 02) — gate a page fragment's
     *                        visibility, not an HTTP endpoint's reachability; kept in their own
     *                        list on {@link SecurityMatrix} rather than folded into
     *                        {@code endpointToRule}, and contribute their roles and the
     *                        {@link SecurityRule.RuleStrategy#UI_FRAGMENT} strategy like any other
     *                        source, but never affect {@code unprotectedEndpoints}.
     */
    public static SecurityMatrix build(List<EndpointInfo> endpoints, List<SecurityRule> urlRules,
                                       List<SecurityRule> uiFragmentRules) {
        Set<String> allRoles = new HashSet<>();
        Map<String, SecurityRule> endpointToRule = new HashMap<>();
        List<String> unprotected = new ArrayList<>();
        Set<SecurityRule.RuleStrategy> strategies = new HashSet<>();

        // Collect all rules from URL patterns
        for (SecurityRule urlRule : urlRules) {
            allRoles.addAll(urlRule.requiredRoles());
            strategies.add(SecurityRule.RuleStrategy.REQUEST_MATCHER);
            // Map URL pattern to rule (simplified: pattern → rule)
            endpointToRule.put(urlRule.urlPattern(), urlRule);
        }

        // Process endpoints: apply URL rules and check for method annotations
        for (EndpointInfo endpoint : endpoints) {
            String endpointKey = endpoint.getHttpVerb() + " " + endpoint.getPathTemplate();
            SecurityRule matchedRule = null;

            // First, check if method has explicit annotations (higher priority)
            if (endpoint.hasSecurityAnnotation()) {
                matchedRule = buildRuleFromAnnotation(endpoint);
                strategies.add(matchedRule.strategy());
                allRoles.addAll(matchedRule.requiredRoles());
            } else {
                // Fallback to URL pattern matching
                for (SecurityRule urlRule : urlRules) {
                    if (matchesUrlPattern(endpoint.getPathTemplate(), urlRule.urlPattern())) {
                        matchedRule = urlRule;
                        break;
                    }
                }
            }

            if (matchedRule != null) {
                endpointToRule.put(endpointKey, matchedRule);
            } else {
                // No security rule found
                unprotected.add(endpointKey);
            }
        }

        for (SecurityRule fragmentRule : uiFragmentRules) {
            allRoles.addAll(fragmentRule.requiredRoles());
            strategies.add(SecurityRule.RuleStrategy.UI_FRAGMENT);
        }

        boolean hasSecurityConfig = !urlRules.isEmpty() || !uiFragmentRules.isEmpty() || endpoints.stream()
                .anyMatch(EndpointInfo::hasSecurityAnnotation);

        log.info("SecurityMatrix: {} roles, {} endpoints, {} unprotected, {} UI-fragment rule(s), strategies: {}",
                allRoles.size(), endpointToRule.size(), unprotected.size(), uiFragmentRules.size(), strategies);

        return new SecurityMatrix(
                hasSecurityConfig,
                allRoles,
                strategies,
                endpointToRule,
                unprotected,
                uiFragmentRules
        );
    }

    private static boolean matchesUrlPattern(String pathTemplate, String pattern) {
        // Simplified matching: /api/users matches /api/users/** and /api/users/{id}
        String normalized = pathTemplate.replaceAll("\\{.*?}", "*");
        String patternNormalized = pattern.replaceAll("\\{.*?}", "*");
        return normalized.startsWith(patternNormalized.replace("**", ""));
    }

    private static SecurityRule buildRuleFromAnnotation(EndpointInfo endpoint) {
        String primaryAnnotation = primaryAnnotationName(endpoint);
        RoleExtraction extraction = extractRolesFromAnnotation(endpoint);
        SecurityRule.RuleStrategy strategy = strategyFor(primaryAnnotation);

        return new SecurityRule(
                endpoint.getPathTemplate(),
                endpoint.getHttpVerb().toString(),
                extraction.roles(),
                strategy,
                sourceFor(endpoint, primaryAnnotation),
                extraction.unparsedExpression()
        );
    }

    /**
     * Which of the (possibly several) security annotations on this endpoint determines the
     * reported strategy, by the same precedence the old single-annotation code used:
     * @Secured, then @RolesAllowed, then @PreAuthorize.
     */
    private static String primaryAnnotationName(EndpointInfo endpoint) {
        if (endpoint.hasAnnotation("Secured")) return "Secured";
        if (endpoint.hasAnnotation("RolesAllowed")) return "RolesAllowed";
        if (endpoint.hasAnnotation("PreAuthorize")) return "PreAuthorize";
        return null;
    }

    private static SecurityRule.RuleStrategy strategyFor(String annotationName) {
        if ("Secured".equals(annotationName)) return SecurityRule.RuleStrategy.SECURED;
        if ("RolesAllowed".equals(annotationName)) return SecurityRule.RuleStrategy.ROLES_ALLOWED;
        if ("PreAuthorize".equals(annotationName)) return SecurityRule.RuleStrategy.PRE_AUTHORIZE;
        return SecurityRule.RuleStrategy.REQUEST_MATCHER;
    }

    /**
     * Names the declaring class explicitly when the rule came from a class-level annotation
     * inherited by this method — the whole point of P1 is that this case is reported as
     * protected, with a source a reader can trust, rather than silently dropped.
     */
    private static String sourceFor(EndpointInfo endpoint, String primaryAnnotation) {
        String suffix = endpoint.getControllerName() + "." + endpoint.getMethodName() + "()";
        if (primaryAnnotation != null
                && endpoint.getAnnotationSource(primaryAnnotation) == EndpointInfo.AnnotationSource.CLASS) {
            return endpoint.getControllerName() + " (class-level) → " + endpoint.getMethodName() + "()";
        }
        return suffix;
    }

    /**
     * Result of merging every security annotation present on an endpoint: the union of
     * roles/permissions extracted across all of them, and — when something couldn't be
     * decomposed — the raw expression text to report instead of silently treating the
     * endpoint as public.
     */
    private record RoleExtraction(Set<String> roles, String unparsedExpression) {}

    private static RoleExtraction extractRolesFromAnnotation(EndpointInfo endpoint) {
        Set<String> roles = new LinkedHashSet<>();
        List<String> unparsedParts = new ArrayList<>();

        // @Secured("ADMIN") or @Secured({"ADMIN", "USER"})
        String secured = endpoint.getAnnotationValue("Secured");
        if (secured != null) {
            roles.addAll(parseRoleList(secured));
        }

        // @RolesAllowed({"ADMIN", "USER"})
        String rolesAllowed = endpoint.getAnnotationValue("RolesAllowed");
        if (rolesAllowed != null) {
            roles.addAll(parseRoleList(rolesAllowed));
        }

        // @PreAuthorize("hasRole('ADMIN')"), @PreAuthorize("hasPermission('', 'ACHAT')"), ...
        String preAuth = endpoint.getAnnotationValue("PreAuthorize");
        if (preAuth != null) {
            PreAuthorizeExpressionParser.Result result = PreAuthorizeExpressionParser.parse(preAuth);
            roles.addAll(result.roles());
            if (!result.fullyParsed()) {
                unparsedParts.add("@PreAuthorize(\"" + preAuth + "\")");
            }
        }

        if (roles.isEmpty() && endpoint.hasSecurityAnnotation()) {
            // Every annotation present failed to yield a role/permission this parser
            // recognises. The endpoint is still protected by *something* — report that
            // verbatim rather than letting it render as public.
            List<String> raw = new ArrayList<>();
            if (secured != null) raw.add("@Secured(\"" + secured + "\")");
            if (rolesAllowed != null) raw.add("@RolesAllowed(\"" + rolesAllowed + "\")");
            if (preAuth != null) raw.add("@PreAuthorize(\"" + preAuth + "\")");
            return new RoleExtraction(roles, String.join("; ", raw));
        }

        return new RoleExtraction(roles, unparsedParts.isEmpty() ? null : String.join("; ", unparsedParts));
    }

    private static Set<String> parseRoleList(String value) {
        Set<String> roles = new HashSet<>();
        // Extract quoted strings: "ADMIN", "USER", etc. — @Secured/@RolesAllowed values are
        // plain string lists, never SpEL, so a straight quoted-substring scan is exact.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("['\"]([^'\"]*)['\"]");
        java.util.regex.Matcher m = p.matcher(value);
        while (m.find()) {
            String role = m.group(1);
            if (!role.isEmpty()) roles.add(role);
        }
        return roles;
    }
}
