package com.devmanchego.contextextractor.java.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2a: Tier 1 Backend — detects literal field calculations in Java source.
 *
 * Detects:
 * - @Formula (Hibernate SQL)
 * - @Mapping(target=X, expression="java(...)")
 * - @Mapping(target=X, constant=Y, defaultValue=Z)
 * - @Transient getter methods with literal arithmetic
 * - @PrePersist/@PreUpdate methods
 */
public final class FieldCalculationDetector {

    private static final Logger log = LoggerFactory.getLogger(FieldCalculationDetector.class);

    /**
     * Scans all compilation units for calculation evidence (Tier 1 literal only).
     *
     * @param compilationUnits parsed Java source files
     * @return map of field name → list of calculation evidences
     */
    public Map<String, List<CalculationEvidence>> detect(List<CompilationUnit> compilationUnits) {
        Map<String, List<CalculationEvidence>> results = new LinkedHashMap<>();

        for (CompilationUnit cu : compilationUnits) {
            String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            String sourceFile = cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown");

            // Scan entities for @Formula
            scanForFormulas(cu, pkg, sourceFile, results);

            // Scan mappers for @Mapping expression/constant/defaultValue
            scanForMapStructMappings(cu, pkg, sourceFile, results);

            // Scan for @Transient getters
            scanForTransientGetters(cu, pkg, sourceFile, results);

            // Scan for @PrePersist/@PreUpdate
            scanForPrePersistUpdate(cu, pkg, sourceFile, results);

            // Scan DTOs (records) for derived fields
            scanForRecordFields(cu, pkg, sourceFile, results);
        }

        log.info("FieldCalculationDetector: detected {} field(s) with calculation evidence", results.size());
        return results;
    }

    private void scanForFormulas(CompilationUnit cu, String pkg, String sourceFile,
                                 Map<String, List<CalculationEvidence>> results) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            field.getAnnotationByName("Formula").ifPresent(ann -> {
                String formula = extractAnnotationValue(ann);
                if (formula != null) {
                    String fieldName = field.getVariables().get(0).getNameAsString();
                    String className = findEnclosingClass(field).map(ClassOrInterfaceDeclaration::getNameAsString).orElse("Unknown");

                    List<String> inputs = extractSqlInputFields(formula);

                    CalculationEvidence evidence = CalculationEvidence.builder()
                            .targetField(fieldName)
                            .locus(CalculationEvidence.Locus.BACKEND_FORMULA_HIBERNATE)
                            .expression(formula)
                            .inputFields(inputs)
                            .sourceFile(sourceFile)
                            .sourceClass(pkg + "." + className)
                            .lineNumber(field.getRange().map(r -> r.begin.line).orElse(-1))
                            .confidence(CalculationEvidence.Confidence.HIGH)
                            .tier(1)
                            .description("SQL formula via @Formula annotation")
                            .build();

                    results.computeIfAbsent(fieldName, k -> new ArrayList<>()).add(evidence);
                }
            });
        });
    }

    private void scanForMapStructMappings(CompilationUnit cu, String pkg, String sourceFile,
                                          Map<String, List<CalculationEvidence>> results) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            method.getAnnotations().forEach(ann -> {
                if (!"Mapping".equals(ann.getNameAsString())) return;

                String target = extractAnnotationAttr(ann, "target");
                String expression = extractAnnotationAttr(ann, "expression");
                String constant = extractAnnotationAttr(ann, "constant");
                String defaultValue = extractAnnotationAttr(ann, "defaultValue");

                if (target != null) {
                    String className = findEnclosingClass(method).map(ClassOrInterfaceDeclaration::getNameAsString).orElse("Unknown");

                    if (expression != null && !expression.isEmpty()) {
                        // @Mapping(target=X, expression="java(...)")
                        List<String> inputs = extractJavaExpressionInputs(expression);

                        CalculationEvidence evidence = CalculationEvidence.builder()
                                .targetField(target)
                                .locus(CalculationEvidence.Locus.BACKEND_MAPSTRUCT_EXPRESSION)
                                .expression(expression)
                                .inputFields(inputs)
                                .sourceFile(sourceFile)
                                .sourceClass(pkg + "." + className)
                                .sourceMethod(method.getNameAsString())
                                .lineNumber(method.getRange().map(r -> r.begin.line).orElse(-1))
                                .confidence(CalculationEvidence.Confidence.HIGH)
                                .tier(1)
                                .description("MapStruct @Mapping with expression")
                                .build();

                        results.computeIfAbsent(target, k -> new ArrayList<>()).add(evidence);
                    } else if (constant != null && !constant.isEmpty()) {
                        // @Mapping(target=X, constant=Y)
                        CalculationEvidence evidence = CalculationEvidence.builder()
                                .targetField(target)
                                .locus(CalculationEvidence.Locus.BACKEND_MAPSTRUCT_CONSTANT)
                                .expression(constant)
                                .sourceFile(sourceFile)
                                .sourceClass(pkg + "." + className)
                                .sourceMethod(method.getNameAsString())
                                .lineNumber(method.getRange().map(r -> r.begin.line).orElse(-1))
                                .confidence(CalculationEvidence.Confidence.HIGH)
                                .tier(1)
                                .description("MapStruct @Mapping with constant value")
                                .build();

                        results.computeIfAbsent(target, k -> new ArrayList<>()).add(evidence);
                    } else if (defaultValue != null && !defaultValue.isEmpty()) {
                        // @Mapping(target=X, defaultValue=Z)
                        CalculationEvidence evidence = CalculationEvidence.builder()
                                .targetField(target)
                                .locus(CalculationEvidence.Locus.BACKEND_MAPSTRUCT_DEFAULT)
                                .expression(defaultValue)
                                .sourceFile(sourceFile)
                                .sourceClass(pkg + "." + className)
                                .sourceMethod(method.getNameAsString())
                                .lineNumber(method.getRange().map(r -> r.begin.line).orElse(-1))
                                .confidence(CalculationEvidence.Confidence.HIGH)
                                .tier(1)
                                .description("MapStruct @Mapping with defaultValue")
                                .build();

                        results.computeIfAbsent(target, k -> new ArrayList<>()).add(evidence);
                    }
                }
            });
        });
    }

    private void scanForTransientGetters(CompilationUnit cu, String pkg, String sourceFile,
                                         Map<String, List<CalculationEvidence>> results) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (!method.getAnnotationByName("Transient").isPresent()) return;
            if (!method.getNameAsString().startsWith("get")) return;

            String fieldName = method.getNameAsString().substring(3); // Remove "get"
            fieldName = fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1); // Lowercase first letter

            String className = findEnclosingClass(method).map(ClassOrInterfaceDeclaration::getNameAsString).orElse("Unknown");

            // Extract inputs from method body (simple: collect all field references)
            List<String> inputs = extractMethodInputs(method);

            CalculationEvidence evidence = CalculationEvidence.builder()
                    .targetField(fieldName)
                    .locus(CalculationEvidence.Locus.BACKEND_GETTER_TRANSIENT)
                    .expression(method.getBody().map(Object::toString).orElse(""))
                    .inputFields(inputs)
                    .sourceFile(sourceFile)
                    .sourceClass(pkg + "." + className)
                    .sourceMethod(method.getNameAsString())
                    .lineNumber(method.getRange().map(r -> r.begin.line).orElse(-1))
                    .confidence(CalculationEvidence.Confidence.MEDIUM)
                    .tier(1)
                    .description("@Transient getter with calculation")
                    .build();

            results.computeIfAbsent(fieldName, k -> new ArrayList<>()).add(evidence);
        });
    }

    private void scanForPrePersistUpdate(CompilationUnit cu, String pkg, String sourceFile,
                                         Map<String, List<CalculationEvidence>> results) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<String> annotationName = method.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .filter(name -> "PrePersist".equals(name) || "PreUpdate".equals(name))
                    .findFirst();

            if (annotationName.isEmpty()) return;

            String className = findEnclosingClass(method).map(ClassOrInterfaceDeclaration::getNameAsString).orElse("Unknown");
            String locus = "PrePersist".equals(annotationName.get())
                    ? "BACKEND_PRE_PERSIST"
                    : "BACKEND_PRE_UPDATE";

            // Extract assignment targets from method body (e.g., "this.updatedAt = ...")
            List<String> targets = extractAssignmentTargets(method);

            for (String target : targets) {
                CalculationEvidence evidence = CalculationEvidence.builder()
                        .targetField(target)
                        .locus(CalculationEvidence.Locus.valueOf(locus))
                        .expression(method.getBody().map(Object::toString).orElse(""))
                        .sourceFile(sourceFile)
                        .sourceClass(pkg + "." + className)
                        .sourceMethod(method.getNameAsString())
                        .lineNumber(method.getRange().map(r -> r.begin.line).orElse(-1))
                        .confidence(CalculationEvidence.Confidence.MEDIUM)
                        .tier(1)
                        .description("Field calculated in " + annotationName.get())
                        .build();

                results.computeIfAbsent(target, k -> new ArrayList<>()).add(evidence);
            }
        });
    }

    private void scanForRecordFields(CompilationUnit cu, String pkg, String sourceFile,
                                     Map<String, List<CalculationEvidence>> results) {
        cu.findAll(RecordDeclaration.class).forEach(record -> {
            if (!isDtoPackage(pkg)) return;

            String className = record.getNameAsString();

            // Record compact constructors and field presence indicates derived fields
            // (simple heuristic: if field name suggests derivation, flag it)
            record.getParameters().forEach(param -> {
                String paramName = param.getNameAsString();
                String paramType = param.getType().asString();

                if (suggestsDerivedField(paramName)) {
                    CalculationEvidence evidence = CalculationEvidence.builder()
                            .targetField(paramName)
                            .locus(CalculationEvidence.Locus.BACKEND_DTO_FIELD)
                            .sourceFile(sourceFile)
                            .sourceClass(pkg + "." + className)
                            .confidence(CalculationEvidence.Confidence.LOW)
                            .tier(1)
                            .description("DTO record field suggesting derivation (heuristic)")
                            .build();

                    results.computeIfAbsent(paramName, k -> new ArrayList<>()).add(evidence);
                }
            });
        });
    }

    // ========== Helpers ==========

    private String extractAnnotationValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return unquote(single.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()))
                    .findFirst()
                    .map(MemberValuePair::getValue)
                    .map(v -> unquote(v.toString()))
                    .orElse(null);
        }
        return null;
    }

    private String extractAnnotationAttr(AnnotationExpr ann, String attrName) {
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> attrName.equals(p.getNameAsString()))
                    .findFirst()
                    .map(MemberValuePair::getValue)
                    .map(v -> unquote(v.toString()))
                    .orElse(null);
        }
        if ("value".equals(attrName) && ann instanceof SingleMemberAnnotationExpr single) {
            return unquote(single.getMemberValue().toString());
        }
        return null;
    }

    private String unquote(String s) {
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private List<String> extractSqlInputFields(String sql) {
        // Strip quoted string literals so their words aren't mistaken for columns
        // (e.g. CASE WHEN ... THEN 'Senior' ELSE 'Junior' END).
        String withoutLiterals = sql.replaceAll("'[^']*'", " ");

        List<String> inputs = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\b", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(withoutLiterals);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String word = matcher.group(1).toLowerCase();
            if (!SQL_KEYWORDS.contains(word) && seen.add(word)) {
                inputs.add(word);
            }
        }
        return inputs;
    }

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "select", "from", "where", "and", "or", "case", "when", "then", "else", "end",
            "count", "sum", "avg", "min", "max", "join", "inner", "left", "right",
            "as", "in", "is", "not", "null", "between", "like"
    );

    private List<String> extractJavaExpressionInputs(String expr) {
        List<String> inputs = new ArrayList<>();
        // Simple heuristic: extract method calls like object.getField()
        Pattern pattern = Pattern.compile("\\.(get|is)([A-Z][a-zA-Z0-9]*)\\(");
        Matcher matcher = pattern.matcher(expr);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String fieldName = matcher.group(2);
            fieldName = fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1);
            if (seen.add(fieldName)) {
                inputs.add(fieldName);
            }
        }
        return inputs;
    }

    private List<String> extractMethodInputs(MethodDeclaration method) {
        List<String> inputs = new ArrayList<>();
        String body = method.getBody().map(Object::toString).orElse("");
        // Extract this.fieldName references
        Pattern pattern = Pattern.compile("this\\.([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(body);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            if (seen.add(matcher.group(1))) {
                inputs.add(matcher.group(1));
            }
        }
        return inputs;
    }

    private List<String> extractAssignmentTargets(MethodDeclaration method) {
        List<String> targets = new ArrayList<>();
        String body = method.getBody().map(Object::toString).orElse("");
        // Extract this.fieldName = ... patterns
        Pattern pattern = Pattern.compile("this\\.([a-zA-Z_][a-zA-Z0-9_]*)\\s*=");
        Matcher matcher = pattern.matcher(body);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            if (seen.add(matcher.group(1))) {
                targets.add(matcher.group(1));
            }
        }
        return targets;
    }

    private Optional<ClassOrInterfaceDeclaration> findEnclosingClass(com.github.javaparser.ast.Node node) {
        return node.findAncestor(ClassOrInterfaceDeclaration.class);
    }

    private boolean isDtoPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return pkg.contains("dto") || pkg.contains("model") || pkg.contains("api");
    }

    private boolean suggestsDerivedField(String fieldName) {
        String lower = fieldName.toLowerCase();
        return lower.contains("count") || lower.contains("total") || lower.contains("sum") ||
               lower.contains("average") || lower.contains("percent") || lower.contains("annual") ||
               lower.contains("monthly") || lower.contains("fullname") || lower.contains("display");
    }
}
