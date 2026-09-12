package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.security.SecurityMatrix;
import com.devmanchego.contextextractor.java.security.SecurityRule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityMatrixRendererTest {

    @Test
    void testRenderNoSecurityConfig() {
        SecurityMatrix matrix = new SecurityMatrix(
                false,
                Set.of(),
                Set.of(),
                Map.of(),
                List.of()
        );

        String output = new SecurityMatrixRenderer().render(matrix);

        assertNotNull(output);
        assertTrue(output.contains("No Spring Security configuration detected"));
        assertTrue(output.contains("# SECURITY"));
    }

    @Test
    void testRenderWithSecurityConfig() {
        SecurityRule rule = new SecurityRule(
                "/api/users",
                "GET",
                Set.of("ADMIN", "USER"),
                SecurityRule.RuleStrategy.REQUEST_MATCHER,
                "SecurityFilterChain"
        );

        Map<String, SecurityRule> endpointToRule = new HashMap<>();
        endpointToRule.put("GET /api/users", rule);

        SecurityMatrix matrix = new SecurityMatrix(
                true,
                Set.of("ADMIN", "USER"),
                Set.of(SecurityRule.RuleStrategy.REQUEST_MATCHER),
                endpointToRule,
                List.of()
        );

        String output = new SecurityMatrixRenderer().render(matrix);

        assertNotNull(output);
        assertTrue(output.contains("## Summary"));
        assertTrue(output.contains("## Identified Roles"));
        assertTrue(output.contains("## Access Control Matrix"));
        assertTrue(output.contains("ADMIN"));
        assertTrue(output.contains("USER"));
    }

    @Test
    void testRenderWithUnparsedExpression_neverShowsPublic() {
        SecurityRule rule = new SecurityRule(
                "/resource/{id}",
                "DELETE",
                Set.of(),
                SecurityRule.RuleStrategy.PRE_AUTHORIZE,
                "ResourceController.delete()",
                "@PreAuthorize(\"@customEvaluator.check(#id, authentication)\")"
        );

        Map<String, SecurityRule> endpointToRule = new HashMap<>();
        endpointToRule.put("DELETE /resource/{id}", rule);

        SecurityMatrix matrix = new SecurityMatrix(
                true,
                Set.of(),
                Set.of(SecurityRule.RuleStrategy.PRE_AUTHORIZE),
                endpointToRule,
                List.of()
        );

        String output = new SecurityMatrixRenderer().render(matrix);

        assertFalse(output.contains("| (public) |"));
        assertTrue(output.contains("unparsed"));
    }

    @Test
    void testRenderWithClassLevelSource_namesTheDeclaringClass() {
        SecurityRule rule = new SecurityRule(
                "/acheteur",
                "GET",
                Set.of("ACHAT"),
                SecurityRule.RuleStrategy.PRE_AUTHORIZE,
                "AcheteurController (class-level) → getAll()"
        );

        Map<String, SecurityRule> endpointToRule = new HashMap<>();
        endpointToRule.put("GET /acheteur", rule);

        SecurityMatrix matrix = new SecurityMatrix(
                true,
                Set.of("ACHAT"),
                Set.of(SecurityRule.RuleStrategy.PRE_AUTHORIZE),
                endpointToRule,
                List.of()
        );

        String output = new SecurityMatrixRenderer().render(matrix);

        assertTrue(output.contains("class-level"));
        assertTrue(output.contains("ACHAT"));
    }

    @Test
    void testRenderWithUnprotectedEndpoints() {
        SecurityRule rule = new SecurityRule(
                "/api/protected",
                "POST",
                Set.of("ADMIN"),
                SecurityRule.RuleStrategy.SECURED,
                "UserController.create()"
        );

        Map<String, SecurityRule> endpointToRule = new HashMap<>();
        endpointToRule.put("POST /api/protected", rule);

        SecurityMatrix matrix = new SecurityMatrix(
                true,
                Set.of("ADMIN"),
                Set.of(SecurityRule.RuleStrategy.SECURED),
                endpointToRule,
                List.of("GET /api/public", "GET /api/health")
        );

        String output = new SecurityMatrixRenderer().render(matrix);

        assertTrue(output.contains("## Unprotected Endpoints"));
        assertTrue(output.contains("GET /api/public"));
        assertTrue(output.contains("GET /api/health"));
    }

    @Test
    void testRenderWithUiFragmentRules_ownSectionNotMixedIntoEndpointTable() {
        SecurityRule fragmentRule = new SecurityRule(
                "/view/achat/fap",
                null,
                Set.of("ACHAT"),
                SecurityRule.RuleStrategy.UI_FRAGMENT,
                "fap.jsp (<sec:authorize access=\"hasPermission('','ACHAT')\">)"
        );

        SecurityMatrix matrix = new SecurityMatrix(
                true,
                Set.of("ACHAT"),
                Set.of(SecurityRule.RuleStrategy.UI_FRAGMENT),
                Map.of(),
                List.of(),
                List.of(fragmentRule)
        );

        String output = new SecurityMatrixRenderer().render(matrix);

        assertTrue(output.contains("## UI-Fragment Authorisation"));
        assertTrue(output.contains("/view/achat/fap"));
        assertTrue(output.contains("ACHAT"));
        // Must not be counted as a protected/unprotected HTTP endpoint — it isn't one.
        assertFalse(output.contains("## Access Control Matrix — Protected Endpoints\n\n| Endpoint | HTTP Method | Required Role(s) | Strategy | Source |\n|----------|-------------|------------------|----------|--------|\n| /view/achat/fap"));
    }

    @Test
    void testRenderWithNoUiFragmentRules_sectionOmitted() {
        SecurityRule rule = new SecurityRule(
                "/api/protected", "GET", Set.of("ADMIN"), SecurityRule.RuleStrategy.SECURED, "X.y()");
        SecurityMatrix matrix = new SecurityMatrix(
                true,
                Set.of("ADMIN"),
                Set.of(SecurityRule.RuleStrategy.SECURED),
                Map.of("GET /api/protected", rule),
                List.of()
        );

        String output = new SecurityMatrixRenderer().render(matrix);

        assertFalse(output.contains("UI-Fragment Authorisation"));
    }
}
