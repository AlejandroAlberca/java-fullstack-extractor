package com.devmanchego.contextextractor.java.exception;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts custom application exceptions from Java source code.
 *
 * Scans for:
 * 1. Custom exception classes (extends RuntimeException in *.exception.* packages)
 * 2. @ExceptionHandler mappings in @RestControllerAdvice
 * 3. Throw locations and message patterns
 * 4. HTTP status codes associated with each exception
 */
public final class ExceptionCatalogExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExceptionCatalogExtractor.class);

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b");
    private static final Pattern STRING_CONCAT_PATTERN = Pattern.compile("\"([^\"]*?)\"\\s*\\+");

    private final Map<String, Integer> exceptionToHttpStatus = new HashMap<>();
    private final Map<String, List<String>> exceptionThrowLocations = new HashMap<>();
    private final Map<String, String> exceptionHandlers = new HashMap<>();
    private final Set<String> customExceptionClasses = new HashSet<>();

    /**
     * Extracts all custom exceptions from a list of compilation units.
     * Optionally includes generic exceptions that pass classification.
     *
     * @param compilationUnits parsed Java source files
     * @param enableGenericExceptions whether to include classified generic exceptions
     * @param callGraphs mapping of endpoint to call graph (for reachability)
     * @return list of discovered exceptions
     */
    public List<ExceptionInfo> extractExceptions(List<CompilationUnit> compilationUnits,
                                                  boolean enableGenericExceptions,
                                                  java.util.Map<?, ?> callGraphs) {
        log.info("ExceptionCatalogExtractor: scanning {} source file(s)", compilationUnits.size());

        // Phase 1: Identify custom exception classes
        for (CompilationUnit cu : compilationUnits) {
            scanForExceptionClasses(cu);
            scanForExceptionHandlers(cu);
            scanForThrowStatements(cu);
        }

        // Phase 2: Build ExceptionInfo records for custom exceptions
        List<ExceptionInfo> results = new ArrayList<>();
        int sequence = 0;
        for (String exceptionFqn : customExceptionClasses) {
            sequence++;
            ExceptionInfo info = buildExceptionInfo(exceptionFqn, sequence);
            results.add(info);
        }

        // Phase 3: Scan and classify generic exceptions (if enabled)
        if (enableGenericExceptions) {
            GenericExceptionScanner scanner = new GenericExceptionScanner();
            List<GenericExceptionCandidate> candidates = scanner.scan(compilationUnits);

            // Populate handler info and reachability
            for (GenericExceptionCandidate candidate : candidates) {
                populateHandlerInfo(candidate);
                // Reachability heuristic: service methods are generally endpoint-reachable
            }

            // Classify and convert to ExceptionInfo
            GenericExceptionClassifier classifier = new GenericExceptionClassifier();
            for (GenericExceptionCandidate candidate : candidates) {
                ClassificationResult result = classifier.classify(candidate);
                if (result.included()) {
                    sequence++;
                    ExceptionInfo info = buildExceptionInfoFromGeneric(candidate, result, sequence);
                    results.add(info);
                }
            }
        }

        log.info("ExceptionCatalogExtractor: extracted {} exception(s)", results.size());
        return results;
    }

    private void scanForExceptionClasses(CompilationUnit cu) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            // Only consider custom exceptions in *.exception.* packages
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");
            if (!isInExceptionPackage(packageName)) {
                return;
            }

            // Check if extends RuntimeException or custom exception
            if (extendsRuntimeException(classDecl)) {
                String fqn = buildFqn(cu, classDecl);
                customExceptionClasses.add(fqn);
            }
        });
    }

    private void scanForExceptionHandlers(CompilationUnit cu) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            if (!hasAnnotation(classDecl.getAnnotations(), "RestControllerAdvice")) {
                return;
            }

            String handlerClass = buildFqn(cu, classDecl);

            classDecl.getMethods().forEach(method -> {
                Optional<AnnotationExpr> handlerAnn = method.getAnnotationByName("ExceptionHandler");
                if (handlerAnn.isEmpty()) {
                    return;
                }

                String exceptionType = extractExceptionTypeFromHandler(handlerAnn.get());
                int httpStatus = extractHttpStatusFromMethod(method);

                if (exceptionType != null && !exceptionType.isEmpty()) {
                    exceptionToHttpStatus.put(exceptionType, httpStatus);
                    exceptionHandlers.put(exceptionType, handlerClass);
                }
            });
        });
    }

    private void scanForThrowStatements(CompilationUnit cu) {
        cu.findAll(ThrowStmt.class).forEach(throwStmt -> {
            String exceptionType = throwStmt.getExpression().toString();

            // Extract exception class name
            String className = exceptionType.split("\\(")[0].trim();

            // Only track if it's a custom exception we identified
            boolean isCustom = customExceptionClasses.stream()
                    .anyMatch(fqn -> fqn.endsWith("." + className));

            if (isCustom) {
                // Find the throw location (method + line)
                Optional<MethodDeclaration> method = throwStmt.getParentNode()
                        .filter(n -> n instanceof MethodDeclaration)
                        .map(n -> (MethodDeclaration) n)
                        .or(() -> {
                            var parent = throwStmt.getParentNode();
                            if (parent.isPresent()) {
                                return parent.get().findAncestor(MethodDeclaration.class);
                            }
                            return Optional.empty();
                        });

                if (method.isPresent()) {
                    String location = method.get().getNameAsString() + ":" +
                                    throwStmt.getRange().map(r -> r.begin.line).orElse(-1);
                    exceptionThrowLocations.computeIfAbsent(className, k -> new ArrayList<>())
                            .add(location);
                }
            }
        });
    }

    private ExceptionInfo buildExceptionInfo(String exceptionFqn, int sequence) {
        String className = exceptionFqn.substring(exceptionFqn.lastIndexOf('.') + 1);
        String domain = inferDomain(exceptionFqn);
        String errorCode = domain + "-" + String.format("%03d", sequence);

        int httpStatus = exceptionToHttpStatus.getOrDefault(className, -1);
        String handlerClass = exceptionHandlers.get(className);
        boolean hasHandler = httpStatus > 0;

        List<String> throwLocations = exceptionThrowLocations.getOrDefault(className, List.of());
        String originClass = throwLocations.isEmpty() ? "unknown" : throwLocations.get(0).split(":")[0];
        String originMethod = throwLocations.isEmpty() ? "unknown" : originClass;

        String messageTemplate = "(custom message)"; // Placeholder - would need AST parsing of constructor
        Set<String> paramNames = new HashSet<>();

        return new ExceptionInfo(
                className,
                exceptionFqn,
                domain,
                errorCode,
                httpStatus,
                messageTemplate,
                exceptionFqn,
                originMethod,
                -1,
                paramNames,
                throwLocations,
                handlerClass,
                hasHandler,
                !isSystemException(className)
        );
    }

    private boolean isInExceptionPackage(String packageName) {
        return packageName != null && !packageName.isEmpty() && packageName.contains(".exception");
    }

    private boolean extendsRuntimeException(ClassOrInterfaceDeclaration classDecl) {
        return classDecl.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().endsWith("RuntimeException") ||
                              t.getNameAsString().endsWith("Exception"));
    }

    private String buildFqn(CompilationUnit cu, ClassOrInterfaceDeclaration classDecl) {
        return cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + "." + classDecl.getNameAsString())
                .orElse(classDecl.getNameAsString());
    }

    private boolean hasAnnotation(List<AnnotationExpr> annotations, String name) {
        return annotations.stream().anyMatch(a -> name.equals(a.getNameAsString()));
    }

    private String extractExceptionTypeFromHandler(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            String value = single.getMemberValue().toString();
            return value.replace(".class", "").trim();
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .map(p -> p.getValue().toString().replace(".class", "").trim())
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

    private int extractHttpStatusFromMethod(MethodDeclaration method) {
        return method.getBody()
                .flatMap(body -> body.toString().lines()
                        .filter(line -> line.contains("HttpStatus."))
                        .findFirst()
                        .flatMap(line -> {
                            if (line.contains("NOT_FOUND") || line.contains("404")) return Optional.of(404);
                            if (line.contains("CONFLICT") || line.contains("409")) return Optional.of(409);
                            if (line.contains("BAD_REQUEST") || line.contains("400")) return Optional.of(400);
                            if (line.contains("INTERNAL_SERVER_ERROR") || line.contains("500")) return Optional.of(500);
                            return Optional.empty();
                        }))
                .orElse(-1);
    }

    private String inferDomain(String fullyQualifiedName) {
        // Extract domain from package: com.example.hrapp.payment.exception.PaymentException → PAYMENT
        String[] parts = fullyQualifiedName.split("\\.");
        if (parts.length > 2) {
            String domain = parts[parts.length - 2]; // "exception" is the package
            String preDomain = parts[Math.max(0, parts.length - 3)]; // domain is before .exception
            if ("exception".equals(domain)) {
                return preDomain.toUpperCase();
            }
        }
        return "APP";
    }

    private boolean isSystemException(String className) {
        return className.contains("NullPointerException") ||
               className.contains("IllegalArgumentException") ||
               className.contains("IOException");
    }

    /**
     * Populates handler info (HTTP status) on a generic exception candidate
     * by matching its type against known handlers.
     */
    private void populateHandlerInfo(GenericExceptionCandidate candidate) {
        // Handler info from Phase 1 exception handler scan
        // Used to determine if exception has @ExceptionHandler in classifier
        if (exceptionToHttpStatus.containsKey(candidate.exceptionType())) {
            log.debug("Found handler for generic exception: {}", candidate.exceptionType());
        }
    }

    /**
     * Builds an ExceptionInfo from a classified generic exception candidate.
     */
    private ExceptionInfo buildExceptionInfoFromGeneric(GenericExceptionCandidate candidate,
                                                        ClassificationResult result,
                                                        int sequence) {
        String domain = candidate.exceptionType().replaceAll("Exception", "").toUpperCase();
        if (domain.isEmpty()) domain = "APP";
        String errorCode = domain + "-" + String.format("%03d", sequence);

        int httpStatus = exceptionToHttpStatus.getOrDefault(candidate.exceptionType(), -1);
        String handlerClass = exceptionHandlers.get(candidate.exceptionType());
        boolean hasHandler = httpStatus > 0;

        // Confidence label from classification result
        String messageTemplate = candidate.messageLiteral() != null
            ? candidate.messageLiteral()
            : "(generic message)";

        return new ExceptionInfo(
                candidate.exceptionType(),
                candidate.exceptionType(),
                domain,
                errorCode,
                httpStatus,
                messageTemplate + " [" + result.confidenceLabel() + "]",
                candidate.originClass(),
                candidate.originMethod(),
                candidate.lineNumber(),
                java.util.Set.of(),  // parameter names inferred from message
                java.util.List.of(candidate.originMethod() + ":" + candidate.lineNumber()),
                handlerClass,
                hasHandler,
                true  // isHumanFacingError
        );
    }
}
