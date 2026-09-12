package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts HTTP endpoints from Micronaut controllers.
 *
 * Handles:
 * - @Controller (class-level prefix) + @Get/@Post/@Put/@Delete/@Patch
 * - Path variable binding: explicit @PathVariable or implicit name-match against {placeholder}
 * - Unwraps HttpResponse<T>
 */
public final class MicronautEndpointExtractor implements EndpointExtractor {

    private final Map<String, String> constants;

    public MicronautEndpointExtractor() { this(Map.of()); }

    public MicronautEndpointExtractor(Map<String, String> constants) {
        this.constants = constants;
    }

    private static final Map<String, HttpVerb> METHOD_ANNOTATIONS = Map.of(
            "Get", HttpVerb.GET,
            "Post", HttpVerb.POST,
            "Put", HttpVerb.PUT,
            "Delete", HttpVerb.DELETE,
            "Patch", HttpVerb.PATCH
    );

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\w+)}");

    @Override
    public List<EndpointInfo> extract(CompilationUnit cu, String sourceFilePath) {
        List<EndpointInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            if (classDecl.getAnnotationByName("Controller").isEmpty()) return;

            String classPrefix = classDecl.getAnnotationByName("Controller")
                    .map(this::extractValue)
                    .orElse("");

            String fqn = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString() + "." + classDecl.getNameAsString())
                    .orElse(classDecl.getNameAsString());

            classDecl.getMethods().forEach(method -> {
                HttpVerb verb = detectVerb(method.getAnnotations());
                if (verb == null) return;

                String methodPath = getMethodPath(method);
                String fullPath = normalizePath(classPrefix + "/" + methodPath);

                String bodyType = extractBodyParam(method, fullPath);
                String returnType = unwrapReturnType(method.getTypeAsString());

                EndpointInfo.Builder builder = EndpointInfo.builder()
                        .httpVerb(verb)
                        .pathTemplate(fullPath)
                        .bodyParameterType(bodyType)
                        .responseType(returnType)
                        .controllerClass(fqn)
                        .methodName(method.getNameAsString())
                        .sourceFile(sourceFilePath)
                        .framework("Micronaut");

                SecurityAnnotationExtractor.attach(
                        classDecl.getAnnotations(), method.getAnnotations(), builder);
                result.add(builder.build());
            });
        });

        return result;
    }

    private HttpVerb detectVerb(List<AnnotationExpr> annotations) {
        return annotations.stream()
                .map(a -> METHOD_ANNOTATIONS.get(a.getNameAsString()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String getMethodPath(MethodDeclaration method) {
        for (String annName : METHOD_ANNOTATIONS.keySet()) {
            Optional<AnnotationExpr> ann = method.getAnnotationByName(annName);
            if (ann.isPresent()) {
                return extractValue(ann.get());
            }
        }
        return "";
    }

    private String extractValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr s) {
            return unquote(s.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr n) {
            return n.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()))
                    .findFirst()
                    .map(p -> unquote(p.getValue().toString()))
                    .orElse("");
        }
        return "";
    }

    private String unquote(String s) {
        return AnnotationValueResolver.resolve(s, constants);
    }

    private String normalizePath(String raw) {
        String p = raw.replaceAll("/+", "/");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /**
     * Body parameter detection: parameters not bound to path placeholders and not
     * carrying Micronaut binding annotations (@PathVariable, @QueryValue, @Header).
     */
    private String extractBodyParam(MethodDeclaration method, String fullPath) {
        Set<String> pathVars = extractPlaceholders(fullPath);
        Set<String> bindingAnnotations = Set.of("PathVariable", "QueryValue", "Header",
                "CookieValue", "Part", "Body");

        for (Parameter param : method.getParameters()) {
            boolean hasBindingAnn = param.getAnnotations().stream()
                    .anyMatch(a -> bindingAnnotations.contains(a.getNameAsString()));
            if (hasBindingAnn) {
                // explicit @Body annotation = it IS the body
                boolean isExplicitBody = param.getAnnotations().stream()
                        .anyMatch(a -> "Body".equals(a.getNameAsString()));
                if (isExplicitBody) return param.getTypeAsString();
                continue;
            }
            // Implicit: if param name matches a path placeholder, it's a path var
            if (pathVars.contains(param.getNameAsString())) continue;
            return param.getTypeAsString();
        }
        return null;
    }

    private Set<String> extractPlaceholders(String pathTemplate) {
        Set<String> names = new HashSet<>();
        Matcher m = PLACEHOLDER_PATTERN.matcher(pathTemplate);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /** Unwraps HttpResponse<T> → T */
    static String unwrapReturnType(String type) {
        if (type == null) return null;
        String t = type.trim();
        if (t.startsWith("HttpResponse<") && t.endsWith(">")) {
            return unwrapReturnType(t.substring("HttpResponse<".length(), t.length() - 1));
        }
        return t;
    }

}
