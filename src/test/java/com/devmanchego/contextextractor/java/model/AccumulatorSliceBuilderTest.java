package com.devmanchego.contextextractor.java.model;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccumulatorSliceBuilderTest {

    private MethodDeclaration parseMethod(String src) {
        return StaticJavaParser.parseBodyDeclaration(src).asMethodDeclaration();
    }

    @Test
    void collectsDeclarationAndReassignmentInOrder() {
        MethodDeclaration m = parseMethod("""
                void run(java.math.BigDecimal base) {
                  java.math.BigDecimal acc = base.multiply(RATE);
                  acc = acc.add(base);
                  p.setTotal(acc);
                }
                """);
        Statement terminal = m.findAll(ExpressionStmt.class).get(2);

        var slice = AccumulatorSliceBuilder.build(m, "acc", terminal);
        assertFalse(slice.isEmpty());
        assertEquals(3, slice.lines().size()); // decl, reassign, terminal
        assertTrue(slice.lines().get(0).contains("acc = base.multiply(RATE)"));
        assertTrue(slice.lines().get(1).contains("acc = acc.add(base)"));
        assertTrue(slice.lines().get(2).contains("setTotal(acc)"));
        assertFalse(slice.inLoop());
    }

    @Test
    void singleStatementIfGuardIsCitedWithCondition() {
        MethodDeclaration m = parseMethod("""
                void run(java.math.BigDecimal salary, java.math.BigDecimal years) {
                  java.math.BigDecimal bonus = salary.multiply(RATE);
                  if (years.compareTo(THRESHOLD) > 0) bonus = bonus.multiply(BOOST);
                  p.setBonus(bonus);
                }
                """);
        Statement terminal = m.findAll(ExpressionStmt.class).get(2);

        var slice = AccumulatorSliceBuilder.build(m, "bonus", terminal);
        String guardedLine = slice.lines().get(1);
        assertTrue(guardedLine.startsWith("if (years.compareTo(THRESHOLD) > 0)"), guardedLine);
        assertTrue(guardedLine.contains("bonus = bonus.multiply(BOOST)"), guardedLine);
    }

    @Test
    void bracedIfGuardIsCitedWithCondition() {
        MethodDeclaration m = parseMethod("""
                void run(java.math.BigDecimal salary) {
                  java.math.BigDecimal bonus = salary.multiply(RATE);
                  if (salary.compareTo(LIMIT) > 0) {
                    bonus = bonus.add(EXTRA);
                  }
                  p.setBonus(bonus);
                }
                """);
        Statement terminal = m.findAll(ExpressionStmt.class).get(2);

        var slice = AccumulatorSliceBuilder.build(m, "bonus", terminal);
        String guardedLine = slice.lines().get(1);
        assertTrue(guardedLine.startsWith("if (salary.compareTo(LIMIT) > 0)"), guardedLine);
    }

    @Test
    void loopMutationIsFlagged() {
        MethodDeclaration m = parseMethod("""
                void run(java.util.List<java.math.BigDecimal> items) {
                  java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                  for (java.math.BigDecimal item : items) {
                    total = total.add(item);
                  }
                  p.setSum(total);
                }
                """);
        Statement terminal = m.findAll(ExpressionStmt.class).get(2);

        var slice = AccumulatorSliceBuilder.build(m, "total", terminal);
        assertTrue(slice.inLoop());
    }

    @Test
    void nonExistentVariableYieldsEmptySlice() {
        MethodDeclaration m = parseMethod("""
                void run() {
                  p.setX(1);
                }
                """);
        var slice = AccumulatorSliceBuilder.build(m, "nope", null);
        assertTrue(slice.isEmpty());
    }

    @Test
    void inputsCollectFromRhsAndGuardCondition() {
        MethodDeclaration m = parseMethod("""
                void run(java.math.BigDecimal salary, java.math.BigDecimal years) {
                  java.math.BigDecimal bonus = salary.multiply(RATE);
                  if (years.compareTo(THRESHOLD) > 0) bonus = bonus.multiply(BOOST);
                  p.setBonus(bonus);
                }
                """);
        Statement terminal = m.findAll(ExpressionStmt.class).get(2);

        var slice = AccumulatorSliceBuilder.build(m, "bonus", terminal);
        List<String> inputs = slice.inputs();
        assertTrue(inputs.contains("salary"));
        assertTrue(inputs.contains("years"));
    }
}
