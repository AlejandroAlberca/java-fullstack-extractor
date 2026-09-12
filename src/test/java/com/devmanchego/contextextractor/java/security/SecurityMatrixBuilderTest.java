package com.devmanchego.contextextractor.java.security;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityMatrixBuilderTest {

    @Test
    void testBuildWithNoSecurityConfig() {
        EndpointInfo endpoint = EndpointInfo.builder()
                .httpVerb(HttpVerb.GET)
                .pathTemplate("/api/public")
                .controllerClass("com.example.PublicController")
                .methodName("getPublic")
                .framework("Spring")
                .build();

        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(endpoint), List.of());

        assertFalse(matrix.hasSecurityConfig());
        assertTrue(matrix.unprotectedEndpoints().contains("GET /api/public"));
    }

    @Test
    void uiFragmentRules_contributeRolesAndTheStrategy_withoutAffectingUnprotectedEndpoints() {
        SecurityRule fragmentRule = new SecurityRule(
                "/view/dossier/liste", null, Set.of("DOSSIER"),
                SecurityRule.RuleStrategy.UI_FRAGMENT, "liste.jsp (<sec:authorize>)");
        EndpointInfo endpoint = EndpointInfo.builder()
                .httpVerb(HttpVerb.GET)
                .pathTemplate("/api/public")
                .controllerClass("com.example.PublicController")
                .methodName("getPublic")
                .framework("Spring")
                .build();

        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(endpoint), List.of(), List.of(fragmentRule));

        assertTrue(matrix.hasSecurityConfig(), "a UI-fragment rule alone is real security configuration");
        assertTrue(matrix.allIdentifiedRoles().contains("DOSSIER"));
        assertTrue(matrix.strategiesDetected().contains(SecurityRule.RuleStrategy.UI_FRAGMENT));
        assertEquals(List.of(fragmentRule), matrix.uiFragmentRules());
        assertTrue(matrix.unprotectedEndpoints().contains("GET /api/public"),
                "a UI-fragment rule gates a page fragment, not this unrelated HTTP endpoint");
    }

    @Test
    void noUiFragmentRules_twoArgOverload_stillWorks() {
        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(), List.of());

        assertTrue(matrix.uiFragmentRules().isEmpty());
    }

    @Test
    void testBuildWithMethodAnnotations() {
        EndpointInfo endpoint = EndpointInfo.builder()
                .httpVerb(HttpVerb.DELETE)
                .pathTemplate("/api/users/{id}")
                .controllerClass("com.example.UserController")
                .methodName("deleteUser")
                .framework("Spring")
                .addSecurityAnnotation("Secured", "@Secured(\"ADMIN\")")
                .build();

        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(endpoint), List.of());

        assertTrue(matrix.hasSecurityConfig());
        assertFalse(matrix.unprotectedEndpoints().contains("DELETE /api/users/{id}"));
        assertTrue(matrix.strategiesDetected().contains(SecurityRule.RuleStrategy.SECURED));
    }

    @Test
    void testBuildWithClassLevelAnnotation_reportedAsProtectedWithClassSource() {
        EndpointInfo endpoint = EndpointInfo.builder()
                .httpVerb(HttpVerb.GET)
                .pathTemplate("/acheteur")
                .controllerClass("com.example.AcheteurController")
                .methodName("getAll")
                .framework("Spring")
                .addSecurityAnnotation("PreAuthorize", "hasPermission('', 'ACHAT')",
                        EndpointInfo.AnnotationSource.CLASS)
                .build();

        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(endpoint), List.of());

        assertFalse(matrix.unprotectedEndpoints().contains("GET /acheteur"));
        SecurityRule rule = matrix.endpointToRule().get("GET /acheteur");
        assertNotNull(rule);
        assertTrue(rule.source().contains("class-level"));
        assertTrue(rule.requiredRoles().contains("ACHAT"));
    }

    @Test
    void testBuildWithHasPermission_extractsPermissionAsRole() {
        EndpointInfo endpoint = EndpointInfo.builder()
                .httpVerb(HttpVerb.PUT)
                .pathTemplate("/acheteur/enregistrer")
                .controllerClass("com.example.AcheteurController")
                .methodName("enregistrer")
                .framework("Spring")
                .addSecurityAnnotation("PreAuthorize", "hasPermission('', 'ADMINISTRATION')")
                .build();

        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(endpoint), List.of());

        assertTrue(matrix.allIdentifiedRoles().contains("ADMINISTRATION"));
        SecurityRule rule = matrix.endpointToRule().get("PUT /acheteur/enregistrer");
        assertFalse(rule.isPublic());
    }

    @Test
    void testBuildWithCompositePermission_decomposedIntoConstituents() {
        EndpointInfo endpoint = EndpointInfo.builder()
                .httpVerb(HttpVerb.GET)
                .pathTemplate("/domaine")
                .controllerClass("com.example.DomaineController")
                .methodName("list")
                .framework("Spring")
                .addSecurityAnnotation("PreAuthorize", "hasPermission('', 'ACHAT|ADMINISTRATION')")
                .build();

        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(endpoint), List.of());

        assertTrue(matrix.allIdentifiedRoles().containsAll(Set.of("ACHAT", "ADMINISTRATION")));
    }

    @Test
    void testBuildWithUnparseablePreAuthorize_neverRenderedAsPublic() {
        EndpointInfo endpoint = EndpointInfo.builder()
                .httpVerb(HttpVerb.DELETE)
                .pathTemplate("/resource/{id}")
                .controllerClass("com.example.ResourceController")
                .methodName("delete")
                .framework("Spring")
                .addSecurityAnnotation("PreAuthorize", "@customEvaluator.check(#id, authentication)")
                .build();

        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(endpoint), List.of());

        SecurityRule rule = matrix.endpointToRule().get("DELETE /resource/{id}");
        assertNotNull(rule, "An endpoint carrying a security annotation must never fall into the unprotected list");
        assertFalse(rule.isPublic());
        assertTrue(rule.hasUnparsedExpression());
        assertFalse(matrix.unprotectedEndpoints().contains("DELETE /resource/{id}"));
    }

    @Test
    void testBuildWithUrlPatterns() {
        SecurityRule urlRule = new SecurityRule(
                "/api/admin/**",
                "POST",
                Set.of("ADMIN"),
                SecurityRule.RuleStrategy.REQUEST_MATCHER,
                "SecurityFilterChain"
        );

        EndpointInfo endpoint = EndpointInfo.builder()
                .httpVerb(HttpVerb.POST)
                .pathTemplate("/api/admin/users")
                .controllerClass("com.example.AdminController")
                .methodName("createAdmin")
                .framework("Spring")
                .build();

        SecurityMatrix matrix = SecurityMatrixBuilder.build(List.of(endpoint), List.of(urlRule));

        assertTrue(matrix.hasSecurityConfig());
        assertTrue(matrix.allIdentifiedRoles().contains("ADMIN"));
    }
}
