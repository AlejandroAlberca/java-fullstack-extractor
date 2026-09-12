package com.devmanchego.contextextractor.java.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityRuleTest {

    @Test
    void testPublicRule() {
        SecurityRule rule = new SecurityRule(
                "/api/health",
                "GET",
                Set.of(),
                SecurityRule.RuleStrategy.REQUEST_MATCHER,
                "SecurityFilterChain"
        );
        assertTrue(rule.isPublic());
        assertFalse(rule.hasComplexLogic());
    }

    @Test
    void testProtectedRule() {
        SecurityRule rule = new SecurityRule(
                "/api/users",
                "POST",
                Set.of("ADMIN", "USER"),
                SecurityRule.RuleStrategy.SECURED,
                "UserController.create()"
        );
        assertFalse(rule.isPublic());
        assertFalse(rule.hasComplexLogic());
        assertEquals(2, rule.requiredRoles().size());
    }

    @Test
    void testPreAuthorizeRule() {
        SecurityRule rule = new SecurityRule(
                "/api/admin/**",
                "DELETE",
                Set.of("ADMIN"),
                SecurityRule.RuleStrategy.PRE_AUTHORIZE,
                "AdminController.delete()"
        );
        assertTrue(rule.hasComplexLogic());
    }

    @Test
    void testUnparsedExpression_neverPublicEvenWithNoRoles() {
        SecurityRule rule = new SecurityRule(
                "/api/resource/{id}",
                "DELETE",
                Set.of(),
                SecurityRule.RuleStrategy.PRE_AUTHORIZE,
                "ResourceController.delete()",
                "@customEvaluator.check(#id)"
        );
        assertFalse(rule.isPublic(), "A rule carrying an unparsed expression is protected by "
                + "something this tool couldn't fully explain — it must never read as public");
        assertTrue(rule.hasUnparsedExpression());
    }

    @Test
    void testFiveArgConstructor_defaultsToNoUnparsedExpression() {
        SecurityRule rule = new SecurityRule(
                "/api/health",
                "GET",
                Set.of(),
                SecurityRule.RuleStrategy.REQUEST_MATCHER,
                "SecurityFilterChain"
        );
        assertFalse(rule.hasUnparsedExpression());
        assertTrue(rule.isPublic());
    }
}
