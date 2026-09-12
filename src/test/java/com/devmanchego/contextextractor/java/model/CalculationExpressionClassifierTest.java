package com.devmanchego.contextextractor.java.model;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Phase 2b copy-vs-calc discriminator.
 */
class CalculationExpressionClassifierTest {

    private final CalculationExpressionClassifier classifier = new CalculationExpressionClassifier();

    private CalculationExpressionClassifier.Result classify(String expr) {
        Expression e = StaticJavaParser.parseExpression(expr);
        return classifier.classify(e);
    }

    // ---- Positives: genuine calculations ----

    @Test
    void arithmeticBinaryIsCalculation() {
        var r = classify("salary * 12");
        assertTrue(r.isCalculation());
        assertEquals("binary-operator", r.reason());
    }

    @Test
    void bigDecimalMultiplyIsCalculation() {
        var r = classify("gross.multiply(MONTHS)");
        assertTrue(r.isCalculation());
        assertEquals("arithmetic-method", r.reason());
        assertTrue(r.inputs().contains("gross"));
    }

    @Test
    void stringConcatenationIsCalculation() {
        var r = classify("employee.getFirstName() + \" \" + employee.getLastName()");
        assertTrue(r.isCalculation());
        assertTrue(r.inputs().contains("firstName"));
        assertTrue(r.inputs().contains("lastName"));
        // The receiver "employee" must NOT be counted as an input field.
        assertFalse(r.inputs().contains("employee"));
    }

    @Test
    void ternaryIsCalculation() {
        // The condition contains a '>' so binary-operator fires first; either reason is fine.
        var r = classify("salary > 50000 ? \"Senior\" : \"Junior\"");
        assertTrue(r.isCalculation());
    }

    @Test
    void pureTernaryWithoutBinaryIsCalculation() {
        var r = classify("active ? \"YES\" : \"NO\"");
        assertTrue(r.isCalculation());
        assertEquals("ternary", r.reason());
    }

    @Test
    void twoDistinctGettersIsCalculation() {
        var r = classify("summary.getAnnualSalary().add(summary.getBonusAmount())");
        assertTrue(r.isCalculation());
        assertTrue(r.inputs().contains("annualSalary"));
        assertTrue(r.inputs().contains("bonusAmount"));
    }

    // ---- Negatives: copies / constants / delegation ----

    @Test
    void singleGetterIsCopy() {
        var r = classify("employee.getId()");
        assertFalse(r.isCalculation());
        assertEquals("copy", r.reason());
        // Only the accessor field, not the receiver.
        assertEquals(1, r.inputs().size());
        assertTrue(r.inputs().contains("id"));
    }

    @Test
    void plainNameIsCopy() {
        var r = classify("gross");
        assertFalse(r.isCalculation());
    }

    @Test
    void constantLiteralIsCopy() {
        var r = classify("true");
        assertFalse(r.isCalculation());
    }

    @Test
    void delegationCallIsCopy() {
        // employeeMapper.toResponse(fetchEmployee(id)) — neither is arithmetic; receivers excluded.
        var r = classify("employeeMapper.toResponse(fetchEmployee(id))");
        assertFalse(r.isCalculation());
        assertFalse(r.inputs().contains("employeeMapper"));
    }
}
