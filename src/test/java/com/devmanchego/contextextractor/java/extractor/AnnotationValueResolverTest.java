package com.devmanchego.contextextractor.java.extractor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationValueResolverTest {

    private static final Map<String, String> CONSTANTS = Map.of(
            "WebConstants.API_V1_ROOT_URL", "/api/v1",
            "API_V1_ROOT_URL",              "/api/v1",
            "BASE",                         "/api"
    );

    @Test
    void simpleLiteral_returned_unquoted() {
        assertEquals("/api/users", AnnotationValueResolver.resolve("\"/api/users\"", CONSTANTS));
    }

    @Test
    void constantQualified_resolved() {
        assertEquals("/api/v1", AnnotationValueResolver.resolve("WebConstants.API_V1_ROOT_URL", CONSTANTS));
    }

    @Test
    void constantUnqualified_resolved() {
        assertEquals("/api/v1", AnnotationValueResolver.resolve("API_V1_ROOT_URL", CONSTANTS));
    }

    @Test
    void concatenation_constantPlusLiteral_resolved() {
        // WebConstants.API_V1_ROOT_URL + "/users/internal"  →  /api/v1/users/internal
        String result = AnnotationValueResolver.resolve(
                "WebConstants.API_V1_ROOT_URL + \"/users/internal\"", CONSTANTS);
        assertEquals("/api/v1/users/internal", result);
    }

    @Test
    void concatenation_multipleParts_resolved() {
        String result = AnnotationValueResolver.resolve(
                "BASE + \"/users\" + \"/detail\"", CONSTANTS);
        assertEquals("/api/users/detail", result);
    }

    @Test
    void unresolvableConstant_emittedAsBracketedPlaceholder() {
        String result = AnnotationValueResolver.resolve("UnknownClass.MISSING_CONST", Map.of());
        // Should contain the reference so it's visible in output
        assertTrue(result.contains("MISSING_CONST") || result.startsWith("{"),
                "Unresolvable constant should produce a visible placeholder, got: " + result);
    }

    @Test
    void splitByPlus_respectsStringBoundaries() {
        // "a+b" + c   →  two parts: ["\"a+b\"", "c"]
        List<String> parts = AnnotationValueResolver.splitByPlus("\"a+b\" + c");
        assertEquals(2, parts.size());
        assertEquals("\"a+b\"", parts.get(0));
        assertEquals("c", parts.get(1));
    }

    @Test
    void extractStringLiteral_stripsQuotes() {
        assertEquals("hello", AnnotationValueResolver.extractStringLiteral("\"hello\""));
    }

    @Test
    void extractStringLiteral_nonLiteral_returnsNull() {
        assertNull(AnnotationValueResolver.extractStringLiteral("SomeClass.CONST"));
    }

    @Test
    void extractStringLiteral_concatenationExpr_returnsNull() {
        // "/api/" + ApiVersionConstants.API_VERSION_V5 + "/path"
        // starts AND ends with '"' but is NOT a simple literal — must return null
        assertNull(AnnotationValueResolver.extractStringLiteral(
                "\"/api/\" + ApiVersionConstants.API_VERSION_V5 + \"/path\""));
    }

    @Test
    void concatenation_literalConstantLiteral_resolved() {
        // "/api/" + ApiVersionConstants.API_VERSION_V5 + "/management/forecast-data"
        // where API_VERSION_V5 = "v5"  →  /api/v5/management/forecast-data
        Map<String, String> constants = Map.of(
                "ApiVersionConstants.API_VERSION_V5", "v5",
                "API_VERSION_V5", "v5");
        String result = AnnotationValueResolver.resolve(
                "\"/api/\" + ApiVersionConstants.API_VERSION_V5 + \"/management/forecast-data\"",
                constants);
        assertEquals("/api/v5/management/forecast-data", result);
    }
}
