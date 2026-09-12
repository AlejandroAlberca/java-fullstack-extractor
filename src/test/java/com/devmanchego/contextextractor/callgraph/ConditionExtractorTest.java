package com.devmanchego.contextextractor.callgraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionExtractorTest {

    private JavaParser parser;

    @BeforeEach
    void setUp() {
        parser = new JavaParser();
    }

    // -----------------------------------------------------------------------
    // Helper: parse a class source and return the named method
    // -----------------------------------------------------------------------

    private MethodDeclaration parseMethod(String classSource, String methodName) {
        ClassOrInterfaceDeclaration cls = parser.parse(classSource)
                .getResult()
                .orElseThrow()
                .findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();
        return cls.getMethodsByName(methodName).get(0);
    }

    private ClassOrInterfaceDeclaration parseClass(String classSource) {
        return parser.parse(classSource)
                .getResult()
                .orElseThrow()
                .findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();
    }

    // -----------------------------------------------------------------------
    // D1: null guard
    // -----------------------------------------------------------------------

    @Test
    void d1_nullGuard_noElse_isDiscarded() {
        String src = "class C { void m(Object x) { if (x == null) throw new IllegalArgumentException(\"null\"); } }";
        MethodDeclaration method = parseMethod(src, "m");
        ConditionExtractor extractor = new ConditionExtractor(Map.of());

        List<FlowCondition> conditions = extractor.extract(method, Map.of());

        assertEquals(0, conditions.size());
    }

    // -----------------------------------------------------------------------
    // D2: inside loop
    // -----------------------------------------------------------------------

    @Test
    void d2_ifInsideForLoop_isDiscarded() {
        String src = "class C { void m(java.util.List<String> items) { for (String item : items) { if (item.equals(\"x\")) { return; } } } }";
        MethodDeclaration method = parseMethod(src, "m");
        ConditionExtractor extractor = new ConditionExtractor(Map.of());

        List<FlowCondition> conditions = extractor.extract(method, Map.of());

        assertEquals(0, conditions.size());
    }

    // -----------------------------------------------------------------------
    // D3: instanceof check
    // -----------------------------------------------------------------------

    @Test
    void d3_instanceofCheck_isDiscarded() {
        String src = "class C { void m(Object obj) { if (obj instanceof String) { return; } } }";
        MethodDeclaration method = parseMethod(src, "m");
        ConditionExtractor extractor = new ConditionExtractor(Map.of());

        List<FlowCondition> conditions = extractor.extract(method, Map.of());

        assertEquals(0, conditions.size());
    }

    // -----------------------------------------------------------------------
    // D4: logging/metrics
    // -----------------------------------------------------------------------

    @Test
    void d4_loggingCondition_isDiscarded() {
        String src = "class C { void m() { if (log.isDebugEnabled()) { log.debug(\"msg\"); } } }";
        MethodDeclaration method = parseMethod(src, "m");
        ConditionExtractor extractor = new ConditionExtractor(Map.of());

        List<FlowCondition> conditions = extractor.extract(method, Map.of());

        assertEquals(0, conditions.size());
    }

    // -----------------------------------------------------------------------
    // R1: calls known service
    // -----------------------------------------------------------------------

    @Test
    void r1_callsKnownService_isAccepted() {
        // Build a minimal class index with InventoryService
        String serviceSrc = "class InventoryService { boolean hasStock(Long id) { return true; } }";
        ClassOrInterfaceDeclaration serviceClass = parseClass(serviceSrc);
        Map<String, ClassOrInterfaceDeclaration> classIndex = new HashMap<>();
        classIndex.put("InventoryService", serviceClass);

        String src = "class OrderController { void m() { if (inventoryService.hasStock(id)) { process(); } } }";
        MethodDeclaration method = parseMethod(src, "m");

        Map<String, String> fieldTypes = new HashMap<>();
        fieldTypes.put("inventoryService", "InventoryService");

        ConditionExtractor extractor = new ConditionExtractor(classIndex);
        List<FlowCondition> conditions = extractor.extract(method, fieldTypes);

        assertEquals(1, conditions.size());
        assertTrue(conditions.get(0).getConditionText().contains("inventoryService.hasStock"));
    }

    // -----------------------------------------------------------------------
    // R4: controls return
    // -----------------------------------------------------------------------

    @Test
    void r4_controlsReturn_isAccepted() {
        String src = "class C { String m(User user) { if (user.isActive()) { return active(); } else { return inactive(); } } }";
        MethodDeclaration method = parseMethod(src, "m");
        ConditionExtractor extractor = new ConditionExtractor(Map.of());

        List<FlowCondition> conditions = extractor.extract(method, Map.of());

        assertEquals(1, conditions.size());
        assertEquals("user.isActive()", conditions.get(0).getConditionText());
        assertTrue(conditions.get(0).isHasElse());
    }

    // -----------------------------------------------------------------------
    // R6: throws domain exception
    // -----------------------------------------------------------------------

    @Test
    void r6_throwsDomainException_isAccepted() {
        // Use a domain-specific exception name that does not match any substring in TECHNICAL_EXCEPTIONS
        String src = "class C { void m(boolean valid) { if (!valid) { throw new OutOfStockFault(\"no stock\"); } } }";
        MethodDeclaration method = parseMethod(src, "m");
        ConditionExtractor extractor = new ConditionExtractor(Map.of());

        List<FlowCondition> conditions = extractor.extract(method, Map.of());

        assertEquals(1, conditions.size());
        assertTrue(conditions.get(0).getConditionText().contains("valid"));
    }

    // -----------------------------------------------------------------------
    // Grey territory
    // -----------------------------------------------------------------------

    @Test
    void greyTerritory_simpleAmountCheck_isIgnored() {
        String src = "class C { void m(int amount) { if (amount > 100) { doSomething(); } } }";
        MethodDeclaration method = parseMethod(src, "m");
        ConditionExtractor extractor = new ConditionExtractor(Map.of());

        List<FlowCondition> conditions = extractor.extract(method, Map.of());

        assertEquals(0, conditions.size());
    }
}
