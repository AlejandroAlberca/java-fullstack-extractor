package com.devmanchego.contextextractor.angular.model;

import com.devmanchego.contextextractor.java.model.CalculationEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Phase 2c frontend value-computation discriminator, plus Phase 3
 * multi-statement method/getter body citation (mirrors backend Phase 3b).
 */
class FrontendCalculationDetectorTest {

    private final FrontendCalculationDetector detector = new FrontendCalculationDetector();

    @TempDir
    Path tempDir;

    private Map<String, List<CalculationEvidence>> detectInSource(String source) throws IOException {
        Path ts = tempDir.resolve("test.component.ts");
        Files.writeString(ts, source);
        return detector.detectInComponent(ts.toString());
    }

    @Test
    void arithmeticIsValueCalculation() {
        assertTrue(detector.isValueCalculation("this.salary() * 12"));
        assertTrue(detector.isValueCalculation("this.a - this.b"));
    }

    @Test
    void templateLiteralConcatIsValueCalculation() {
        assertTrue(detector.isValueCalculation("`${this.employee?.firstName} ${this.employee?.lastName}`"));
    }

    @Test
    void ternaryIsValueCalculation() {
        assertTrue(detector.isValueCalculation("this.active ? 'Active' : 'Inactive'"));
    }

    @Test
    void twoDistinctFieldsIsValueCalculation() {
        assertTrue(detector.isValueCalculation("this.base + this.bonus"));
    }

    @Test
    void plainOptionalChainingPassthroughIsNotCalculation() {
        // `?.` must NOT be mistaken for a ternary operator.
        assertFalse(detector.isValueCalculation("this.employee?.name"));
    }

    @Test
    void singleFieldPassthroughIsNotCalculation() {
        assertFalse(detector.isValueCalculation("this.employee"));
    }

    // ---- Phase 3: regular methods (invisible before this phase) ----

    @Test
    void singleReturnRegularMethodIsFormula() throws IOException {
        String src = """
                class C {
                  calculateNetSalaryAfterTax(grossSalary: number, taxRate: number = 0.21): number {
                    return grossSalary * (1 - taxRate);
                  }
                }
                """;
        var result = detectInSource(src);
        assertTrue(result.containsKey("calculateNetSalaryAfterTax"));
        CalculationEvidence ev = result.get("calculateNetSalaryAfterTax").get(0);
        assertEquals(CalculationEvidence.Kind.FORMULA, ev.getKind());
        assertEquals(2, ev.getTier());
        assertEquals("grossSalary * (1 - taxRate)", ev.getExpression());
    }

    @Test
    void multiStatementRegularMethodIsSlice() throws IOException {
        String src = """
                class C {
                  calculateBenefitsPackage(salary: number, yearsOfService: number): number {
                    let baseBonus = salary * 0.05;
                    if (yearsOfService > 5) baseBonus *= 1.5;
                    if (salary > 60000) baseBonus += 2000;
                    const insurance = 1200;
                    const pension = salary * 0.04;
                    return baseBonus + insurance + pension;
                  }
                }
                """;
        var result = detectInSource(src);
        assertTrue(result.containsKey("calculateBenefitsPackage"));
        CalculationEvidence ev = result.get("calculateBenefitsPackage").get(0);
        assertEquals(CalculationEvidence.Kind.SLICE, ev.getKind());
        assertEquals(3, ev.getTier());
        assertEquals("ts", ev.getLanguage());
        assertTrue(ev.getExpression().contains("baseBonus *= 1.5"), ev.getExpression());
    }

    @Test
    void multiStatementGetterIsSlice() throws IOException {
        String src = """
                class C {
                  get employmentLevel(): string {
                    if (!this.employee.active) return 'TERMINATED';
                    if (this.years > 10 && this.salary > 70000) return 'EXECUTIVE';
                    return 'JUNIOR';
                  }
                }
                """;
        var result = detectInSource(src);
        assertTrue(result.containsKey("employmentLevel"));
        CalculationEvidence ev = result.get("employmentLevel").get(0);
        assertEquals(CalculationEvidence.Kind.SLICE, ev.getKind());
        assertEquals(CalculationEvidence.Locus.FRONTEND_METHOD_SLICE, ev.getLocus());
    }

    @Test
    void singleReturnGetterIsNotDuplicatedAsSlice() throws IOException {
        // Already handled by detectComponentGetters (Phase 2c) as a FORMULA — the
        // multi-statement pass must not also emit a redundant SLICE for it.
        String src = """
                class C {
                  get fullName(): string {
                    return this.firstName + ' ' + this.lastName;
                  }
                }
                """;
        var result = detectInSource(src);
        assertTrue(result.containsKey("fullName"));
        assertEquals(1, result.get("fullName").size());
        assertEquals(CalculationEvidence.Kind.FORMULA, result.get("fullName").get(0).getKind());
    }

    @Test
    void voidMethodIsNeverTreatedAsAFieldCalculation() throws IOException {
        // A `void` method returns nothing, so it can never BE a field's value — even if it
        // references multiple `this.x` properties while wiring up a form/config object.
        String src = """
                class C {
                  private buildForm(): void {
                    this.form = this.fb.group({
                      firstName: ['', [Validators.required, Validators.minLength(2)]],
                      salary: ['', [Validators.required, CustomValidators.positiveNumber()]]
                    });
                  }
                }
                """;
        var result = detectInSource(src);
        assertFalse(result.containsKey("buildForm"));
    }

    @Test
    void controlFlowKeywordsAreNotTreatedAsMethods() throws IOException {
        String src = """
                class C {
                  run(): void {
                    if (this.a > this.b) {
                      for (let i = 0; i < 10; i++) {}
                    }
                  }
                }
                """;
        var result = detectInSource(src);
        assertFalse(result.containsKey("if"));
        assertFalse(result.containsKey("for"));
    }
}
