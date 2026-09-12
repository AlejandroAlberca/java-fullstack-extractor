package com.devmanchego.contextextractor.java.exception;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scans Java source for throws of <em>generic</em> framework exceptions
 * (IllegalArgumentException, IllegalStateException, etc.) and produces
 * {@link GenericExceptionCandidate} records with AST-derived facts.
 *
 * <p>For each throw, captures: exception type, whether it's inside an if-guard,
 * whether it's in a catch block, message literal, layer (business/infra),
 * constructor/init context. Reachability is computed separately via call graph.
 */
public final class GenericExceptionScanner {

    private static final Logger log = LoggerFactory.getLogger(GenericExceptionScanner.class);

    private static final Set<String> GENERIC_EXCEPTION_TYPES = Set.of(
            "IllegalArgumentException",
            "IllegalStateException",
            "UnsupportedOperationException",
            "ClassCastException",
            "NullPointerException",
            "ArrayIndexOutOfBoundsException",
            "IndexOutOfBoundsException",
            "ArithmeticException",
            "AssertionError",
            "RuntimeException"
    );

    /**
     * Scans all compilation units for throws of generic exceptions,
     * producing candidates with full AST context.
     */
    public List<GenericExceptionCandidate> scan(List<CompilationUnit> compilationUnits) {
        List<GenericExceptionCandidate> candidates = new ArrayList<>();

        for (CompilationUnit cu : compilationUnits) {
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            cu.findAll(ThrowStmt.class).forEach(throwStmt -> {
                Optional<GenericExceptionCandidate> candidate = parseThrowStatement(
                        throwStmt, cu, packageName);
                candidate.ifPresent(candidates::add);
            });
        }

        log.info("GenericExceptionScanner: found {} generic exception throw(s)", candidates.size());
        return candidates;
    }

    private Optional<GenericExceptionCandidate> parseThrowStatement(
            ThrowStmt throwStmt, CompilationUnit cu, String packageName) {

        // Extract the exception type name from "throw new XxxException(...)"
        if (!(throwStmt.getExpression() instanceof ObjectCreationExpr oce)) {
            return Optional.empty();
        }

        String exceptionType = oce.getTypeAsString();

        // Only process if it's a known generic type
        if (!GENERIC_EXCEPTION_TYPES.stream()
                .anyMatch(t -> exceptionType.endsWith(t))) {
            return Optional.empty();
        }

        // Extract message literal (if present)
        String messageLiteral = null;
        if (!oce.getArguments().isEmpty()) {
            var firstArg = oce.getArgument(0);
            if (firstArg.isStringLiteralExpr()) {
                messageLiteral = firstArg.asStringLiteralExpr().getValue();
            }
        }

        // Detect if thrown inside an if-guard
        boolean insideIf = throwStmt.getParentNode()
                .map(parent -> parent instanceof IfStmt)
                .orElse(false);

        // Detect if inside a catch block
        Optional<CatchClause> catchParent = throwStmt.findAncestor(CatchClause.class);
        boolean insideCatch = catchParent.isPresent();

        // Get enclosing method + class
        Optional<MethodDeclaration> method = throwStmt.findAncestor(MethodDeclaration.class);
        Optional<ClassOrInterfaceDeclaration> klass = throwStmt.findAncestor(
                ClassOrInterfaceDeclaration.class);

        if (method.isEmpty() || klass.isEmpty()) {
            return Optional.empty();
        }

        MethodDeclaration methodDecl = method.get();
        ClassOrInterfaceDeclaration classDecl = klass.get();

        String originClass = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + "." + classDecl.getNameAsString())
                .orElse(classDecl.getNameAsString());
        String originMethod = methodDecl.getNameAsString();

        // Detect if in constructor / static init / @Bean method
        boolean inCtorOrInit = methodDecl.isConstructorDeclaration()
                || methodDecl.isStatic() && "static".equals(methodDecl.getModifiers().toString())
                || methodDecl.getAnnotationByName("Bean").isPresent();

        // Infer layer from package name
        GenericExceptionCandidate.Layer layer = inferLayer(packageName, classDecl.getNameAsString());

        int lineNumber = throwStmt.getRange().map(r -> r.begin.line).orElse(-1);

        return Optional.of(new GenericExceptionCandidate(
                exceptionType.substring(exceptionType.lastIndexOf('.') + 1),
                false,  // hasExplicitHandler — will be set later by caller
                insideIf,
                insideCatch,
                inCtorOrInit,
                messageLiteral,
                layer,
                false,  // reachableFromEndpoint — will be set later via call graph
                originClass,
                originMethod,
                lineNumber
        ));
    }

    private GenericExceptionCandidate.Layer inferLayer(String packageName, String className) {
        // Business layer: *.service.*, *.domain.*, *Service, *UseCase, *Handler (non-advice)
        if (packageName.contains(".service") || packageName.contains(".domain")
                || className.endsWith("Service") || className.endsWith("UseCase")
                || (className.endsWith("Handler") && !packageName.contains(".exception"))) {
            return GenericExceptionCandidate.Layer.BUSINESS;
        }

        // Controller layer: *.controller.*, *Controller, *Resource
        if (packageName.contains(".controller") || className.endsWith("Controller")
                || className.endsWith("Resource")) {
            return GenericExceptionCandidate.Layer.CONTROLLER;
        }

        // Infra/util layer: *.config.*, *.util.*, *.infrastructure.*, *Config, *Utils, *Client
        if (packageName.contains(".config") || packageName.contains(".util")
                || packageName.contains(".infrastructure")
                || className.endsWith("Config") || className.endsWith("Utils")
                || className.endsWith("Client")) {
            return GenericExceptionCandidate.Layer.INFRA_UTIL;
        }

        return GenericExceptionCandidate.Layer.OTHER;
    }
}
