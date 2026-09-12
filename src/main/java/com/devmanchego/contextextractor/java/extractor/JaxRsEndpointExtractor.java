package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import java.util.*;

/**
 * Extracts HTTP endpoints from JAX-RS / Jakarta REST resources.
 *
 * Covers: Jersey, RESTEasy, Quarkus, Apache CXF.
 * Detection: @Path (class + method), @GET/@POST/@PUT/@DELETE/@PATCH.
 * Body parameter: first parameter without @PathParam/@QueryParam/@HeaderParam.
 * Unwraps: Response, CompletionStage<T>.
 */
public final class JaxRsEndpointExtractor implements EndpointExtractor {

    private final Map<String, String> constants;

    public JaxRsEndpointExtractor() { this(Map.of()); }

    public JaxRsEndpointExtractor(Map<String, String> constants) {
        this.constants = constants;
    }

    private static final Set<String> EXCLUDE_PARAMS = Set.of(
            "PathParam", "QueryParam", "HeaderParam", "CookieParam",
            "FormParam", "Context", "MatrixParam", "BeanParam"
    );

    private static final Map<String, HttpVerb> HTTP_ANNOTATIONS = Map.of(
            "GET", HttpVerb.GET,
            "POST", HttpVerb.POST,
            "PUT", HttpVerb.PUT,
            "DELETE", HttpVerb.DELETE,
            "PATCH", HttpVerb.PATCH
    );

    @Override
    public List<EndpointInfo> extract(CompilationUnit cu, String sourceFilePath) {
        List<EndpointInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            Optional<AnnotationExpr> classPathAnn = classDecl.getAnnotationByName("Path");
            if (classPathAnn.isEmpty()) return;

            String classPath = extractAnnotationValue(classPathAnn.get());
            String fqn = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString() + "." + classDecl.getNameAsString())
                    .orElse(classDecl.getNameAsString());

            classDecl.getMethods().forEach(method -> {
                HttpVerb verb = detectVerb(method.getAnnotations());
                if (verb == null) return;

                String methodPath = method.getAnnotationByName("Path")
                        .map(this::extractAnnotationValue)
                        .orElse("");

                String fullPath = normalizePath(classPath + "/" + methodPath);
                String bodyType = extractBodyParameter(method);
                String returnType = unwrapReturnType(method.getTypeAsString());

                EndpointInfo.Builder builder = EndpointInfo.builder()
                        .httpVerb(verb)
                        .pathTemplate(fullPath)
                        .bodyParameterType(bodyType)
                        .responseType(returnType)
                        .controllerClass(fqn)
                        .methodName(method.getNameAsString())
                        .sourceFile(sourceFilePath)
                        .framework("JaxRs");

                SecurityAnnotationExtractor.attach(
                        classDecl.getAnnotations(), method.getAnnotations(), builder);
                result.add(builder.build());
            });
        });

        return result;
    }

    private HttpVerb detectVerb(List<AnnotationExpr> annotations) {
        return annotations.stream()
                .map(a -> HTTP_ANNOTATIONS.get(a.getNameAsString()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String extractAnnotationValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return unquote(single.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
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

    /** Body parameter = first parameter without any JAX-RS binding annotation. */
    private String extractBodyParameter(MethodDeclaration method) {
        return method.getParameters().stream()
                .filter(p -> p.getAnnotations().stream()
                        .noneMatch(a -> EXCLUDE_PARAMS.contains(a.getNameAsString())))
                .findFirst()
                .map(Parameter::getTypeAsString)
                .orElse(null);
    }

    /**
     * Unwraps JAX-RS response wrappers:
     * Response → null (opaque), CompletionStage<T> → T
     */
    static String unwrapReturnType(String type) {
        if (type == null) return null;
        String t = type.trim();
        if ("Response".equals(t) || "javax.ws.rs.core.Response".equals(t)
                || "jakarta.ws.rs.core.Response".equals(t)) {
            return null;
        }
        for (String wrapper : new String[]{"CompletionStage", "Future"}) {
            if (t.startsWith(wrapper + "<") && t.endsWith(">")) {
                return unwrapReturnType(t.substring(wrapper.length() + 1, t.length() - 1));
            }
        }
        return t;
    }

}
