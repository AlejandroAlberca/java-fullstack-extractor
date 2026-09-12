package com.devmanchego.contextextractor.java.security;

import java.util.Collections;
import java.util.Set;

/**
 * Represents a security rule bound to an HTTP endpoint.
 * Captured from either URL pattern configuration (SecurityFilterChain) or method annotations.
 */
public record SecurityRule(
        String urlPattern,           // e.g. "/api/users/**", "/api/users/{id}"
        String httpMethod,           // GET, POST, PUT, PATCH, DELETE, or null for any
        Set<String> requiredRoles,   // e.g. {"ADMIN", "USER"}
        RuleStrategy strategy,       // REQUEST_MATCHER, SECURED, ROLES_ALLOWED, PRE_AUTHORIZE
        String source,                // e.g. "SecurityFilterChain" or "EmployeeController.getById()"
        String unparsedExpression    // raw text of a security expression the parser could not
                                      // fully decompose; null when nothing was left unexplained.
                                      // Never conflate this with "public" — the endpoint still
                                      // carries a real authorisation annotation the source code
                                      // enforces, this parser just couldn't fully explain it.
) {

    public enum RuleStrategy {
        REQUEST_MATCHER,    // requestMatchers() in SecurityFilterChain
        SECURED,            // @Secured annotation
        ROLES_ALLOWED,      // @RolesAllowed annotation
        PRE_AUTHORIZE,      // @PreAuthorize annotation (hasRole/hasAuthority/hasPermission)
        UI_FRAGMENT         // JSP <sec:authorize access="..."> gating a fragment's visibility
    }

    public SecurityRule {
        requiredRoles = Collections.unmodifiableSet(requiredRoles);
    }

    /** Convenience constructor for rules with nothing left unparsed (the common case). */
    public SecurityRule(String urlPattern, String httpMethod, Set<String> requiredRoles,
                         RuleStrategy strategy, String source) {
        this(urlPattern, httpMethod, requiredRoles, strategy, source, null);
    }

    public boolean hasComplexLogic() {
        return strategy == RuleStrategy.PRE_AUTHORIZE;
    }

    public boolean hasUnparsedExpression() {
        return unparsedExpression != null;
    }

    /**
     * {@code true} only when this rule genuinely requires no role — i.e. no roles were
     * extracted AND nothing was left unparsed. A rule with an unparsed expression is never
     * public: it carries a real authorisation annotation this parser simply couldn't fully
     * explain, and asserting it is public would state the opposite of what the source enforces.
     */
    public boolean isPublic() {
        return requiredRoles.isEmpty() && unparsedExpression == null;
    }
}
