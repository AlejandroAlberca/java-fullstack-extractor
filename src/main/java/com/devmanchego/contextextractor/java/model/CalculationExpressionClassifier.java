package com.devmanchego.contextextractor.java.model;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The copy-vs-calc discriminator (Phase 2b core).
 *
 * <p>Given a right-hand-side {@link Expression} (a setter argument, an assignment value,
 * or a getter return), decides whether it represents a genuine <em>calculation</em> or a
 * plain <em>copy</em>. Without this gate every field write would look computed.
 *
 * <p>An expression is a calculation when it contains any of:
 * <ul>
 *   <li>an arithmetic / relational / logical {@link BinaryExpr} (including string concatenation),</li>
 *   <li>a ternary {@link ConditionalExpr},</li>
 *   <li>a known arithmetic/aggregation method call ({@code add, multiply, divide, subtract,
 *       size, count, sum, …}),</li>
 *   <li>or references to {@code >= 2} distinct source fields.</li>
 * </ul>
 * A single getter, a single name, or a literal is a copy.
 */
public final class CalculationExpressionClassifier {

    /** BigDecimal/BigInteger arithmetic and common numeric method names. */
    private static final Set<String> ARITHMETIC_METHODS = Set.of(
            "add", "subtract", "multiply", "divide", "remainder", "negate", "abs",
            "pow", "mod", "min", "max", "sqrt", "scaleByPowerOfTen"
    );

    /** Aggregation / reduction method names. */
    private static final Set<String> AGGREGATION_METHODS = Set.of(
            "size", "count", "sum", "average", "length", "reduce"
    );

    /**
     * Result of classifying an expression.
     */
    public record Result(boolean isCalculation, List<String> inputs, String reason) {}

    /**
     * Classifies an expression as calculation or copy, collecting input field names.
     */
    public Result classify(Expression expr) {
        List<String> inputs = collectInputs(expr);

        // 1. Binary operator (arithmetic, relational, logical, string concat)
        boolean hasBinary = expr.findAll(BinaryExpr.class).stream()
                .anyMatch(this::isCalcOperator);
        if (hasBinary) {
            return new Result(true, inputs, "binary-operator");
        }

        // 2. Ternary
        if (!expr.findAll(ConditionalExpr.class).isEmpty()) {
            return new Result(true, inputs, "ternary");
        }

        // 3. Known arithmetic / aggregation method call
        boolean hasCalcMethod = expr.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .anyMatch(n -> ARITHMETIC_METHODS.contains(n) || AGGREGATION_METHODS.contains(n));
        if (hasCalcMethod) {
            return new Result(true, inputs, "arithmetic-method");
        }

        // 4. References to >= 2 distinct source fields
        if (inputs.size() >= 2) {
            return new Result(true, inputs, "multi-field");
        }

        // Otherwise: copy
        return new Result(false, inputs, "copy");
    }

    private boolean isCalcOperator(BinaryExpr bin) {
        return switch (bin.getOperator()) {
            case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER,
                 AND, OR, XOR, BINARY_AND, BINARY_OR,
                 EQUALS, NOT_EQUALS, GREATER, GREATER_EQUALS, LESS, LESS_EQUALS,
                 LEFT_SHIFT, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> true;
        };
    }

    /**
     * Collects distinct input field names from an expression: getter/is-accessor targets
     * plus bare operand names. Receivers of accessor / delegation calls (e.g. {@code employee}
     * in {@code employee.getId()}) are excluded — they are object holders, not field values —
     * while receivers of arithmetic calls (e.g. {@code gross} in {@code gross.multiply(x)})
     * are kept as operands.
     */
    public List<String> collectInputs(Expression expr) {
        Set<String> inputs = new LinkedHashSet<>();

        // Names that are the receiver of a NON-arithmetic call → exclude (holders, not values).
        Set<String> nonOperandReceivers = new LinkedHashSet<>();
        for (MethodCallExpr call : expr.findAll(MethodCallExpr.class)) {
            boolean arithmetic = ARITHMETIC_METHODS.contains(call.getNameAsString())
                    || AGGREGATION_METHODS.contains(call.getNameAsString());
            if (!arithmetic) {
                call.getScope()
                        .filter(NameExpr.class::isInstance)
                        .map(s -> ((NameExpr) s).getNameAsString())
                        .ifPresent(nonOperandReceivers::add);
            }
        }

        // Getter-style method calls: getSalary() / isActive() -> salary / active
        for (MethodCallExpr call : expr.findAll(MethodCallExpr.class)) {
            String field = accessorToField(call.getNameAsString());
            if (field != null) {
                inputs.add(field);
            }
        }

        // Bare name references that look like fields (start lowercase, not a constant/receiver)
        for (NameExpr name : expr.findAll(NameExpr.class)) {
            String id = name.getNameAsString();
            if (looksLikeField(id) && !nonOperandReceivers.contains(id)) {
                inputs.add(id);
            }
        }

        return new ArrayList<>(inputs);
    }

    private String accessorToField(String methodName) {
        if (methodName.length() > 3 && methodName.startsWith("get")
                && Character.isUpperCase(methodName.charAt(3))) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.length() > 2 && methodName.startsWith("is")
                && Character.isUpperCase(methodName.charAt(2))) {
            return decapitalize(methodName.substring(2));
        }
        return null;
    }

    private boolean looksLikeField(String id) {
        // Exclude ALL_CAPS constants and Type-like PascalCase references.
        if (id.isEmpty()) return false;
        if (!Character.isLowerCase(id.charAt(0))) return false;
        // Exclude common non-field identifiers.
        return !RESERVED.contains(id);
    }

    private static final Set<String> RESERVED = Set.of(
            "this", "super", "value", "result", "it", "e", "ex"
    );

    private String decapitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
