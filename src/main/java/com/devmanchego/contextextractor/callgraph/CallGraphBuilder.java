package com.devmanchego.contextextractor.callgraph;

import com.devmanchego.contextextractor.java.extractor.SpringEndpointExtractor;
import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.ScheduledJobInfo;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the call graph for one API endpoint, following method calls
 * up to {@link #MAX_DEPTH} levels deep and detecting cycles.
 *
 * Produces a tree of {@link CallNode}s rooted at the controller method.
 */
public final class CallGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(CallGraphBuilder.class);
    static final int MAX_DEPTH = 10;

    /** All parsed compilation units from the Java project (needed for cross-class resolution). */
    private final Map<String, ClassOrInterfaceDeclaration> classIndex;
    /** Set of warnings about cycles detected during traversal. */
    private final List<String> cycleWarnings = new ArrayList<>();
    private final ConditionExtractor conditionExtractor;

    public CallGraphBuilder(List<CompilationUnit> allCUs) {
        this.classIndex = buildClassIndex(allCUs);
        this.conditionExtractor = new ConditionExtractor(classIndex);
    }

    /**
     * Builds the call graph rooted at the controller method for the given endpoint.
     * Returns {@link Optional#empty()} when the controller class or method cannot be resolved.
     */
    public Optional<CallNode> build(EndpointInfo endpoint) {
        String controllerFqn = endpoint.getControllerClass();
        String methodName = endpoint.getMethodName();

        ClassOrInterfaceDeclaration controllerClass = findClass(controllerFqn);
        if (controllerClass == null) {
            log.debug("Cannot resolve class {} for call graph.", controllerFqn);
            return Optional.empty();
        }

        Optional<MethodDeclaration> method = findMethod(controllerClass, methodName);
        if (method.isEmpty()) {
            log.debug("Cannot resolve method {}#{} for call graph.", controllerFqn, methodName);
            return Optional.empty();
        }

        Set<String> visited = new LinkedHashSet<>();
        CallNode root = traverse(controllerClass, method.get(), CallNode.Role.CONTROLLER,
                visited, 0, controllerFqn);
        return Optional.of(root);
    }

    /** Builds the call graph rooted at a {@code @Scheduled} job method. */
    public Optional<CallNode> buildForJob(ScheduledJobInfo job) {
        ClassOrInterfaceDeclaration cls = findClass(job.getClassName());
        if (cls == null) {
            log.debug("Cannot resolve class {} for scheduled job call graph.", job.getClassName());
            return Optional.empty();
        }
        Optional<MethodDeclaration> method = findMethod(cls, job.getMethodName());
        if (method.isEmpty()) {
            log.debug("Cannot resolve method {}#{} for scheduled job call graph.",
                    job.getClassName(), job.getMethodName());
            return Optional.empty();
        }
        Set<String> visited = new LinkedHashSet<>();
        CallNode root = traverse(cls, method.get(), CallNode.Role.SERVICE, visited, 0, job.getClassName());
        return Optional.of(root);
    }

    public List<String> getCycleWarnings() {
        return Collections.unmodifiableList(cycleWarnings);
    }

    // -----------------------------------------------------------------------
    // Traversal
    // -----------------------------------------------------------------------

    private CallNode traverse(ClassOrInterfaceDeclaration classDecl,
                               MethodDeclaration method,
                               CallNode.Role role,
                               Set<String> visited,
                               int depth,
                               String classFqn) {
        String nodeKey = classFqn + "#" + method.getNameAsString();
        boolean transactional = hasTransactional(classDecl, method);
        String returnType = SpringEndpointExtractor.unwrapReturnType(method.getTypeAsString());

        if (depth >= MAX_DEPTH) {
            log.debug("Max depth {} reached at {}", MAX_DEPTH, nodeKey);
            return leaf(shortClassName(classFqn), method.getNameAsString(), role, transactional, returnType);
        }

        if (visited.contains(nodeKey)) {
            String warning = "Cycle detected in call graph: " + nodeKey + " (path: " + String.join(" → ", visited) + ")";
            cycleWarnings.add(warning);
            log.warn(warning);
            return leaf(shortClassName(classFqn), method.getNameAsString(), role, transactional, returnType);
        }

        visited.add(nodeKey);
        List<CallNode> callees = new ArrayList<>();

        // Build name → declaredTypeName map covering three scopes:
        //   1. Class fields   (e.g. private IngestionService ingestionService)
        //   2. Method params  (e.g. void process(ReportService svc))
        //   3. Local variables (e.g. UserService us = ...; us.doSomething())
        // This lets us resolve "ingestionService.upload()" → "IngestionService"
        // without full symbol resolution.
        Map<String, String> fieldTypes = buildScopeTypeMap(classDecl, method);

        // Find all method calls in this method's body
        method.findAll(MethodCallExpr.class).forEach(callExpr -> {
            String calledMethodName = callExpr.getNameAsString();

            callExpr.getScope().ifPresent(scope -> {
                // Handles "this.service", "service", "obj.service" → last identifier
                String scopeStr  = scope.toString();
                String lastIdent = scopeStr.contains(".")
                        ? scopeStr.substring(scopeStr.lastIndexOf('.') + 1)
                        : scopeStr;

                // Look up the declared type of the field (e.g. "IngestionService")
                String declaredType = fieldTypes.get(lastIdent);
                if (declaredType == null) return;

                Map.Entry<String, ClassOrInterfaceDeclaration> entry = findClassEntry(declaredType);
                if (entry == null) return;

                findMethod(entry.getValue(), calledMethodName).ifPresent(calledMethodDecl -> {
                    CallNode.Role calleeRole = detectRole(entry.getValue());
                    Set<String> childVisited = new LinkedHashSet<>(visited);
                    callees.add(traverse(entry.getValue(), calledMethodDecl,
                            calleeRole, childVisited, depth + 1, entry.getKey()));
                });
            });
        });

        visited.remove(nodeKey);

        List<FlowCondition> conditions    = conditionExtractor.extract(method, fieldTypes);
        List<FlowCondition> allConditions = conditionExtractor.extractAll(method, fieldTypes);

        List<String> implementedInterfaces = classDecl.getImplementedTypes().stream()
                .map(t -> t.getNameAsString())
                .collect(java.util.stream.Collectors.toList());

        String superClass = classDecl.getExtendedTypes().stream()
                .map(t -> t.getNameAsString())
                .filter(name -> !name.equals("Object"))
                .findFirst()
                .orElse(null);

        return new CallNode(shortClassName(classFqn), method.getNameAsString(),
                role, transactional, returnType, implementedInterfaces, superClass,
                conditions, allConditions, callees);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean hasTransactional(ClassOrInterfaceDeclaration classDecl, MethodDeclaration method) {
        boolean onMethod = method.getAnnotationByName("Transactional").isPresent();
        boolean onClass = classDecl.getAnnotationByName("Transactional").isPresent();
        return onMethod || onClass;
    }

    private CallNode.Role detectRole(ClassOrInterfaceDeclaration cls) {
        if (cls.getAnnotationByName("Repository").isPresent()) return CallNode.Role.REPOSITORY;
        if (cls.getAnnotationByName("Mapper").isPresent())     return CallNode.Role.MAPPER;
        if (cls.getAnnotationByName("Service").isPresent())    return CallNode.Role.SERVICE;
        if (cls.getAnnotationByName("Component").isPresent())  return CallNode.Role.SERVICE;
        String name = cls.getNameAsString();
        if (name.endsWith("Validator") || name.endsWith("Validation")) return CallNode.Role.VALIDATOR;
        if (name.endsWith("Repository") || name.endsWith("Repo")) return CallNode.Role.REPOSITORY;
        if (name.endsWith("Service") || name.endsWith("ServiceImpl")) return CallNode.Role.SERVICE;
        if (name.endsWith("Mapper")) return CallNode.Role.MAPPER;
        return CallNode.Role.UNKNOWN;
    }

    private Optional<MethodDeclaration> findMethod(ClassOrInterfaceDeclaration cls, String name) {
        return cls.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(name))
                .findFirst();
    }

    private ClassOrInterfaceDeclaration findClass(String fqn) {
        Map.Entry<String, ClassOrInterfaceDeclaration> entry = findClassEntry(fqn);
        return entry != null ? entry.getValue() : null;
    }

    /**
     * Returns the index entry (FQN → class) for a given name.
     * Tries exact FQN match first, then falls back to simple name match.
     */
    private Map.Entry<String, ClassOrInterfaceDeclaration> findClassEntry(String name) {
        if (classIndex.containsKey(name)) return Map.entry(name, classIndex.get(name));
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return classIndex.entrySet().stream()
                .filter(e -> e.getValue().getNameAsString().equals(simple))
                .findFirst()
                .orElse(null);
    }

    /**
     * Builds a name → declared type map covering three scopes:
     * <ol>
     *   <li>Class fields — {@code private IngestionService ingestionService}</li>
     *   <li>Method parameters — {@code void process(ReportService svc)}</li>
     *   <li>Local variable declarations — {@code UserService us = ...}</li>
     * </ol>
     * Primitive types, {@code String}, and standard collection/framework types are
     * excluded from scope 3 so DTOs and value objects are not mistaken for services.
     */
    private Map<String, String> buildScopeTypeMap(ClassOrInterfaceDeclaration cls,
                                                   MethodDeclaration method) {
        Map<String, String> map = new LinkedHashMap<>();

        // 1. Class fields
        cls.getFields().forEach(fd -> {
            String typeName = fd.getElementType().asString();
            fd.getVariables().forEach(var -> map.put(var.getNameAsString(), typeName));
        });

        // 2. Method parameters
        method.getParameters().forEach(param ->
                map.put(param.getNameAsString(), param.getType().asString()));

        // 3. Local variable declarations — only include types that could be services
        //    (i.e., types present in our class index); skip primitives and well-known types.
        method.findAll(VariableDeclarationExpr.class).forEach(vde -> {
            String typeName = vde.getElementType().asString();
            if (!isExcludedLocalType(typeName) && findClassEntry(typeName) != null) {
                vde.getVariables().forEach(var -> map.putIfAbsent(var.getNameAsString(), typeName));
            }
        });

        return map;
    }

    private static final Set<String> EXCLUDED_LOCAL_TYPES = Set.of(
            "String", "int", "long", "double", "float", "boolean", "byte", "short", "char",
            "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "List", "Map", "Set", "Collection", "Optional", "var",
            "ResponseEntity", "Mono", "Flux", "CompletableFuture",
            "Object", "void", "Void");

    private boolean isExcludedLocalType(String typeName) {
        // Strip generic parameters: List<String> → List
        int angle = typeName.indexOf('<');
        String base = angle >= 0 ? typeName.substring(0, angle).trim() : typeName.trim();
        return EXCLUDED_LOCAL_TYPES.contains(base);
    }

    private String shortClassName(String fqn) {
        if (!fqn.contains(".")) return fqn;
        String[] parts = fqn.split("\\.");
        return parts.length >= 2
                ? parts[parts.length - 2] + "." + parts[parts.length - 1]
                : parts[parts.length - 1];
    }

    private CallNode leaf(String className, String methodName, CallNode.Role role,
                          boolean transactional, String returnType) {
        return new CallNode(className, methodName, role, transactional, returnType,
                            List.of(), null, List.of(), List.of());
    }

    private Map<String, ClassOrInterfaceDeclaration> buildClassIndex(List<CompilationUnit> cus) {
        Map<String, ClassOrInterfaceDeclaration> index = new LinkedHashMap<>();
        for (CompilationUnit cu : cus) {
            String pkg = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString() + ".")
                    .orElse("");
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String fqn = pkg + cls.getNameAsString();
                index.put(fqn, cls);
            });
        }
        return index;
    }
}
