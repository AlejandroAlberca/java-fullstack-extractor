package com.devmanchego.contextextractor.java.model;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Phase 3a (intermediate-variable inlining) and 3c (helper inlining).
 */
class Tier3CalculationDetectorTest {

    private Map<String, List<CalculationEvidence>> detect(String source) {
        CompilationUnit cu = StaticJavaParser.parse(source);
        cu.setStorage(java.nio.file.Path.of("Test.java"));
        return new Tier3CalculationDetector().detect(List.of(cu));
    }

    @Test
    void reconstructsSingleLevelIntermediateVariable() {
        // Tier 2 sees `net` (a copy); Tier 3 must inline its definition.
        String src = """
                class S {
                  void run(P p, java.math.BigDecimal gross) {
                    java.math.BigDecimal net = gross.subtract(gross.multiply(RATE));
                    p.setNetSalary(net);
                  }
                }
                """;
        var result = detect(src);
        assertTrue(result.containsKey("netSalary"));
        String expr = result.get("netSalary").get(0).getExpression();
        assertTrue(expr.contains("gross.subtract(gross.multiply(RATE))"), expr);
        assertEquals(3, result.get("netSalary").get(0).getTier());
    }

    @Test
    void reconstructsMultiLevelIntermediateVariables() {
        String src = """
                class S {
                  void run(P p, java.math.BigDecimal base) {
                    java.math.BigDecimal monthly = base.divide(MONTHS);
                    java.math.BigDecimal net = monthly.subtract(monthly.multiply(RATE));
                    p.setValue(net);
                  }
                }
                """;
        var result = detect(src);
        assertTrue(result.containsKey("value"));
        String expr = result.get("value").get(0).getExpression();
        // Both `net` and `monthly` fully inlined → no local names remain.
        assertFalse(expr.contains("monthly"), expr);
        assertFalse(expr.contains("net"), expr);
        assertTrue(expr.contains("base.divide(MONTHS)"), expr);
    }

    @Test
    void reconstructsHelperDelegation() {
        // Tier 2 sees grossToNet(base) as a single-input copy; Tier 3c inlines the helper.
        String src = """
                class S {
                  void run(P p, java.math.BigDecimal base) {
                    p.setNet(grossToNet(base));
                  }
                  private java.math.BigDecimal grossToNet(java.math.BigDecimal g) {
                    return g.subtract(g.multiply(RATE));
                  }
                }
                """;
        var result = detect(src);
        assertTrue(result.containsKey("net"));
        String expr = result.get("net").get(0).getExpression();
        assertTrue(expr.contains("base.subtract(base.multiply(RATE))"), expr);
    }

    @Test
    void doesNotReconstructPlainCopy() {
        // setter arg is a direct field getter → nothing to reconstruct.
        String src = """
                class S {
                  void run(P p, E e) {
                    p.setName(e.getName());
                  }
                }
                """;
        assertTrue(detect(src).isEmpty());
    }

    @Test
    void doesNotReconstructDataAccessWithLambda() {
        // Inlining a repository fetch must NOT be treated as a calculation (lambda guard).
        String src = """
                class S {
                  void run(P p, Repo repo, Long id) {
                    Entity dep = repo.findById(id).orElseThrow(() -> new RuntimeException("not found: " + id));
                    p.setDepartment(dep);
                  }
                }
                """;
        assertTrue(detect(src).isEmpty());
    }

    @Test
    void multiAssignmentAccumulatorYieldsSliceNotFormula() {
        // `acc` is assigned twice → not safe to inline as a single formula (3a/3c skip it);
        // Phase 3b must instead cite the accumulating statements verbatim.
        String src = """
                class S {
                  void run(P p, java.math.BigDecimal base) {
                    java.math.BigDecimal acc = base.multiply(RATE);
                    acc = acc.add(base);
                    p.setTotal(acc);
                  }
                }
                """;
        var result = detect(src);
        assertTrue(result.containsKey("total"));
        CalculationEvidence ev = result.get("total").get(0);
        assertEquals(CalculationEvidence.Kind.SLICE, ev.getKind());
        assertEquals(3, ev.getTier());
        assertEquals(CalculationEvidence.Confidence.LOW, ev.getConfidence());
        assertTrue(ev.getExpression().contains("acc = acc.add(base)"), ev.getExpression());
        assertTrue(ev.getInputFields().contains("base"));
    }

    @Test
    void guardedAccumulatorCitesIfConditionInline() {
        // Single-statement `if` guards around a mutation must be cited with their condition,
        // not lost — that branch context is exactly what a symbolic summary would risk getting
        // wrong, so we cite the source instead.
        String src = """
                class S {
                  void run(P p, java.math.BigDecimal salary, java.math.BigDecimal years) {
                    java.math.BigDecimal bonus = salary.multiply(RATE);
                    if (years.compareTo(THRESHOLD) > 0) bonus = bonus.multiply(BOOST);
                    p.setBonus(bonus);
                  }
                }
                """;
        var result = detect(src);
        assertTrue(result.containsKey("bonus"));
        CalculationEvidence ev = result.get("bonus").get(0);
        assertEquals(CalculationEvidence.Kind.SLICE, ev.getKind());
        assertTrue(ev.getExpression().contains("if (years.compareTo(THRESHOLD) > 0)"), ev.getExpression());
    }

    @Test
    void loopAccumulatorIsLabeledAndSliced() {
        String src = """
                class S {
                  void run(P p, java.util.List<java.math.BigDecimal> items) {
                    java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                    for (java.math.BigDecimal item : items) {
                      total = total.add(item);
                    }
                    p.setSum(total);
                  }
                }
                """;
        var result = detect(src);
        assertTrue(result.containsKey("sum"));
        CalculationEvidence ev = result.get("sum").get(0);
        assertEquals(CalculationEvidence.Kind.SLICE, ev.getKind());
        assertTrue(ev.getDescription().toLowerCase().contains("loop"), ev.getDescription());
    }

    @Test
    void sliceIsAdditiveWhenTier2AlreadyFoundAFormula() {
        // Tier 2 already flags `baseBonus.add(insurance)` as a calculation (2 distinct
        // fields) — but `baseBonus` itself is path-dependent, so Tier 3 must ALSO emit a
        // slice explaining it, without suppressing or duplicating the Tier 2 formula.
        String src = """
                class S {
                  void run(P p, java.math.BigDecimal salary, java.math.BigDecimal insurance) {
                    java.math.BigDecimal baseBonus = salary.multiply(RATE);
                    if (salary.compareTo(LIMIT) > 0) baseBonus = baseBonus.add(EXTRA);
                    p.setPackageTotal(baseBonus.add(insurance));
                  }
                }
                """;
        var result = detect(src);
        assertTrue(result.containsKey("packageTotal"));
        List<CalculationEvidence> evidences = result.get("packageTotal");
        assertEquals(1, evidences.size()); // Tier3CalculationDetector only emits the slice;
                                            // the FORMULA half comes from Tier2CalculationDetector.
        assertEquals(CalculationEvidence.Kind.SLICE, evidences.get(0).getKind());
        assertTrue(evidences.get(0).getDescription().contains("not the whole story"));
    }
}
