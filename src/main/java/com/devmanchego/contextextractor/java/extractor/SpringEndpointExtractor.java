package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.util.*;

/**
 * Extracts HTTP endpoints from Spring MVC / Spring WebFlux controllers.
 *
 * Handles:
 * - @RestController + @RequestMapping (class-level prefix)
 * - @GetMapping, @PostMapping, @PutMapping, @DeleteMapping, @PatchMapping
 * - ResponseEntity<T>, Mono<T>, Flux<T>, List<T>, Page<T> unwrapping
 */
public final class SpringEndpointExtractor implements EndpointExtractor {

    private final Map<String, String> constants;

    public SpringEndpointExtractor() { this(Map.of()); }

    public SpringEndpointExtractor(Map<String, String> constants) {
        this.constants = constants;
    }

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller"
    );

    private static final Map<String, HttpVerb> METHOD_MAPPING_ANNOTATIONS = Map.of(
            "GetMapping", HttpVerb.GET,
            "PostMapping", HttpVerb.POST,
            "PutMapping", HttpVerb.PUT,
            "DeleteMapping", HttpVerb.DELETE,
            "PatchMapping", HttpVerb.PATCH
    );

    @Override
    public List<EndpointInfo> extract(CompilationUnit cu, String sourceFilePath) {
        List<EndpointInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            if (!hasAnyAnnotation(classDecl.getAnnotations(), CONTROLLER_ANNOTATIONS)) {
                return;
            }
            String fqn = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString() + "." + classDecl.getNameAsString())
                    .orElse(classDecl.getNameAsString());

            // @Controller without @RestController serves views (HTML/SPA forward) — mark as static routes
            boolean isSpaController = hasAnnotation(classDecl.getAnnotations(), "Controller")
                    && !hasAnnotation(classDecl.getAnnotations(), "RestController");

            String classPrefix = extractPathFromAnnotation(classDecl.getAnnotations(), "RequestMapping");

            classDecl.getMethods().forEach(method -> {
                for (Map.Entry<String, HttpVerb> entry : METHOD_MAPPING_ANNOTATIONS.entrySet()) {
                    if (hasAnnotation(method.getAnnotations(), entry.getKey())) {
                        String methodPath = extractPathFromAnnotation(method.getAnnotations(), entry.getKey());
                        String fullPath = normalizePath(classPrefix + "/" + methodPath);

                        String bodyType = extractBodyParameter(method);
                        String returnType = unwrapReturnType(method.getTypeAsString());

                        EndpointInfo.Builder builder = EndpointInfo.builder()
                                .httpVerb(entry.getValue())
                                .pathTemplate(fullPath)
                                .bodyParameterType(bodyType)
                                .responseType(returnType)
                                .controllerClass(fqn)
                                .methodName(method.getNameAsString())
                                .sourceFile(sourceFilePath)
                                .framework("Spring")
                                .staticRoute(isSpaController);

                        SecurityAnnotationExtractor.attach(
                                classDecl.getAnnotations(), method.getAnnotations(), builder);
                        result.add(builder.build());
                        break;
                    }
                }
                // Handle @RequestMapping(method = RequestMethod.XXX) on methods
                extractRequestMappingMethod(method, classDecl.getAnnotations(), classPrefix, fqn,
                        sourceFilePath, isSpaController)
                        .ifPresent(result::add);
            });
        });

        return result;
    }

    private Optional<EndpointInfo> extractRequestMappingMethod(MethodDeclaration method,
                                                                List<AnnotationExpr> classAnnotations,
                                                                String classPrefix,
                                                                String fqn,
                                                                String sourceFilePath,
                                                                boolean isSpaController) {
        Optional<AnnotationExpr> ann = method.getAnnotationByName("RequestMapping");
        if (ann.isEmpty()) return Optional.empty();

        HttpVerb verb = extractHttpMethod(ann.get());
        if (verb == null) verb = HttpVerb.GET; // default

        String path = extractPathFromAnnotation(method.getAnnotations(), "RequestMapping");
        String fullPath = normalizePath(classPrefix + "/" + path);

        EndpointInfo.Builder builder = EndpointInfo.builder()
                .httpVerb(verb)
                .pathTemplate(fullPath)
                .bodyParameterType(extractBodyParameter(method))
                .responseType(unwrapReturnType(method.getTypeAsString()))
                .controllerClass(fqn)
                .methodName(method.getNameAsString())
                .sourceFile(sourceFilePath)
                .framework("Spring")
                .staticRoute(isSpaController);

        SecurityAnnotationExtractor.attach(classAnnotations, method.getAnnotations(), builder);
        return Optional.of(builder.build());
    }

    private HttpVerb extractHttpMethod(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("method".equals(pair.getNameAsString())) {
                    String val = pair.getValue().toString();
                    if (val.contains("POST")) return HttpVerb.POST;
                    if (val.contains("PUT")) return HttpVerb.PUT;
                    if (val.contains("DELETE")) return HttpVerb.DELETE;
                    if (val.contains("PATCH")) return HttpVerb.PATCH;
                    return HttpVerb.GET;
                }
            }
        }
        return null;
    }

    private boolean hasAnyAnnotation(List<AnnotationExpr> annotations, Set<String> names) {
        return annotations.stream().anyMatch(a -> names.contains(a.getNameAsString()));
    }

    private boolean hasAnnotation(List<AnnotationExpr> annotations, String name) {
        return annotations.stream().anyMatch(a -> name.equals(a.getNameAsString()));
    }

    private String extractPathFromAnnotation(List<AnnotationExpr> annotations, String annotationName) {
        return annotations.stream()
                .filter(a -> annotationName.equals(a.getNameAsString()))
                .findFirst()
                .map(this::extractValue)
                .orElse("");
    }

    private String extractValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return unquote(single.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()) || "path".equals(p.getNameAsString()))
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
        // Collapse double slashes, ensure leading slash
        String p = raw.replaceAll("/+", "/");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private String extractBodyParameter(MethodDeclaration method) {
        return method.getParameters().stream()
                .filter(p -> p.getAnnotations().stream()
                        .anyMatch(a -> "RequestBody".equals(a.getNameAsString())))
                .findFirst()
                .map(p -> unwrapType(p.getTypeAsString()))
                .orElse(null);
    }

    /**
     * Unwraps Spring/Reactor wrapper types:
     * ResponseEntity<T> → T, Mono<T> → T, Flux<T> → T[], List<T> → T[], Page<T> → T[]
     */
    public static String unwrapReturnType(String type) {
        if (type == null) return null;
        String t = type.trim();
        for (String wrapper : new String[]{"ResponseEntity", "Mono"}) {
            if (t.startsWith(wrapper + "<") && t.endsWith(">")) {
                return unwrapReturnType(t.substring(wrapper.length() + 1, t.length() - 1));
            }
        }
        for (String listWrapper : new String[]{"Flux", "List", "Page"}) {
            if (t.startsWith(listWrapper + "<") && t.endsWith(">")) {
                String inner = unwrapReturnType(t.substring(listWrapper.length() + 1, t.length() - 1));
                return inner + "[]";
            }
        }
        return t;
    }

    private String unwrapType(String type) {
        return unwrapReturnType(type);
    }

}
