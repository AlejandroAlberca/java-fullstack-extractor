package com.devmanchego.contextextractor.java.persistence;

import com.devmanchego.contextextractor.java.model.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Extracts JPA entity metadata and correlates DTO ↔ Entity field mappings.
 *
 * Handles: @Entity/@Table, @Column, @Id/@GeneratedValue, @Transient,
 * @OneToMany/@ManyToOne/@OneToOne/@ManyToMany/@JoinColumn,
 * @Embedded/@Embeddable (recursive dotted-path flattening),
 * @Mapper/@Mapping (MapStruct), @Repository/@Query.
 */
public final class PersistenceMappingExtractor {

    private static final Logger log = LoggerFactory.getLogger(PersistenceMappingExtractor.class);

    private static final Set<String> RELATION_ANNOTATIONS = Set.of(
            "OneToMany", "ManyToOne", "OneToOne", "ManyToMany"
    );

    // -----------------------------------------------------------------------
    // Entity extraction
    // -----------------------------------------------------------------------

    public Optional<EntityInfo> extractEntity(CompilationUnit cu, String sourceFile) {
        return extractEntity(cu, sourceFile, List.of(cu));
    }

    /**
     * @param allCUs every parsed compilation unit in the project, needed to resolve
     *               {@code @MappedSuperclass} ancestors and {@code @EmbeddedId} component
     *               classes that live in a different file from the entity itself. Pass just
     *               {@code List.of(cu)} when only same-file resolution is needed (e.g. tests).
     */
    public Optional<EntityInfo> extractEntity(CompilationUnit cu, String sourceFile,
                                              List<CompilationUnit> allCUs) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getAnnotationByName("Entity").isPresent())
                .findFirst()
                .map(c -> buildEntityInfo(c, cu, sourceFile, allCUs));
    }

    private EntityInfo buildEntityInfo(ClassOrInterfaceDeclaration classDecl,
                                       CompilationUnit cu, String sourceFile,
                                       List<CompilationUnit> allCUs) {
        String fqn = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + "." + classDecl.getNameAsString())
                .orElse(classDecl.getNameAsString());

        String tableName = classDecl.getAnnotationByName("Table")
                .flatMap(a -> extractAnnotationAttr(a, "name"))
                .orElse(toSnakeCase(classDecl.getNameAsString()));

        Map<String, ClassOrInterfaceDeclaration> classIndex = indexClassesByName(allCUs);
        Map<String, String> attributeOverrides = extractAttributeOverrides(classDecl);

        List<FieldInfo> fields = new ArrayList<>();
        List<RelationInfo> relations = new ArrayList<>();

        // Fields inherited from @MappedSuperclass ancestors (root-first) come before the
        // entity's own fields, so a reader sees the identifier first, matching how the table
        // actually reads — not "missing until column 2" the way an unresolved inherited @Id
        // used to render.
        fields.addAll(collectInheritedFields(classDecl, classIndex, attributeOverrides, new HashSet<>()));

        for (FieldDeclaration field : classDecl.getFields()) {
            if (field.isStatic()) continue; // e.g. serialVersionUID — a JVM implementation
            // detail, never a persistent (or derived-but-worth-showing) field. JPA itself
            // excludes static fields from entity state; this extractor previously didn't,
            // which is how a serialization constant ended up rendered as a database column.
            if (isRelationField(field)) {
                extractRelation(field).ifPresent(relations::add);
            } else if (field.getAnnotationByName("EmbeddedId").isPresent()) {
                // A composite primary key: flatten the @Embeddable class's own fields into this
                // entity's field list, each one marked as part of the primary key — never as a
                // derived/virtual attribute, which is what happened before this resolved the
                // embeddable's type at all.
                fields.addAll(flattenEmbeddedId(field, classIndex));
            } else if (field.getAnnotationByName("Embedded").isPresent()) {
                // Embedded handled by caller via @Embeddable flattening
                fields.addAll(flattenEmbedded(field, ""));
            } else {
                fields.add(applyAttributeOverride(buildFieldInfo(field, ""), attributeOverrides));
            }
        }

        String description = classDecl.getJavadocComment()
                .map(jc -> jc.parse().getDescription().toText().strip())
                .filter(s -> !s.isBlank())
                .orElse(null);

        return new EntityInfo(fqn, classDecl.getNameAsString(), tableName,
                sourceFile, fields, relations, List.of(), description);
    }

    // -----------------------------------------------------------------------
    // @MappedSuperclass inheritance (P3)
    // -----------------------------------------------------------------------

    /**
     * Builds a simple-name → declaration index across every parsed compilation unit, so a
     * {@code @MappedSuperclass} ancestor or {@code @EmbeddedId} component class declared in a
     * different file from the entity can still be resolved. Not scoped to {@code @Entity}
     * classes — mapped superclasses and embeddable ID classes carry neither annotation.
     */
    private Map<String, ClassOrInterfaceDeclaration> indexClassesByName(List<CompilationUnit> allCUs) {
        Map<String, ClassOrInterfaceDeclaration> result = new LinkedHashMap<>();
        for (CompilationUnit unit : allCUs) {
            for (ClassOrInterfaceDeclaration cls : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                result.putIfAbsent(cls.getNameAsString(), cls);
            }
        }
        return result;
    }

    /**
     * Walks the {@code extends} chain of {@code classDecl}, collecting the persistent fields of
     * every {@code @MappedSuperclass} ancestor (root-first), with any {@code @AttributeOverride}
     * declared on {@code classDecl} itself applied. A plain Java superclass with no
     * {@code @MappedSuperclass} annotation contributes nothing — it isn't part of JPA state.
     *
     * @param visited class simple names already walked in this call, guarding against a
     *                pathological cycle from simple-name collisions across packages
     */
    private List<FieldInfo> collectInheritedFields(ClassOrInterfaceDeclaration classDecl,
                                                    Map<String, ClassOrInterfaceDeclaration> classIndex,
                                                    Map<String, String> attributeOverrides,
                                                    Set<String> visited) {
        List<FieldInfo> result = new ArrayList<>();
        for (var parentType : classDecl.getExtendedTypes()) {
            String parentName = parentType.getNameAsString();
            if (!visited.add(parentName)) continue;

            ClassOrInterfaceDeclaration parentDecl = classIndex.get(parentName);
            if (parentDecl == null) continue; // not in this codebase (external library type) — nothing to inherit
            if (parentDecl.getAnnotationByName("MappedSuperclass").isEmpty()) continue;

            // Ancestors of this mapped superclass first (root-first ordering).
            result.addAll(collectInheritedFields(parentDecl, classIndex, attributeOverrides, visited));

            for (FieldDeclaration field : parentDecl.getFields()) {
                if (field.isStatic()) continue; // e.g. serialVersionUID — never persistent state
                if (isRelationField(field)) continue; // relations on mapped superclasses: out of scope
                if (field.getAnnotationByName("Embedded").isPresent()
                        || field.getAnnotationByName("EmbeddedId").isPresent()) {
                    continue; // embedded state on a mapped superclass: out of scope
                }

                FieldInfo fieldInfo = applyAttributeOverride(buildFieldInfo(field, ""), attributeOverrides);
                if (fieldInfo.isPrimaryKey()) {
                    fieldInfo = fieldInfo.withKeyOrigin(parentDecl.getNameAsString());
                }
                result.add(fieldInfo);
            }
        }
        return result;
    }

    /**
     * Parses {@code @AttributeOverride(name = "...", column = @Column(name = "..."))} and its
     * repeatable {@code @AttributeOverrides({...})} form, both declared directly on the entity
     * class. Returns attribute name → overriding column name.
     */
    private Map<String, String> extractAttributeOverrides(ClassOrInterfaceDeclaration classDecl) {
        Map<String, String> overrides = new LinkedHashMap<>();
        classDecl.getAnnotationByName("AttributeOverride")
                .ifPresent(ann -> addAttributeOverride(ann, overrides));

        classDecl.getAnnotationByName("AttributeOverrides").ifPresent(ann -> {
            if (ann instanceof SingleMemberAnnotationExpr single
                    && single.getMemberValue() instanceof ArrayInitializerExpr arr) {
                for (var value : arr.getValues()) {
                    if (value instanceof AnnotationExpr nested) addAttributeOverride(nested, overrides);
                }
            }
        });
        return overrides;
    }

    private void addAttributeOverride(AnnotationExpr ann, Map<String, String> overrides) {
        if (!(ann instanceof NormalAnnotationExpr normal)) return;
        String attrName = null;
        String columnName = null;
        for (MemberValuePair pair : normal.getPairs()) {
            if ("name".equals(pair.getNameAsString())) {
                attrName = unquote(pair.getValue().toString());
            } else if ("column".equals(pair.getNameAsString())
                    && pair.getValue() instanceof AnnotationExpr columnAnn) {
                columnName = extractAnnotationAttr(columnAnn, "name").orElse(null);
            }
        }
        if (attrName != null && columnName != null) overrides.put(attrName, columnName);
    }

    private FieldInfo applyAttributeOverride(FieldInfo field, Map<String, String> overrides) {
        String override = overrides.get(field.getName());
        return override != null ? field.withColumnName(override) : field;
    }

    /**
     * Flattens an {@code @EmbeddedId} composite key into one {@link FieldInfo} per component
     * field of the {@code @Embeddable} class, each marked as part of the primary key. If the
     * embeddable class isn't visible in this codebase, emits a single best-effort placeholder
     * so the table still has *a* primary key rather than none — the alternative of silently
     * classifying it as a derived attribute is exactly the defect this fixes.
     */
    private List<FieldInfo> flattenEmbeddedId(FieldDeclaration embeddedIdField,
                                              Map<String, ClassOrInterfaceDeclaration> classIndex) {
        String fieldName = embeddedIdField.getVariables().get(0).getNameAsString();
        String typeName = embeddedIdField.getElementType().asString();
        ClassOrInterfaceDeclaration embeddableDecl = classIndex.get(typeName);

        if (embeddableDecl == null) {
            log.debug("@EmbeddedId field '{}' has type '{}' not visible in this codebase — "
                    + "emitting a single placeholder primary key column.", fieldName, typeName);
            return List.of(FieldInfo.builder()
                    .name(fieldName)
                    .type(typeName + " (embedded id, component fields unresolved)")
                    .primaryKey(true)
                    .nullable(false)
                    .build());
        }

        List<FieldInfo> result = new ArrayList<>();
        for (FieldDeclaration componentField : embeddableDecl.getFields()) {
            if (componentField.isStatic()) continue; // e.g. serialVersionUID on the embeddable itself
            if (isRelationField(componentField)) continue; // relation inside a composite key: out of scope
            result.add(buildFieldInfo(componentField, fieldName, true));
        }
        return result;
    }

    private List<FieldInfo> flattenEmbedded(FieldDeclaration embeddedField, String prefix) {
        // Returns placeholder fields using dotted-path naming.
        // Full recursive flattening requires symbol resolution; we emit a best-effort result.
        String fieldName = embeddedField.getVariables().get(0).getNameAsString();
        String typeName = embeddedField.getElementType().asString();
        String path = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;

        log.debug("@Embedded field '{}' of type '{}' — flattening to dotted path '{}'",
                fieldName, typeName, path);

        // Return a single placeholder row; the renderer will mark it for further expansion
        return List.of(FieldInfo.builder()
                .name(path)
                .type(typeName + " (embedded)")
                .optional(true)
                .build());
    }

    private FieldInfo buildFieldInfo(FieldDeclaration field, String prefix) {
        return buildFieldInfo(field, prefix, false);
    }

    /**
     * @param forcePrimaryKey marks this field as part of the primary key regardless of whether
     *                        it carries {@code @Id} — used when flattening an
     *                        {@code @EmbeddedId} component, none of which are individually
     *                        annotated {@code @Id} on the embeddable class.
     */
    private FieldInfo buildFieldInfo(FieldDeclaration field, String prefix, boolean forcePrimaryKey) {
        String rawName = field.getVariables().get(0).getNameAsString();
        String name = prefix.isEmpty() ? rawName : prefix + "." + rawName;
        String type = field.getElementType().asString();

        FieldInfo.Builder builder = FieldInfo.builder().name(name).type(type);

        // @Id / @GeneratedValue
        builder.primaryKey(forcePrimaryKey || field.getAnnotationByName("Id").isPresent());
        builder.generatedValue(field.getAnnotationByName("GeneratedValue").isPresent());
        if (forcePrimaryKey) builder.nullable(false); // composite-key components are never nullable

        // @Transient (Java-only, no DB column) / @Formula (DB-computed, no DB column either)
        builder.transientField(field.getAnnotationByName("Transient").isPresent());
        field.getAnnotationByName("Formula").ifPresent(ann -> {
            builder.formula(true);
            // @Formula("expr") or @Formula(value = "expr") — capture the expression itself so
            // the schema can report what a field is actually computed by, not a generic label.
            extractAnnotationAttr(ann, "value").ifPresent(builder::formulaExpression);
        });

        // @Column
        field.getAnnotationByName("Column").ifPresent(ann -> {
            extractAnnotationAttr(ann, "name").ifPresent(builder::columnName);
            extractAnnotationAttr(ann, "nullable")
                    .ifPresent(v -> builder.nullable(!"false".equalsIgnoreCase(v)));
            extractAnnotationAttr(ann, "length")
                    .ifPresent(v -> { try { builder.maxLength(Integer.parseInt(v)); } catch (NumberFormatException ignored) {} });
            extractAnnotationAttr(ann, "unique")
                    .ifPresent(v -> builder.unique("true".equalsIgnoreCase(v)));
        });

        // Bean Validation
        if (field.getAnnotationByName("NotNull").isPresent())  builder.addValidationRule("@NotNull");
        if (field.getAnnotationByName("NotBlank").isPresent()) builder.addValidationRule("@NotBlank");
        if (field.getAnnotationByName("Email").isPresent())    builder.addValidationRule("@Email");
        field.getAnnotationByName("Size").ifPresent(ann -> {
            StringBuilder rule = new StringBuilder("@Size(");
            extractAnnotationAttr(ann, "min").ifPresent(v -> rule.append("min=").append(v).append(", "));
            extractAnnotationAttr(ann, "max").ifPresent(v -> rule.append("max=").append(v).append(", "));
            if (rule.length() > 6) {
                String s = rule.toString().replaceAll(", $", "") + ")";
                builder.addValidationRule(s);
            }
        });
        field.getAnnotationByName("Min").ifPresent(ann ->
                extractAnnotationAttr(ann, "value").or(() -> extractAnnotationAttr(ann, "value"))
                        .ifPresent(v -> builder.addValidationRule("@Min(" + v + ")")));
        field.getAnnotationByName("Max").ifPresent(ann ->
                extractAnnotationAttr(ann, "value")
                        .ifPresent(v -> builder.addValidationRule("@Max(" + v + ")")));
        field.getAnnotationByName("DecimalMin").ifPresent(ann ->
                extractAnnotationAttr(ann, "value")
                        .ifPresent(v -> builder.addValidationRule("@DecimalMin(" + v + ")")));
        field.getAnnotationByName("DecimalMax").ifPresent(ann ->
                extractAnnotationAttr(ann, "value")
                        .ifPresent(v -> builder.addValidationRule("@DecimalMax(" + v + ")")));
        field.getAnnotationByName("Positive").ifPresent(a -> builder.addValidationRule("@Positive"));
        field.getAnnotationByName("PositiveOrZero").ifPresent(a -> builder.addValidationRule("@PositiveOrZero"));
        field.getAnnotationByName("Negative").ifPresent(a -> builder.addValidationRule("@Negative"));
        field.getAnnotationByName("Pattern").ifPresent(ann ->
                extractAnnotationAttr(ann, "regexp")
                        .ifPresent(v -> builder.addValidationRule("@Pattern(" + v + ")")));
        field.getAnnotationByName("Digits").ifPresent(ann -> {
            StringBuilder rule = new StringBuilder("@Digits(");
            extractAnnotationAttr(ann, "integer").ifPresent(v -> rule.append("integer=").append(v).append(", "));
            extractAnnotationAttr(ann, "fraction").ifPresent(v -> rule.append("fraction=").append(v).append(", "));
            if (rule.length() > 8) {
                builder.addValidationRule(rule.toString().replaceAll(", $", "") + ")");
            }
        });

        // @Enumerated → field type is a Java enum whose constants become the domain of legal values
        if (field.getAnnotationByName("Enumerated").isPresent()) {
            builder.addValidationRule("@Enumerated");
        }

        // Field-level Javadoc → "business meaning" prose for documentation
        field.getJavadocComment()
                .map(jc -> jc.parse().getDescription().toText().strip())
                .filter(s -> !s.isBlank())
                .ifPresent(builder::description);

        return builder.build();
    }

    private boolean isRelationField(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .anyMatch(a -> RELATION_ANNOTATIONS.contains(a.getNameAsString()));
    }

    private Optional<RelationInfo> extractRelation(FieldDeclaration field) {
        String fieldName = field.getVariables().get(0).getNameAsString();
        String type = field.getElementType().asString();

        return field.getAnnotations().stream()
                .filter(a -> RELATION_ANNOTATIONS.contains(a.getNameAsString()))
                .findFirst()
                .map(ann -> {
                    RelationInfo.Kind kind = switch (ann.getNameAsString()) {
                        case "OneToMany"  -> RelationInfo.Kind.ONE_TO_MANY;
                        case "ManyToOne"  -> RelationInfo.Kind.MANY_TO_ONE;
                        case "OneToOne"   -> RelationInfo.Kind.ONE_TO_ONE;
                        default           -> RelationInfo.Kind.MANY_TO_MANY;
                    };
                    String joinCol = field.getAnnotationByName("JoinColumn")
                            .flatMap(a -> extractAnnotationAttr(a, "name"))
                            .orElse(null);
                    String mappedBy = extractAnnotationAttr(ann, "mappedBy").orElse(null);
                    return new RelationInfo(fieldName, type, kind, joinCol, mappedBy);
                });
    }

    // -----------------------------------------------------------------------
    // DTO extraction
    // -----------------------------------------------------------------------

    private static final Set<String> DTO_SUFFIXES = Set.of(
            "Dto", "DTO", "Request", "Response", "Command", "Query",
            "Payload", "Data", "Result", "Info", "Params", "Body", "Form");

    private static final Set<String> DTO_PACKAGE_SEGMENTS = Set.of(
            "model", "dto", "request", "response", "payload", "api",
            "contract", "transfer");

    /**
     * Extracts ALL DTO candidates from a compilation unit, including:
     * <ul>
     *   <li>Java {@code record} types (top-level and nested) — records are value objects by design</li>
     *   <li>Non-entity classes whose name ends in a DTO-ish suffix</li>
     *   <li>Any type in a package segment matching {@code model}, {@code dto}, {@code api}, etc.</li>
     * </ul>
     */
    public List<DtoInfo> extractDtos(CompilationUnit cu, String sourceFile) {
        List<DtoInfo> result = new ArrayList<>();
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        boolean inDtoPackage = isDtoPackage(pkg);

        // Records (top-level and inner) — records are immutable value types, always candidates
        cu.findAll(RecordDeclaration.class).forEach(rec -> {
            if (!isDtoCandidate(rec.getNameAsString(), inDtoPackage)) return;
            String fqn = pkg.isEmpty() ? rec.getNameAsString() : pkg + "." + rec.getNameAsString();
            List<FieldInfo> fields = rec.getParameters().stream()
                    .map(this::buildFieldInfoFromRecordParam)
                    .collect(Collectors.toList());
            result.add(new DtoInfo(fqn, rec.getNameAsString(), sourceFile, fields));
        });

        // Regular classes (non-interface, non-entity)
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
            if (c.isInterface() || c.getAnnotationByName("Entity").isPresent()) return;
            if (!isDtoCandidate(c.getNameAsString(), inDtoPackage)) return;
            String fqn = pkg.isEmpty() ? c.getNameAsString() : pkg + "." + c.getNameAsString();
            List<FieldInfo> fields = c.getFields().stream()
                    .filter(f -> !f.isStatic()) // e.g. serialVersionUID — never a real transfer field
                    .map(f -> buildFieldInfo(f, ""))
                    .collect(Collectors.toList());
            result.add(new DtoInfo(fqn, c.getNameAsString(), sourceFile, fields));
        });

        return result;
    }

    /** Backward-compatible single-result wrapper used by legacy code and tests. */
    public Optional<DtoInfo> extractDto(CompilationUnit cu, String sourceFile) {
        List<DtoInfo> all = extractDtos(cu, sourceFile);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    private boolean isDtoCandidate(String simpleName, boolean inDtoPackage) {
        if (inDtoPackage) return true;
        return DTO_SUFFIXES.stream().anyMatch(simpleName::endsWith);
    }

    private boolean isDtoPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String[] segments = pkg.split("\\.");
        return Arrays.stream(segments)
                .anyMatch(s -> DTO_PACKAGE_SEGMENTS.contains(s.toLowerCase()));
    }

    private FieldInfo buildFieldInfoFromRecordParam(Parameter param) {
        FieldInfo.Builder builder = FieldInfo.builder()
                .name(param.getNameAsString())
                .type(param.getType().asString());
        if (param.getAnnotationByName("NotNull").isPresent())  builder.addValidationRule("@NotNull");
        if (param.getAnnotationByName("NotBlank").isPresent()) builder.addValidationRule("@NotBlank");
        if (param.getAnnotationByName("Email").isPresent())    builder.addValidationRule("@Email");
        param.getAnnotationByName("Size").ifPresent(ann -> {
            StringBuilder rule = new StringBuilder("@Size(");
            extractAnnotationAttr(ann, "min").ifPresent(v -> rule.append("min=").append(v).append(", "));
            extractAnnotationAttr(ann, "max").ifPresent(v -> rule.append("max=").append(v).append(", "));
            if (rule.length() > 6) builder.addValidationRule(
                    rule.toString().replaceAll(", $", "") + ")");
        });
        return builder.build();
    }

    // -----------------------------------------------------------------------
    // MapStruct mapper correlation
    // -----------------------------------------------------------------------

    /**
     * Correlates DTO and Entity fields using MapStruct mapper information.
     * Returns a PersistenceMapping with explicit, implicit, or no-match rows.
     */
    public PersistenceMapping correlate(DtoInfo dto, EntityInfo entity,
                                        List<CompilationUnit> allCUs) {
        // Find a @Mapper that references both types
        Optional<MapperInfo> mapper = findMapper(dto, entity, allCUs);

        List<MappingRow> rows = new ArrayList<>();
        Map<String, String> explicitMappings = mapper
                .map(MapperInfo::mappings)
                .orElse(Map.of());

        Set<String> matchedEntityFields = new HashSet<>();

        for (FieldInfo dtoField : dto.getFields()) {
            String entityFieldName = explicitMappings.getOrDefault(dtoField.getName(), dtoField.getName());
            Optional<FieldInfo> entityField = entity.getFields().stream()
                    .filter(f -> f.getName().equals(entityFieldName))
                    .findFirst();

            if (entityField.isPresent()) {
                matchedEntityFields.add(entityField.get().getName());
                MappingRow.Confidence confidence = explicitMappings.containsKey(dtoField.getName())
                        ? MappingRow.Confidence.EXPLICIT
                        : (mapper.isPresent() ? MappingRow.Confidence.IMPLICIT : MappingRow.Confidence.NO_MATCH);
                // If no mapper and name matches, it's implicit (MapStruct default); if truly no mapper exists
                // and names differ, the entityField would be empty, handled in else branch.
                if (!mapper.isPresent() && !dtoField.getName().equals(entityFieldName)) {
                    confidence = MappingRow.Confidence.NO_MATCH;
                } else if (!mapper.isPresent()) {
                    confidence = MappingRow.Confidence.IMPLICIT;
                }
                rows.add(MappingRow.builder()
                        .dtoField(dtoField.getName()).dtoType(dtoField.getType())
                        .entityField(entityField.get().getName()).entityType(entityField.get().getType())
                        .confidence(confidence)
                        .build());
            } else {
                rows.add(MappingRow.builder()
                        .dtoField(dtoField.getName()).dtoType(dtoField.getType())
                        .entityField("[no match]").entityType("")
                        .confidence(MappingRow.Confidence.NO_MATCH)
                        .dtoOnly(true)
                        .build());
            }
        }

        // Entity-only fields
        for (FieldInfo entityField : entity.getFields()) {
            if (!matchedEntityFields.contains(entityField.getName())) {
                rows.add(MappingRow.builder()
                        .dtoField("[no match]").dtoType("")
                        .entityField(entityField.getName()).entityType(entityField.getType())
                        .confidence(MappingRow.Confidence.NO_MATCH)
                        .entityOnly(true)
                        .build());
            }
        }

        return new PersistenceMapping(dto, entity, rows,
                mapper.map(MapperInfo::fqn).orElse(null));
    }

    private Optional<MapperInfo> findMapper(DtoInfo dto, EntityInfo entity,
                                             List<CompilationUnit> allCUs) {
        String dtoSimple = dto.getSimpleName();
        String entitySimple = entity.getSimpleName();

        for (CompilationUnit cu : allCUs) {
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cls.getAnnotationByName("Mapper").isEmpty()) continue;
                String fqn = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString() + "." + cls.getNameAsString())
                        .orElse(cls.getNameAsString());

                Map<String, String> mappings = new LinkedHashMap<>();
                for (MethodDeclaration method : cls.getMethods()) {
                    boolean relevantMethod = method.getTypeAsString().contains(entitySimple)
                            || method.getTypeAsString().contains(dtoSimple);
                    if (!relevantMethod) continue;

                    for (AnnotationExpr ann : method.getAnnotations()) {
                        if (!"Mapping".equals(ann.getNameAsString())) continue;
                        String source = extractAnnotationAttr(ann, "source").orElse(null);
                        String target = extractAnnotationAttr(ann, "target").orElse(null);
                        if (source != null && target != null) {
                            mappings.put(source, target);
                        }
                    }
                }
                return Optional.of(new MapperInfo(fqn, mappings));
            }
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Repository extraction
    // -----------------------------------------------------------------------

    public List<RepositoryMethodInfo> extractRepositoryMethods(CompilationUnit cu) {
        List<RepositoryMethodInfo> result = new ArrayList<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getAnnotationByName("Repository").isPresent())
                .forEach(repo -> repo.getMethods().forEach(method -> {
                    String query = method.getAnnotationByName("Query")
                            .map(a -> extractAnnotationAttr(a, "value")
                                    .or(() -> {
                                        if (a instanceof SingleMemberAnnotationExpr s) {
                                            return Optional.of(unquote(s.getMemberValue().toString()));
                                        }
                                        return Optional.empty();
                                    })
                                    .orElse(null))
                            .orElse(null);
                    result.add(new RepositoryMethodInfo(
                            method.getNameAsString(),
                            method.getTypeAsString(),
                            query));
                }));
        return result;
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private Optional<String> extractAnnotationAttr(AnnotationExpr ann, String attrName) {
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> attrName.equals(p.getNameAsString()))
                    .findFirst()
                    .map(MemberValuePair::getValue)
                    .map(v -> unquote(v.toString()));
        }
        if ("value".equals(attrName) && ann instanceof SingleMemberAnnotationExpr single) {
            return Optional.of(unquote(single.getMemberValue().toString()));
        }
        return Optional.empty();
    }

    private String unquote(String s) {
        String t = s.trim();
        return (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2)
                ? t.substring(1, t.length() - 1) : t;
    }

    private String toSnakeCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private record MapperInfo(String fqn, Map<String, String> mappings) {}
}
