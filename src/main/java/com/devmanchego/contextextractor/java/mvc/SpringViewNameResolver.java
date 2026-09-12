package com.devmanchego.contextextractor.java.mvc;

import com.devmanchego.contextextractor.java.extractor.AnnotationValueResolver;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 07: finds Spring MVC controller methods that return a view name rather than a serialised
 * body, and reconstructs each one's view-name template statically — the exact counterpart to the
 * naming convention {@code JspRouteReconstructor} falls back to on the frontend side alone.
 *
 * <p>A method is view-returning when its declaring class carries {@code @Controller} (not
 * {@code @RestController}) and neither the class nor the method carries {@code @ResponseBody},
 * and the method itself returns {@code String}. A {@code @Controller} method returning
 * {@code void} (writing a file to the response directly, say) is not a view — it is simply
 * skipped, not reported, since it was never a candidate.
 *
 * <p>The view name is read from every {@code return} statement in the method body: a string
 * literal, a reference to a {@code static final String} field (resolved the same way
 * {@link AnnotationValueResolver} resolves annotation values), or a {@code +}-concatenation of
 * those with a {@code @PathVariable} parameter — which becomes a {@code {name}} placeholder using
 * that parameter's bound URL segment name, so the view template composes directly with the URL
 * template's own placeholders. A return expression built any other way (a method call, a field of
 * a runtime object) is reported unresolved with the reason, never guessed.
 */
public final class SpringViewNameResolver {

    private static final Set<String> METHOD_MAPPING_ANNOTATIONS =
            Set.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");
    private static final Map<String, HttpVerb> METHOD_MAPPING_VERBS = Map.of(
            "GetMapping", HttpVerb.GET, "PostMapping", HttpVerb.POST, "PutMapping", HttpVerb.PUT,
            "DeleteMapping", HttpVerb.DELETE, "PatchMapping", HttpVerb.PATCH);

    /**
     * @param urlTemplate       Spring path template, canonical {@code {name}} placeholders
     * @param viewNameTemplates every distinct view name this method can return, empty when none resolved
     * @param unresolvedReturns return statements this method has that could not be resolved (may
     *                          coexist with non-empty {@code viewNameTemplates} — an if/else with
     *                          one literal branch and one computed one)
     */
    public record ViewRoute(HttpVerb verb, String urlTemplate, List<String> viewNameTemplates,
                            String controllerClass, String methodName, String sourceFile,
                            List<String> unresolvedReturns) {
        public ViewRoute {
            viewNameTemplates = List.copyOf(viewNameTemplates);
            unresolvedReturns = List.copyOf(unresolvedReturns);
        }

        public boolean resolved() { return !viewNameTemplates.isEmpty(); }
    }

    /** {@code prefix + viewName + suffix} = the JSP file; {@code assumedDefault} when no bean configuring it was found. */
    public record ViewResolverConfig(String prefix, String suffix, boolean assumedDefault) {
        public static final ViewResolverConfig DEFAULT = new ViewResolverConfig("/WEB-INF/jsp/", ".jsp", true);
    }

    public List<ViewRoute> extract(List<CompilationUnit> allCUs) {
        Map<String, String> constants = AnnotationValueResolver.collectConstants(allCUs);
        List<ViewRoute> routes = new ArrayList<>();
        for (CompilationUnit cu : allCUs) {
            String sourceFile = cu.getStorage().map(s -> s.getPath().toString()).orElse(null);
            for (ClassOrInterfaceDeclaration classDecl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!hasAnnotation(classDecl.getAnnotations(), "Controller")
                        || hasAnnotation(classDecl.getAnnotations(), "RestController")
                        || hasAnnotation(classDecl.getAnnotations(), "ResponseBody")) {
                    continue;
                }
                String fqn = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString() + "." + classDecl.getNameAsString())
                        .orElse(classDecl.getNameAsString());
                String classPrefix = extractPath(classDecl.getAnnotations(), "RequestMapping");

                for (MethodDeclaration method : classDecl.getMethods()) {
                    extractRoute(method, classDecl, classPrefix, fqn, sourceFile, constants).ifPresent(routes::add);
                }
            }
        }
        return routes;
    }

    private Optional<ViewRoute> extractRoute(MethodDeclaration method, ClassOrInterfaceDeclaration classDecl,
                                             String classPrefix, String fqn, String sourceFile,
                                             Map<String, String> constants) {
        if (hasAnnotation(method.getAnnotations(), "ResponseBody")) return Optional.empty();
        if (!"String".equals(method.getTypeAsString())) return Optional.empty();

        HttpVerb verb = null;
        String methodPath = null;
        for (String ann : METHOD_MAPPING_ANNOTATIONS) {
            if (hasAnnotation(method.getAnnotations(), ann)) {
                verb = METHOD_MAPPING_VERBS.get(ann);
                methodPath = extractPath(method.getAnnotations(), ann);
                break;
            }
        }
        if (verb == null) {
            Optional<AnnotationExpr> rm = method.getAnnotationByName("RequestMapping");
            if (rm.isEmpty()) return Optional.empty(); // no HTTP mapping at all — not an endpoint
            verb = extractHttpVerb(rm.get());
            methodPath = extractPath(method.getAnnotations(), "RequestMapping");
        }
        String urlTemplate = normalizePath(classPrefix + "/" + methodPath);

        if (method.getBody().isEmpty()) {
            return Optional.of(new ViewRoute(verb, urlTemplate, List.of(), fqn, method.getNameAsString(), sourceFile,
                    List.of("no method body")));
        }

        Map<String, String> pathVariableSegments = pathVariableBindings(method);
        Map<String, String> classConstants = localFieldConstants(classDecl, constants);

        List<String> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (ReturnStmt rs : method.getBody().get().findAll(ReturnStmt.class)) {
            if (rs.findAncestor(LambdaExpr.class).isPresent()) continue; // not this method's own control flow
            if (rs.getExpression().isEmpty()) continue;
            Optional<String> viewName = resolveViewName(rs.getExpression().get(), pathVariableSegments, classConstants);
            if (viewName.isPresent()) {
                if (!resolved.contains(viewName.get())) resolved.add(viewName.get());
            } else {
                unresolved.add(rs.getExpression().get().toString());
            }
        }
        if (resolved.isEmpty() && unresolved.isEmpty()) return Optional.empty(); // no return with an expression

        return Optional.of(new ViewRoute(verb, urlTemplate, resolved, fqn, method.getNameAsString(), sourceFile, unresolved));
    }

    /** The URL segment name each {@code @PathVariable} parameter is bound to, keyed by its Java identifier. */
    private static Map<String, String> pathVariableBindings(MethodDeclaration method) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (Parameter p : method.getParameters()) {
            for (AnnotationExpr a : p.getAnnotations()) {
                if (!"PathVariable".equals(a.getNameAsString())) continue;
                String bound = extractSingleValue(a).orElse(p.getNameAsString());
                bindings.put(p.getNameAsString(), bound);
            }
        }
        return bindings;
    }

    /** Static final String fields of this class, added to the shared constant map under their bare name. */
    private static Map<String, String> localFieldConstants(ClassOrInterfaceDeclaration classDecl, Map<String, String> shared) {
        Map<String, String> merged = new LinkedHashMap<>(shared);
        for (FieldDeclaration field : classDecl.getFields()) {
            if (!field.isStatic() || !field.isFinal()) continue;
            for (VariableDeclarator v : field.getVariables()) {
                v.getInitializer().filter(Expression::isStringLiteralExpr)
                        .ifPresent(init -> merged.putIfAbsent(v.getNameAsString(), init.asStringLiteralExpr().asString()));
            }
        }
        return merged;
    }

    /** {@code null} on failure, else a {@code {segmentName}}-templated view name. */
    private static Optional<String> resolveViewName(Expression expr, Map<String, String> pathVars, Map<String, String> constants) {
        Expression e = unwrap(expr);
        if (e.isStringLiteralExpr()) return Optional.of(e.asStringLiteralExpr().asString());

        if (e.isNameExpr()) {
            String name = e.asNameExpr().getNameAsString();
            if (pathVars.containsKey(name)) return Optional.of("{" + pathVars.get(name) + "}");
            String constant = constants.get(name);
            return constant != null ? Optional.of(constant) : Optional.empty();
        }
        if (e.isFieldAccessExpr()) {
            FieldAccessExpr fa = e.asFieldAccessExpr();
            String key = fa.getScope() + "." + fa.getNameAsString();
            String constant = constants.getOrDefault(key, constants.get(fa.getNameAsString()));
            return constant != null ? Optional.of(constant) : Optional.empty();
        }
        if (e.isBinaryExpr() && e.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            BinaryExpr b = e.asBinaryExpr();
            Optional<String> left = resolveViewName(b.getLeft(), pathVars, constants);
            Optional<String> right = resolveViewName(b.getRight(), pathVars, constants);
            if (left.isEmpty() || right.isEmpty()) return Optional.empty();
            return Optional.of(left.get() + right.get());
        }
        return Optional.empty();
    }

    private static Expression unwrap(Expression e) {
        return e.isEnclosedExpr() ? unwrap(((EnclosedExpr) e).getInner()) : e;
    }

    /**
     * Best-effort discovery of an {@code InternalResourceViewResolver}'s {@code setPrefix}/
     * {@code setSuffix} calls anywhere in the backend — a config class typically builds it as a
     * {@code @Bean}. Falls back to the conventional {@code WEB-INF/jsp/} / {@code .jsp} pair
     * (the same one {@code JspRouteReconstructor}'s naming-convention fallback already assumes)
     * when no such configuration is found, flagged as assumed rather than confirmed.
     */
    public ViewResolverConfig resolveConfig(List<CompilationUnit> allCUs) {
        String prefix = null;
        String suffix = null;
        for (CompilationUnit cu : allCUs) {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                if (call.getArguments().size() != 1) continue;
                Expression arg = call.getArgument(0);
                if (!arg.isStringLiteralExpr()) continue;
                if ("setPrefix".equals(name)) prefix = arg.asStringLiteralExpr().asString();
                else if ("setSuffix".equals(name)) suffix = arg.asStringLiteralExpr().asString();
            }
        }
        if (prefix == null || suffix == null) return ViewResolverConfig.DEFAULT;
        return new ViewResolverConfig(prefix, suffix, false);
    }

    // -----------------------------------------------------------------------
    // Annotation / path helpers (self-contained — same shapes SpringEndpointExtractor reads)
    // -----------------------------------------------------------------------

    private static boolean hasAnnotation(NodeList<AnnotationExpr> annotations, String name) {
        return annotations.stream().anyMatch(a -> name.equals(a.getNameAsString()));
    }

    private static String extractPath(NodeList<AnnotationExpr> annotations, String annotationName) {
        return annotations.stream()
                .filter(a -> annotationName.equals(a.getNameAsString()))
                .findFirst()
                .flatMap(SpringViewNameResolver::extractSingleValue)
                .orElse("");
    }

    private static Optional<String> extractSingleValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return Optional.of(unquote(single.getMemberValue().toString()));
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("value".equals(pair.getNameAsString()) || "path".equals(pair.getNameAsString())) {
                    return Optional.of(unquote(pair.getValue().toString()));
                }
            }
        }
        return Optional.empty();
    }

    private static String unquote(String literal) {
        String s = literal.strip();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static HttpVerb extractHttpVerb(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (!"method".equals(pair.getNameAsString())) continue;
                String val = pair.getValue().toString();
                if (val.contains("POST")) return HttpVerb.POST;
                if (val.contains("PUT")) return HttpVerb.PUT;
                if (val.contains("DELETE")) return HttpVerb.DELETE;
                if (val.contains("PATCH")) return HttpVerb.PATCH;
                return HttpVerb.GET;
            }
        }
        return HttpVerb.GET;
    }

    private static String normalizePath(String raw) {
        String p = raw.replaceAll("/+", "/");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
