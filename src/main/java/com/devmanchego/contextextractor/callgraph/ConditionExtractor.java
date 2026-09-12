package com.devmanchego.contextextractor.callgraph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Extracts semantically relevant conditions from a method body.
 *
 * Pre-filter: only top-level IfStmt (R5).
 * Discard rules: D1 null-guard, D2 inside loop, D3 instanceof, D4 logging/metrics, D5 isEmpty/size.
 * Accept rules:  R1 calls known service, R2 both branches substantive, R3 domain enum/constant,
 *                R4 controls return, R6 throws domain exception.
 * Grey territory (neither discarded nor accepted) is silently ignored.
 */
public final class ConditionExtractor {

    private static final Logger log = LoggerFactory.getLogger(ConditionExtractor.class);

    private static final Set<String> TECHNICAL_EXCEPTIONS = Set.of(
            "IllegalArgumentException", "NullPointerException", "IllegalStateException",
            "UnsupportedOperationException", "RuntimeException", "Exception",
            "ClassCastException", "IndexOutOfBoundsException", "AssertionError",
            "NumberFormatException", "ArithmeticException", "StackOverflowError");

    private static final Set<String> INFRASTRUCTURE_SCOPES = Set.of(
            "log", "logger", "LOG", "LOGGER", "metrics", "tracer", "span",
            "meter", "tracing", "monitoring", "audit");

    private static final Set<String> TECHNICAL_CONSTANT_PREFIXES = Set.of(
            "HttpStatus", "MediaType", "ContentType", "Objects", "Collections",
            "Arrays", "Math", "System", "Integer", "Long", "Double");

    private final Map<String, ClassOrInterfaceDeclaration> classIndex;

    public ConditionExtractor(Map<String, ClassOrInterfaceDeclaration> classIndex) {
        this.classIndex = classIndex;
    }

    /**
     * Returns the list of business-relevant conditions found in {@code method}'s
     * top-level body, given the variable/field type map from the enclosing scope.
     * Applies all discard (D1-D5) and accept (R1-R6) rules.
     */
    public List<FlowCondition> extract(MethodDeclaration method,
                                       Map<String, String> fieldTypes) {
        List<FlowCondition> result = new ArrayList<>();
        if (method.getBody().isEmpty()) return result;

        BlockStmt body = method.getBody().get();
        // Pre-filter R5: only top-level statements
        body.getStatements().forEach(stmt -> {
            if (stmt instanceof IfStmt ifStmt) {
                processIfStmt(ifStmt, fieldTypes, result);
            }
        });
        return result;
    }

    /**
     * Returns ALL top-level if/else conditions found in {@code method}'s body,
     * applying only the loop filter (D2): conditions nested inside for/while/forEach
     * are still excluded. All other discard and accept rules are bypassed.
     */
    public List<FlowCondition> extractAll(MethodDeclaration method,
                                          Map<String, String> fieldTypes) {
        List<FlowCondition> result = new ArrayList<>();
        if (method.getBody().isEmpty()) return result;

        method.getBody().get().getStatements().forEach(stmt -> {
            if (stmt instanceof IfStmt ifStmt && !isInsideLoop(ifStmt)) {
                String condText    = ifStmt.getCondition().toString();
                String thenSummary = summarizeBranch(ifStmt.getThenStmt(), fieldTypes);
                String elseSummary = ifStmt.getElseStmt()
                        .map(e -> summarizeBranch(e, fieldTypes))
                        .orElse(null);
                result.add(new FlowCondition(condText, thenSummary, elseSummary,
                                             ifStmt.getElseStmt().isPresent()));
            }
        });
        return result;
    }

    // -----------------------------------------------------------------------
    // Processing
    // -----------------------------------------------------------------------

    private void processIfStmt(IfStmt stmt, Map<String, String> fieldTypes,
                                List<FlowCondition> result) {
        if (isNullGuard(stmt))                          return;  // D1
        if (isInsideLoop(stmt))                         return;  // D2
        if (isInstanceofCheck(stmt))                    return;  // D3
        if (isLoggingOrMetrics(stmt))                   return;  // D4
        if (isSimpleCollectionSizeCheck(stmt))          return;  // D5

        boolean accept = callsKnownService(stmt, fieldTypes)         // R1
                      || bothBranchesSubstantive(stmt, fieldTypes)   // R2
                      || involvesDomainConstantOrEnum(stmt)          // R3
                      || controlsReturn(stmt)                        // R4
                      || throwsDomainException(stmt);                // R6

        if (!accept) return;

        String condText    = stmt.getCondition().toString();
        String thenSummary = summarizeBranch(stmt.getThenStmt(), fieldTypes);
        String elseSummary = stmt.getElseStmt()
                .map(e -> summarizeBranch(e, fieldTypes))
                .orElse(null);

        result.add(new FlowCondition(condText, thenSummary, elseSummary,
                                     stmt.getElseStmt().isPresent()));
    }

    // -----------------------------------------------------------------------
    // Discard rules
    // -----------------------------------------------------------------------

    /** D1: null/non-null guard — no else, or then-branch only does a technical throw. */
    private boolean isNullGuard(IfStmt stmt) {
        if (!isNullComparison(stmt.getCondition())) return false;
        if (stmt.getElseStmt().isEmpty()) return true;
        return containsOnlyTechnicalThrow(stmt.getThenStmt());
    }

    private boolean isNullComparison(Expression cond) {
        if (cond instanceof UnaryExpr u
                && u.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            cond = u.getExpression();
        }
        if (!(cond instanceof BinaryExpr bin)) return false;
        boolean nullSide = bin.getLeft() instanceof NullLiteralExpr
                        || bin.getRight() instanceof NullLiteralExpr;
        boolean eqOp = bin.getOperator() == BinaryExpr.Operator.EQUALS
                    || bin.getOperator() == BinaryExpr.Operator.NOT_EQUALS;
        return nullSide && eqOp;
    }

    private boolean containsOnlyTechnicalThrow(Statement stmt) {
        List<ThrowStmt> throws_ = stmt.findAll(ThrowStmt.class);
        return !throws_.isEmpty()
            && throws_.stream().allMatch(t -> isTechnicalException(t.getExpression().toString()));
    }

    private boolean isTechnicalException(String exprStr) {
        return TECHNICAL_EXCEPTIONS.stream().anyMatch(exprStr::contains);
    }

    /** D2: the IfStmt is a direct child inside a loop body. */
    private boolean isInsideLoop(IfStmt stmt) {
        return stmt.getParentNode()
                .flatMap(Node::getParentNode)
                .map(p -> p instanceof ForStmt || p instanceof WhileStmt
                       || p instanceof ForEachStmt || p instanceof DoStmt)
                .orElse(false);
    }

    /** D3: condition is an instanceof check. */
    private boolean isInstanceofCheck(IfStmt stmt) {
        Expression cond = unwrapNot(stmt.getCondition());
        return cond instanceof InstanceOfExpr;
    }

    /** D4: condition or then-body deals only with logging/metrics infrastructure. */
    private boolean isLoggingOrMetrics(IfStmt stmt) {
        Expression cond = stmt.getCondition();
        if (cond instanceof MethodCallExpr call) {
            if (call.getScope().map(s -> INFRASTRUCTURE_SCOPES.contains(s.toString()))
                    .orElse(false)) return true;
        }
        List<MethodCallExpr> calls = stmt.getThenStmt().findAll(MethodCallExpr.class);
        return !calls.isEmpty() && calls.stream().allMatch(c ->
                c.getScope().map(s -> INFRASTRUCTURE_SCOPES.contains(s.toString()))
                            .orElse(false));
    }

    /** D5: simple isEmpty/isBlank/isPresent/isNotEmpty check without a substantive else. */
    private boolean isSimpleCollectionSizeCheck(IfStmt stmt) {
        if (stmt.getElseStmt().isPresent()) return false;
        Expression cond = unwrapNot(stmt.getCondition());
        if (!(cond instanceof MethodCallExpr call)) return false;
        String name = call.getNameAsString();
        return name.equals("isEmpty")  || name.equals("isBlank")
            || name.equals("isPresent") || name.equals("isNotEmpty")
            || name.equals("hasNext");
    }

    // -----------------------------------------------------------------------
    // Accept rules
    // -----------------------------------------------------------------------

    /** R1: condition is a direct call to a method on a known service object. */
    private boolean callsKnownService(IfStmt stmt, Map<String, String> fieldTypes) {
        Expression cond = unwrapNot(stmt.getCondition());
        if (!(cond instanceof MethodCallExpr call)) return false;
        return call.getScope()
                .map(s -> isKnownServiceVar(s.toString(), fieldTypes))
                .orElse(false);
    }

    /** R2: both branches make a call to a known service method. */
    private boolean bothBranchesSubstantive(IfStmt stmt, Map<String, String> fieldTypes) {
        if (stmt.getElseStmt().isEmpty()) return false;
        return branchHasServiceCall(stmt.getThenStmt(), fieldTypes)
            && branchHasServiceCall(stmt.getElseStmt().get(), fieldTypes);
    }

    private boolean branchHasServiceCall(Statement branch, Map<String, String> fieldTypes) {
        return branch.findAll(MethodCallExpr.class).stream()
                .anyMatch(c -> c.getScope()
                        .map(s -> isKnownServiceVar(s.toString(), fieldTypes))
                        .orElse(false));
    }

    /** R3: condition references a domain enum literal (UpperCase.CONSTANT pattern). */
    private boolean involvesDomainConstantOrEnum(IfStmt stmt) {
        return stmt.getCondition().findAll(FieldAccessExpr.class).stream()
                .anyMatch(fa -> {
                    String scope = fa.getScope().toString();
                    return Character.isUpperCase(scope.charAt(0))
                        && TECHNICAL_CONSTANT_PREFIXES.stream().noneMatch(scope::startsWith);
                });
    }

    /** R4: condition directly controls the return value of the method. */
    private boolean controlsReturn(IfStmt stmt) {
        boolean thenRet = !stmt.getThenStmt().findAll(ReturnStmt.class).isEmpty();
        boolean elseRet = stmt.getElseStmt()
                .map(e -> !e.findAll(ReturnStmt.class).isEmpty())
                .orElse(false);
        return thenRet || elseRet;
    }

    /** R6: throws a domain-specific (non-JDK) exception. */
    private boolean throwsDomainException(IfStmt stmt) {
        return stmt.findAll(ThrowStmt.class).stream()
                .anyMatch(t -> !isTechnicalException(t.getExpression().toString()));
    }

    // -----------------------------------------------------------------------
    // Branch summarization
    // -----------------------------------------------------------------------

    private String summarizeBranch(Statement branch, Map<String, String> fieldTypes) {
        // 1. Throw
        List<ThrowStmt> throws_ = branch.findAll(ThrowStmt.class);
        if (!throws_.isEmpty()) {
            return "throws " + extractTypeName(throws_.get(0).getExpression().toString());
        }
        // 2. Return with meaningful expression
        List<ReturnStmt> returns = branch.findAll(ReturnStmt.class);
        if (!returns.isEmpty()) {
            return returns.get(0).getExpression()
                    .filter(e -> e instanceof MethodCallExpr || e instanceof ObjectCreationExpr
                              || e instanceof FieldAccessExpr)
                    .map(e -> "return " + truncate(e.toString(), 45))
                    .orElse(null);
        }
        // 3. Service method call
        for (MethodCallExpr call : branch.findAll(MethodCallExpr.class)) {
            if (call.getScope().map(s -> isKnownServiceVar(s.toString(), fieldTypes))
                    .orElse(false)) {
                return "calls " + call.getScope().get() + "." + call.getNameAsString() + "()";
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean isKnownServiceVar(String scopeStr, Map<String, String> fieldTypes) {
        String name = scopeStr.contains(".")
                ? scopeStr.substring(scopeStr.lastIndexOf('.') + 1) : scopeStr;
        String type = fieldTypes.get(name);
        return type != null && findClassEntry(type) != null;
    }

    private Map.Entry<String, ClassOrInterfaceDeclaration> findClassEntry(String name) {
        if (classIndex.containsKey(name)) return Map.entry(name, classIndex.get(name));
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return classIndex.entrySet().stream()
                .filter(e -> e.getValue().getNameAsString().equals(simple))
                .findFirst().orElse(null);
    }

    private String extractTypeName(String expr) {
        int paren = expr.indexOf('(');
        String base = paren > 0 ? expr.substring(0, paren) : expr;
        return base.replaceFirst("^new\\s+", "").trim();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private Expression unwrapNot(Expression expr) {
        if (expr instanceof UnaryExpr u
                && u.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return u.getExpression();
        }
        return expr;
    }
}
