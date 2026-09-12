package com.devmanchego.contextextractor.java.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consolidated security matrix for the application.
 * Merges rules from URL pattern configuration, method annotations, and (for a JSP + jQuery
 * frontend) UI-fragment-level authorisation tags.
 */
public record SecurityMatrix(
        boolean hasSecurityConfig,           // true if SecurityFilterChain or method annotations detected
        Set<String> allIdentifiedRoles,      // e.g. {"ADMIN", "USER", "ANONYMOUS"}
        Set<SecurityRule.RuleStrategy> strategiesDetected,  // which strategies were used
        Map<String, SecurityRule> endpointToRule,  // key = "GET /api/users", value = rule
        List<String> unprotectedEndpoints,   // endpoints without explicit security
        /**
         * Rules gating a UI fragment's visibility rather than an HTTP endpoint — a JSP
         * {@code <sec:authorize access="...">} block, one entry per block found. Kept separate
         * from {@link #endpointToRule} deliberately: that map is keyed by {@code "VERB path"}
         * and represents "is this endpoint reachable at all", a materially different claim from
         * "does this fragment of a reachable page render for this user". Folding the two
         * together would make an already-precise {@link #unprotectedEndpoints} count ambiguous
         * about which kind of gap it's reporting.
         */
        List<SecurityRule> uiFragmentRules
) {
    /** Backward-compatible: no UI-fragment rules (every framework except JSP + jQuery). */
    public SecurityMatrix(boolean hasSecurityConfig, Set<String> allIdentifiedRoles,
                          Set<SecurityRule.RuleStrategy> strategiesDetected,
                          Map<String, SecurityRule> endpointToRule, List<String> unprotectedEndpoints) {
        this(hasSecurityConfig, allIdentifiedRoles, strategiesDetected, endpointToRule,
                unprotectedEndpoints, List.of());
    }
}
