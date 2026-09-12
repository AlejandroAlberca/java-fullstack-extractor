package com.devmanchego.contextextractor.java.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Phase 2b: Tier 2 Backend — detects single-expression field calculations at write-sites
 * that live in ordinary method bodies (the service layer), not in annotations.
 *
 * <p>Scans three kinds of write-site and gates each through the
 * {@link CalculationExpressionClassifier} copy-vs-calc discriminator:
 * <ul>
 *   <li><b>Setter calls</b> — {@code target.setField(EXPR)}</li>
 *   <li><b>Assignments</b> — {@code this.field = EXPR} / {@code field = EXPR}
 *       (excluding {@code @PrePersist}/{@code @PreUpdate}, already covered by Phase 2a)</li>
 *   <li><b>Getter returns</b> — {@code getX()} / {@code isX()} bodies
 *       (excluding {@code @Transient}, already covered by Phase 2a)</li>
 * </ul>
 * All evidence is emitted at tier 2 with MEDIUM confidence.
 */
public final class Tier2CalculationDetector {

    private static final Logger log = LoggerFactory.getLogger(Tier2CalculationDetector.class);

    private final CalculationExpressionClassifier classifier = new CalculationExpressionClassifier();

    /**
     * Scans all compilation units for Tier 2 write-site calculations.
     *
     * @param compilationUnits parsed Java source files
     * @return map of field name → list of calculation evidences
     */
    public Map<String, List<CalculationEvidence>> detect(List<CompilationUnit> compilationUnits) {
        Map<String, List<CalculationEvidence>> results = new LinkedHashMap<>();

        for (CompilationUnit cu : compilationUnits) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            String sourceFile = cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown");

            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (isLifecycleMethod(method)) {
                    continue; // @PrePersist / @PreUpdate handled by Phase 2a
                }
                String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ClassOrInterfaceDeclaration::getNameAsString)
                        .orElse("Unknown");
                String fqn = pkg.isEmpty() ? className : pkg + "." + className;

                Tier3CalculationDetector.LocalSymbolTable symbols =
                        Tier3CalculationDetector.LocalSymbolTable.of(method);

                scanSetterCalls(method, fqn, sourceFile, results);
                scanAssignments(method, symbols, fqn, sourceFile, results);
                scanGetterReturn(method, fqn, sourceFile, results);
            }
        }

        log.info("Tier2CalculationDetector: detected {} field(s) with Tier 2 calculation evidence",
                results.size());
        return results;
    }

    // ---------------------------------------------------------------------
    // Setter calls:  target.setField(EXPR)
    // ---------------------------------------------------------------------

    private void scanSetterCalls(MethodDeclaration method, String fqn, String sourceFile,
                                 Map<String, List<CalculationEvidence>> results) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!isSetter(name) || call.getArguments().size() != 1) {
                continue;
            }
            String field = decapitalize(name.substring(3));
            Expression arg = call.getArgument(0);

            CalculationExpressionClassifier.Result result = classifier.classify(arg);
            if (!result.isCalculation()) {
                continue; // copy / constant — correctly ignored
            }

            addEvidence(results, field, CalculationEvidence.builder()
                    .targetField(field)
                    .locus(CalculationEvidence.Locus.BACKEND_SERVICE_SETTER)
                    .expression(cleanExpr(arg))
                    .inputFields(result.inputs())
                    .sourceFile(sourceFile)
                    .sourceClass(fqn)
                    .sourceMethod(method.getNameAsString())
                    .lineNumber(call.getRange().map(r -> r.begin.line).orElse(-1))
                    .confidence(CalculationEvidence.Confidence.MEDIUM)
                    .tier(2)
                    .description("Setter-call calculation (" + result.reason() + ")")
                    .build());
        }
    }

    // ---------------------------------------------------------------------
    // Assignments:  this.field = EXPR  /  field = EXPR
    // ---------------------------------------------------------------------

    private void scanAssignments(MethodDeclaration method, Tier3CalculationDetector.LocalSymbolTable symbols,
                                 String fqn, String sourceFile,
                                 Map<String, List<CalculationEvidence>> results) {
        for (AssignExpr assign : method.findAll(AssignExpr.class)) {
            if (assign.getOperator() != AssignExpr.Operator.ASSIGN) {
                continue;
            }
            // A reassigned local (`total = total.add(bonus)`) is dataflow within the method,
            // not a field write — Tier 3's slice builder is responsible for surfacing it.
            if (assign.getTarget() instanceof NameExpr n && symbols.isLocal(n.getNameAsString())) {
                continue;
            }
            String field = assignmentTargetField(assign.getTarget());
            if (field == null) {
                continue;
            }
            Expression value = assign.getValue();

            CalculationExpressionClassifier.Result result = classifier.classify(value);
            if (!result.isCalculation()) {
                continue;
            }

            addEvidence(results, field, CalculationEvidence.builder()
                    .targetField(field)
                    .locus(CalculationEvidence.Locus.BACKEND_SERVICE_ASSIGNMENT)
                    .expression(cleanExpr(value))
                    .inputFields(result.inputs())
                    .sourceFile(sourceFile)
                    .sourceClass(fqn)
                    .sourceMethod(method.getNameAsString())
                    .lineNumber(assign.getRange().map(r -> r.begin.line).orElse(-1))
                    .confidence(CalculationEvidence.Confidence.MEDIUM)
                    .tier(2)
                    .description("Assignment calculation (" + result.reason() + ")")
                    .build());
        }
    }

    // ---------------------------------------------------------------------
    // Getter returns:  getX() { return EXPR; }  (non-@Transient)
    // ---------------------------------------------------------------------

    private void scanGetterReturn(MethodDeclaration method, String fqn, String sourceFile,
                                  Map<String, List<CalculationEvidence>> results) {
        String name = method.getNameAsString();
        if (!isGetter(name)) {
            return;
        }
        if (method.getAnnotationByName("Transient").isPresent()) {
            return; // Phase 2a already handles @Transient getters
        }
        if (method.getBody().isEmpty()) {
            return;
        }

        List<ReturnStmt> returns = method.findAll(ReturnStmt.class);
        if (returns.size() != 1) {
            return; // multi-return → not a single-expression getter (defer to Tier 3)
        }
        Optional<Expression> returned = returns.get(0).getExpression();
        if (returned.isEmpty()) {
            return;
        }

        String field = accessorField(name);
        CalculationExpressionClassifier.Result result = classifier.classify(returned.get());
        if (!result.isCalculation()) {
            return;
        }

        addEvidence(results, field, CalculationEvidence.builder()
                .targetField(field)
                .locus(CalculationEvidence.Locus.BACKEND_GETTER_COMPUTED)
                .expression(cleanExpr(returned.get()))
                .inputFields(result.inputs())
                .sourceFile(sourceFile)
                .sourceClass(fqn)
                .sourceMethod(name)
                .lineNumber(method.getRange().map(r -> r.begin.line).orElse(-1))
                .confidence(CalculationEvidence.Confidence.MEDIUM)
                .tier(2)
                .description("Computed getter (" + result.reason() + ")")
                .build());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean isLifecycleMethod(MethodDeclaration method) {
        return method.getAnnotationByName("PrePersist").isPresent()
                || method.getAnnotationByName("PreUpdate").isPresent();
    }

    private boolean isSetter(String name) {
        return name.length() > 3 && name.startsWith("set") && Character.isUpperCase(name.charAt(3));
    }

    private boolean isGetter(String name) {
        return (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3)))
                || (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2)));
    }

    private String accessorField(String name) {
        if (name.startsWith("get")) {
            return decapitalize(name.substring(3));
        }
        return decapitalize(name.substring(2)); // "is"
    }

    private String assignmentTargetField(Expression target) {
        if (target instanceof FieldAccessExpr fieldAccess) {
            return fieldAccess.getNameAsString(); // this.field
        }
        if (target instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();     // field
        }
        return null;
    }

    private void addEvidence(Map<String, List<CalculationEvidence>> results,
                             String field, CalculationEvidence evidence) {
        results.computeIfAbsent(field, k -> new ArrayList<>()).add(evidence);
    }

    private String decapitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Serializes an expression to source text with attached/contained comments removed. */
    private String cleanExpr(Expression expr) {
        Expression clone = expr.clone();
        clone.getAllContainedComments().forEach(com.github.javaparser.ast.comments.Comment::remove);
        clone.removeComment();
        return clone.toString().replaceAll("\\s+", " ").trim();
    }
}
