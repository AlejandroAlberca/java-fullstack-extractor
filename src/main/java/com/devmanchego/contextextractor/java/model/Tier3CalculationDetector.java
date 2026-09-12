package com.devmanchego.contextextractor.java.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Phase 3a + 3c: reconstructs field calculations that Tier 2 misses because the write-site
 * argument is a plain local variable (a "copy") or an opaque in-project helper call.
 *
 * <p><b>3a — intermediate variables:</b> a method-local symbol table maps each
 * single-assignment local to its defining expression; the write-site argument is then
 * inlined recursively (multi-level).
 *
 * <p><b>3c — helper delegation:</b> a call to an in-project method with a single
 * {@code return} is replaced by that return expression, with formal parameters substituted
 * by the actual arguments.
 *
 * <p>Emits Tier 3 evidence <em>only</em> when the surface expression is a copy (so Tier 2
 * did not already cover it) and the reconstructed expression is a genuine calculation —
 * this avoids duplicating Tier 2 findings. Inlining is bounded by depth, iteration count and
 * expression size, and never inlines multi-assignment / guarded locals (which would be
 * semantically wrong — those are deferred to a Tier 3b summary).
 */
public final class Tier3CalculationDetector {

    private static final Logger log = LoggerFactory.getLogger(Tier3CalculationDetector.class);

    private static final int MAX_ITERATIONS = 48;
    private static final int MAX_NODES = 400;

    private final CalculationExpressionClassifier classifier = new CalculationExpressionClassifier();

    /** name + "/" + arity → single-return helper method (in-project). */
    private Map<String, MethodDeclaration> helperIndex;

    public Map<String, List<CalculationEvidence>> detect(List<CompilationUnit> compilationUnits) {
        helperIndex = buildHelperIndex(compilationUnits);
        Map<String, List<CalculationEvidence>> results = new LinkedHashMap<>();

        for (CompilationUnit cu : compilationUnits) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            String sourceFile = cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown");

            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (method.getBody().isEmpty()) continue;
                String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ClassOrInterfaceDeclaration::getNameAsString).orElse("Unknown");
                String fqn = pkg.isEmpty() ? className : pkg + "." + className;

                LocalSymbolTable symbols = LocalSymbolTable.of(method);
                scanSetterCalls(method, symbols, fqn, sourceFile, results);
                scanAssignments(method, symbols, fqn, sourceFile, results);
            }
        }

        log.info("Tier3CalculationDetector: reconstructed {} field(s) via inlining", results.size());
        return results;
    }

    // ------------------------------------------------------------------
    // Write-site scanning
    // ------------------------------------------------------------------

    private void scanSetterCalls(MethodDeclaration method, LocalSymbolTable symbols,
                                 String fqn, String sourceFile,
                                 Map<String, List<CalculationEvidence>> results) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!isSetter(name) || call.getArguments().size() != 1) continue;
            String field = decapitalize(name.substring(3));
            tryReconstruct(call.getArgument(0), field, method, symbols, fqn, sourceFile,
                    CalculationEvidence.Locus.BACKEND_SERVICE_SETTER,
                    call.getRange().map(r -> r.begin.line).orElse(-1), results);
        }
    }

    private void scanAssignments(MethodDeclaration method, LocalSymbolTable symbols,
                                 String fqn, String sourceFile,
                                 Map<String, List<CalculationEvidence>> results) {
        for (AssignExpr assign : method.findAll(AssignExpr.class)) {
            if (assign.getOperator() != AssignExpr.Operator.ASSIGN) continue;
            String field = assignmentTargetField(assign.getTarget());
            if (field == null) continue;
            // Skip assignments to a plain local (those are dataflow, not field writes).
            if (assign.getTarget() instanceof NameExpr n && symbols.isLocal(n.getNameAsString())) continue;
            tryReconstruct(assign.getValue(), field, method, symbols, fqn, sourceFile,
                    CalculationEvidence.Locus.BACKEND_SERVICE_ASSIGNMENT,
                    assign.getRange().map(r -> r.begin.line).orElse(-1), results);
        }
    }

    private void tryReconstruct(Expression surface, String field, MethodDeclaration method,
                                LocalSymbolTable symbols, String fqn, String sourceFile,
                                CalculationEvidence.Locus locus, int line,
                                Map<String, List<CalculationEvidence>> results) {
        // Tier 2 already covers the surface expression itself when it's a calculation
        // (e.g. `baseBonus.add(insurance).add(pension)` — a multi-field reference). That does
        // NOT mean the story is complete: `baseBonus` itself may be a path-dependent local.
        // So: only attempt formula reconstruction when the surface is a plain copy (Tier 2
        // missed it); but ALWAYS fall through to the Phase 3b slice check afterwards — it is
        // additive and a no-op unless a multi-assignment local is actually referenced.
        boolean surfaceIsCalc = classifier.classify(surface).isCalculation();

        if (!surfaceIsCalc) {
            Set<String> inlined = new LinkedHashSet<>();
            Expression reconstructed = inline(surface.clone(), symbols, inlined);
            boolean formulaRecovered = reconstructed != null
                    && !reconstructed.toString().equals(surface.toString())
                    // A field's value formula does not contain a lambda — that signals a
                    // data-access/delegation chain (repository.findById(..).orElseThrow(..)),
                    // not a calculation.
                    && reconstructed.findAll(com.github.javaparser.ast.expr.LambdaExpr.class).isEmpty();

            if (formulaRecovered) {
                CalculationExpressionClassifier.Result r = classifier.classify(reconstructed);
                if (r.isCalculation()) {
                    boolean usedHelper = inlined.stream().anyMatch(s -> s.endsWith("()"));
                    String how = usedHelper ? "helper" : "local(s)";
                    results.computeIfAbsent(field, k -> new ArrayList<>()).add(CalculationEvidence.builder()
                            .targetField(field)
                            .locus(locus)
                            .expression(normalize(reconstructed.toString()))
                            .inputFields(r.inputs())
                            .sourceFile(sourceFile)
                            .sourceClass(fqn)
                            .sourceMethod(method.getNameAsString())
                            .lineNumber(line)
                            .confidence(CalculationEvidence.Confidence.MEDIUM)
                            .tier(3)
                            .kind(CalculationEvidence.Kind.FORMULA)
                            .description("Reconstructed by inlining " + how + ": " + String.join(", ", inlined))
                            .build());
                    return; // full formula recovered — no need for a supplementary slice
                }
            }
        }

        // Phase 3b: cite the accumulating statements verbatim when the surface (or the
        // formula Tier 2 already found for it) references a path-dependent local.
        trySlice(surface, field, method, symbols, fqn, sourceFile, line, surfaceIsCalc, results);
    }

    private void trySlice(Expression surface, String field, MethodDeclaration method,
                          LocalSymbolTable symbols, String fqn, String sourceFile, int line,
                          boolean supplementsFormula,
                          Map<String, List<CalculationEvidence>> results) {
        String var = firstMultiAssignmentLocal(surface, symbols);
        if (var == null) return;

        com.github.javaparser.ast.stmt.Statement terminal =
                surface.findAncestor(com.github.javaparser.ast.stmt.Statement.class).orElse(null);
        AccumulatorSliceBuilder.Slice slice = AccumulatorSliceBuilder.build(method, var, terminal);
        if (slice.isEmpty()) return;

        String reason = slice.inLoop()
                ? "aggregation over loop — value depends on branch/iteration"
                : "value depends on branch conditions";
        String description = supplementsFormula
                ? "Path-dependent accumulator (" + reason + "); `" + var
                        + "` referenced above is not the whole story — cited verbatim"
                : "Path-dependent accumulator (" + reason + "); cited verbatim";

        results.computeIfAbsent(field, k -> new ArrayList<>()).add(CalculationEvidence.builder()
                .targetField(field)
                .locus(CalculationEvidence.Locus.BACKEND_ACCUMULATOR_SLICE)
                .expression(String.join("\n", slice.lines()))
                .inputFields(slice.inputs())
                .sourceFile(sourceFile)
                .sourceClass(fqn)
                .sourceMethod(method.getNameAsString())
                .lineNumber(line)
                .confidence(CalculationEvidence.Confidence.LOW)
                .tier(3)
                .kind(CalculationEvidence.Kind.SLICE)
                .language("java")
                .description(description)
                .build());
    }

    private String firstMultiAssignmentLocal(Expression expr, LocalSymbolTable symbols) {
        for (NameExpr n : expr.findAll(NameExpr.class)) {
            if (symbols.isMultiAssignment(n.getNameAsString())) {
                return n.getNameAsString();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Inlining engine
    // ------------------------------------------------------------------

    private Expression inline(Expression expr, LocalSymbolTable symbols, Set<String> inlinedOut) {
        Expression work = expr;
        Set<String> doneLocals = new HashSet<>();   // fully-expanded locals
        Set<String> doneHelpers = new HashSet<>();  // expanded helpers (cycle guard)

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            if (work.findAll(Node.class).size() > MAX_NODES) return null; // blow-up guard

            // 3a: expand ALL occurrences of one single-assignment local at once.
            String local = nextInlinableLocal(work, symbols, doneLocals);
            if (local != null) {
                Expression def = symbols.definitionOf(local);
                work = replaceAllNames(work, local, def);
                doneLocals.add(local);
                inlinedOut.add(local);
                continue;
            }

            // 3c: expand an in-project helper call with a single return.
            MethodCallExpr helperCall = nextInlinableHelper(work, doneHelpers);
            if (helperCall != null) {
                MethodDeclaration helper = helperIndex.get(key(helperCall.getNameAsString(),
                        helperCall.getArguments().size()));
                String sig = helperCall.getNameAsString() + "()";
                Expression body = expandHelper(helperCall, helper);
                if (body == null) return null;
                work = replaceOrRoot(work, helperCall, body);
                doneHelpers.add(sig);
                inlinedOut.add(sig);
                continue;
            }

            return work; // fixed point
        }
        return work;
    }

    private String nextInlinableLocal(Expression expr, LocalSymbolTable symbols, Set<String> done) {
        for (NameExpr n : expr.findAll(NameExpr.class)) {
            String id = n.getNameAsString();
            if (symbols.isSingleAssignment(id) && !done.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private MethodCallExpr nextInlinableHelper(Expression expr, Set<String> done) {
        for (MethodCallExpr c : expr.findAll(MethodCallExpr.class)) {
            String k = key(c.getNameAsString(), c.getArguments().size());
            if (helperIndex.containsKey(k) && !done.contains(c.getNameAsString() + "()")) {
                return c;
            }
        }
        return null;
    }

    /** Replaces every {@code NameExpr} named {@code var} in {@code root} with {@code def}. */
    private Expression replaceAllNames(Expression root, String var, Expression def) {
        if (root instanceof NameExpr rn && rn.getNameAsString().equals(var)) {
            return def.clone();
        }
        List<NameExpr> targets = new ArrayList<>();
        root.findAll(NameExpr.class).stream()
                .filter(n -> n.getNameAsString().equals(var))
                .forEach(targets::add);
        for (NameExpr t : targets) {
            t.replace(def.clone());
        }
        return root;
    }

    /** Substitutes formal params with actual args in a helper's single-return body. */
    private Expression expandHelper(MethodCallExpr call, MethodDeclaration helper) {
        Optional<ReturnStmt> ret = helper.findAll(ReturnStmt.class).stream().findFirst();
        if (ret.isEmpty() || ret.get().getExpression().isEmpty()) return null;
        Expression body = ret.get().getExpression().get().clone();

        List<Parameter> formals = helper.getParameters();
        for (int i = 0; i < formals.size() && i < call.getArguments().size(); i++) {
            String formalName = formals.get(i).getNameAsString();
            Expression actual = call.getArgument(i);
            List<NameExpr> targets = new ArrayList<>();
            if (body instanceof NameExpr bn && bn.getNameAsString().equals(formalName)) {
                body = actual.clone();
                continue;
            }
            body.findAll(NameExpr.class).stream()
                    .filter(n -> n.getNameAsString().equals(formalName))
                    .forEach(targets::add);
            for (NameExpr t : targets) {
                t.replace(actual.clone());
            }
        }
        return body;
    }

    /** Replaces a node in place, or returns the replacement when the node is the root. */
    private Expression replaceOrRoot(Expression root, Node target, Expression replacement) {
        if (target == root) {
            return replacement;
        }
        target.replace(replacement);
        return root;
    }

    // ------------------------------------------------------------------
    // Helper index (single-return in-project methods)
    // ------------------------------------------------------------------

    private Map<String, MethodDeclaration> buildHelperIndex(List<CompilationUnit> cus) {
        Map<String, MethodDeclaration> index = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (CompilationUnit cu : cus) {
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                if (m.getBody().isEmpty()) continue;
                List<ReturnStmt> returns = m.findAll(ReturnStmt.class);
                if (returns.size() != 1 || returns.get(0).getExpression().isEmpty()) continue;
                String k = key(m.getNameAsString(), m.getParameters().size());
                if (index.containsKey(k)) {
                    ambiguous.add(k); // overload by arity collision → drop to stay safe
                } else {
                    index.put(k, m);
                }
            }
        }
        ambiguous.forEach(index::remove);
        return index;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean isSetter(String name) {
        return name.length() > 3 && name.startsWith("set") && Character.isUpperCase(name.charAt(3));
    }

    private String assignmentTargetField(Expression target) {
        if (target instanceof com.github.javaparser.ast.expr.FieldAccessExpr fa) return fa.getNameAsString();
        if (target instanceof NameExpr n) return n.getNameAsString();
        return null;
    }

    private String key(String name, int arity) {
        return name + "/" + arity;
    }

    private String decapitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // Method-local symbol table
    // ------------------------------------------------------------------

    /**
     * Tracks each local variable's defining expression and whether it is assigned exactly
     * once (safe to inline) or multiple times / with a compound operator (not inlinable).
     */
    static final class LocalSymbolTable {
        private final Map<String, Expression> definitions = new HashMap<>();
        private final Set<String> multiAssign = new HashSet<>();
        private final Set<String> allLocals = new HashSet<>();

        static LocalSymbolTable of(MethodDeclaration method) {
            LocalSymbolTable t = new LocalSymbolTable();
            Map<String, Integer> counts = new HashMap<>();

            method.findAll(VariableDeclarator.class).forEach(vd -> {
                String name = vd.getNameAsString();
                t.allLocals.add(name);
                vd.getInitializer().ifPresent(init -> {
                    counts.merge(name, 1, Integer::sum);
                    t.definitions.put(name, init);
                });
            });

            method.findAll(AssignExpr.class).forEach(a -> {
                if (a.getTarget() instanceof NameExpr n) {
                    String name = n.getNameAsString();
                    counts.merge(name, 1, Integer::sum);
                    if (a.getOperator() != AssignExpr.Operator.ASSIGN) {
                        t.multiAssign.add(name); // compound assignment (+=, *= …)
                    } else {
                        t.definitions.put(name, a.getValue());
                    }
                }
            });

            counts.forEach((name, c) -> {
                if (c > 1) t.multiAssign.add(name);
            });
            return t;
        }

        boolean isLocal(String name) {
            return allLocals.contains(name);
        }

        boolean isSingleAssignment(String name) {
            return definitions.containsKey(name) && !multiAssign.contains(name);
        }

        /** True when the local is reassigned, or mutated with a compound operator (+=, *=…). */
        boolean isMultiAssignment(String name) {
            return multiAssign.contains(name);
        }

        Expression definitionOf(String name) {
            return definitions.get(name);
        }
    }
}
