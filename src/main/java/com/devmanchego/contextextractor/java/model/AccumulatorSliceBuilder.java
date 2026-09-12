package com.devmanchego.contextextractor.java.model;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.*;

import java.util.*;

/**
 * Phase 3b: builds a verbatim source-code citation for a path-dependent accumulator,
 * instead of attempting a symbolic summary.
 *
 * <p>Design rationale: once a local is mutated across branches (e.g. {@code baseBonus *= 1.5}
 * inside an {@code if}), there is no single formula — any symbolic summary is an approximation
 * that risks being wrong. An LLM reads the original few lines of Java better than a bespoke
 * summary DSL, so this builder collects and orders the statements that define or mutate the
 * variable, together with their immediate single-statement {@code if} guard when present, and
 * cites them verbatim.
 */
final class AccumulatorSliceBuilder {

    private static final int MAX_STATEMENTS = 10;

    private AccumulatorSliceBuilder() {}

    record Slice(List<String> lines, List<String> inputs, boolean inLoop, boolean truncated) {
        boolean isEmpty() { return lines.isEmpty(); }
    }

    /**
     * @param method  the enclosing method
     * @param varName the multi-assignment local to trace
     * @param terminal the write-site statement that consumes the variable (cited last)
     */
    static Slice build(MethodDeclaration method, String varName, Statement terminal) {
        CalculationExpressionClassifier classifier = new CalculationExpressionClassifier();

        List<Statement> defs = collectDefiningStatements(method, varName);
        if (defs.isEmpty()) {
            return new Slice(List.of(), List.of(), false, false);
        }

        LinkedHashSet<Statement> ordered = new LinkedHashSet<>(defs);
        if (terminal != null) {
            ordered.add(terminal);
        }

        boolean inLoop = ordered.stream().anyMatch(AccumulatorSliceBuilder::isInsideLoop);

        List<String> lines = new ArrayList<>();
        Set<String> inputs = new LinkedHashSet<>();
        boolean truncated = ordered.size() > MAX_STATEMENTS;

        int i = 0;
        for (Statement stmt : ordered) {
            if (i++ >= MAX_STATEMENTS) break;
            lines.add(citeWithGuard(stmt, inputs, classifier));
            collectRhsInputs(stmt, varName, inputs, classifier);
        }
        if (truncated) {
            lines.add("// … (+" + (ordered.size() - MAX_STATEMENTS) + " more)");
        }

        return new Slice(lines, new ArrayList<>(inputs), inLoop, truncated);
    }

    // ------------------------------------------------------------------

    private static List<Statement> collectDefiningStatements(MethodDeclaration method, String varName) {
        List<Statement> result = new ArrayList<>();

        for (ExpressionStmt es : method.findAll(ExpressionStmt.class)) {
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                boolean matches = vde.getVariables().stream()
                        .anyMatch(v -> v.getNameAsString().equals(varName) && v.getInitializer().isPresent());
                if (matches) result.add(es);
            } else if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr n
                    && n.getNameAsString().equals(varName)) {
                result.add(es);
            }
        }
        result.sort(Comparator.comparingInt(s -> s.getRange().map(r -> r.begin.line).orElse(0)));
        return result;
    }

    /**
     * Cites a statement verbatim; if it is a direct (optionally block-wrapped) single-purpose
     * then-branch of an {@code if} with no other siblings, prefixes the guard condition so the
     * branch context is not lost. Also records the guard condition's input fields.
     */
    private static String citeWithGuard(Statement stmt, Set<String> inputs,
                                        CalculationExpressionClassifier classifier) {
        Optional<IfStmt> guard = immediateIfGuard(stmt);
        if (guard.isPresent()) {
            // A guard condition variable is relevant context regardless of which method is
            // called on it (e.g. `years` in `years.compareTo(THRESHOLD) > 0`) — unlike RHS
            // input collection, don't exclude comparison receivers here.
            inputs.addAll(collectAllNames(guard.get().getCondition()));
            return "if (" + guard.get().getCondition() + ") " + stmt.toString();
        }
        return stmt.toString();
    }

    private static Optional<IfStmt> immediateIfGuard(Statement stmt) {
        Node parent = stmt.getParentNode().orElse(null);
        if (parent instanceof IfStmt ifs && ifs.getThenStmt() == stmt) {
            return Optional.of(ifs);
        }
        if (parent instanceof BlockStmt block && block.getStatements().size() == 1) {
            Node blockParent = block.getParentNode().orElse(null);
            if (blockParent instanceof IfStmt ifs && ifs.getThenStmt() == block) {
                return Optional.of(ifs);
            }
        }
        return Optional.empty();
    }

    private static void collectRhsInputs(Statement stmt, String varName, Set<String> inputs,
                                         CalculationExpressionClassifier classifier) {
        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                vde.getVariables().stream()
                        .filter(v -> v.getNameAsString().equals(varName))
                        .findFirst()
                        .flatMap(v -> v.getInitializer())
                        .ifPresent(init -> inputs.addAll(classifier.collectInputs(init)));
            } else if (es.getExpression() instanceof AssignExpr ae) {
                inputs.addAll(classifier.collectInputs(ae.getValue()));
            }
        }
    }

    private static final Set<String> RESERVED_NAMES = Set.of("this", "super", "true", "false", "null");

    /** All lowercase-leading identifier references in an expression, e.g. a guard condition. */
    private static List<String> collectAllNames(com.github.javaparser.ast.expr.Expression expr) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        expr.findAll(NameExpr.class).forEach(n -> {
            String id = n.getNameAsString();
            if (!id.isEmpty() && Character.isLowerCase(id.charAt(0)) && !RESERVED_NAMES.contains(id)) {
                names.add(id);
            }
        });
        return new ArrayList<>(names);
    }

    private static boolean isInsideLoop(Statement stmt) {
        return stmt.findAncestor(ForStmt.class).isPresent()
                || stmt.findAncestor(ForEachStmt.class).isPresent()
                || stmt.findAncestor(WhileStmt.class).isPresent()
                || stmt.findAncestor(DoStmt.class).isPresent();
    }
}
