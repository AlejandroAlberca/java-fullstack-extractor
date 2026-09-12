package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.util.List;
import java.util.Optional;

/**
 * Attaches method-security annotations (@Secured, @RolesAllowed, @PreAuthorize) to an
 * {@link EndpointInfo.Builder}. Shared by every {@link EndpointExtractor} so the three
 * backend frameworks (Spring, JAX-RS, Micronaut) apply the exact same inheritance rule.
 *
 * <p>A class-level annotation is inherited by every handler method that does not declare
 * its own annotation of the same type — the same precedence the security frameworks
 * themselves apply at runtime. Without this, an endpoint whose protection is declared once
 * on the controller class is extracted as unprotected, however common that style is.
 *
 * <p>The value recorded on the endpoint is the annotation's <em>member value</em>, unwrapped
 * from its {@code @Name(...)} syntax — e.g. {@code @PreAuthorize("hasRole('ADMIN')")} yields
 * {@code hasRole('ADMIN')}. Downstream SpEL parsing ({@code PreAuthorizeExpressionParser})
 * needs the bare expression, not the annotation-wrapped source, to reliably tell a fully
 * decomposed expression from a partially understood one.
 */
public final class SecurityAnnotationExtractor {

    private static final List<String> SECURITY_ANNOTATIONS = List.of("Secured", "RolesAllowed", "PreAuthorize");

    private SecurityAnnotationExtractor() {}

    /**
     * @param classAnnotations  annotations on the declaring class (empty list if none apply)
     * @param methodAnnotations annotations on the handler method
     * @param builder           endpoint builder to attach the resolved annotations to
     */
    public static void attach(List<AnnotationExpr> classAnnotations,
                               List<AnnotationExpr> methodAnnotations,
                               EndpointInfo.Builder builder) {
        for (String name : SECURITY_ANNOTATIONS) {
            Optional<AnnotationExpr> methodAnn = findByName(methodAnnotations, name);
            if (methodAnn.isPresent()) {
                builder.addSecurityAnnotation(name, rawValue(methodAnn.get()), EndpointInfo.AnnotationSource.METHOD);
                continue;
            }
            findByName(classAnnotations, name).ifPresent(ann ->
                    builder.addSecurityAnnotation(name, rawValue(ann), EndpointInfo.AnnotationSource.CLASS));
        }
    }

    private static Optional<AnnotationExpr> findByName(List<AnnotationExpr> annotations, String name) {
        return annotations.stream().filter(a -> name.equals(a.getNameAsString())).findFirst();
    }

    /**
     * The annotation's member-value text, unwrapped from its outer {@code @Name(...)} syntax.
     * {@code @RolesAllowed({"A", "B"})} — an array member value, not a single string literal —
     * falls back to the full annotation text, which the downstream role-list parser already
     * handles by regex-extracting every quoted substring regardless of surrounding syntax.
     */
    private static String rawValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            String memberText = single.getMemberValue().toString();
            String literal = AnnotationValueResolver.extractStringLiteral(memberText);
            return literal != null ? literal : memberText;
        }
        return ann.toString();
    }
}
