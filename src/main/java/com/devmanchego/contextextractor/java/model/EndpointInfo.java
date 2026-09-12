package com.devmanchego.contextextractor.java.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single HTTP endpoint discovered in the backend source code.
 * The path template uses canonical placeholders in {name} form (already normalized).
 */
public final class EndpointInfo {

    private final HttpVerb httpVerb;
    private final String pathTemplate;
    private final String bodyParameterType;   // null when no request body
    private final String responseType;        // unwrapped (ResponseEntity<T> → T)
    private final String controllerClass;     // fully-qualified
    private final String methodName;
    private final String sourceFile;
    private final String framework;           // "Spring", "JaxRs", "Micronaut"
    private final boolean staticRoute;        // true for SPA / view-serving controllers (@Controller, not @RestController)
    private final Map<String, String> securityAnnotations;  // @Secured, @RolesAllowed, @PreAuthorize → raw (unwrapped) value
    private final Map<String, AnnotationSource> securityAnnotationSources;  // same keys → where it was declared

    /**
     * Where a security annotation attached to this endpoint was actually declared.
     * A {@code METHOD} annotation always overrides a {@code CLASS} one of the same
     * type; a {@code CLASS} annotation is inherited by every method that does not
     * declare its own — see {@code SecurityAnnotationExtractor}.
     */
    public enum AnnotationSource { METHOD, CLASS }

    private EndpointInfo(Builder b) {
        this.httpVerb = Objects.requireNonNull(b.httpVerb);
        this.pathTemplate = Objects.requireNonNull(b.pathTemplate);
        this.bodyParameterType = b.bodyParameterType;
        this.responseType = b.responseType;
        this.controllerClass = Objects.requireNonNull(b.controllerClass);
        this.methodName = Objects.requireNonNull(b.methodName);
        this.sourceFile = b.sourceFile;
        this.framework = Objects.requireNonNull(b.framework);
        this.staticRoute = b.staticRoute;
        this.securityAnnotations = new HashMap<>(b.securityAnnotations);
        this.securityAnnotationSources = new HashMap<>(b.securityAnnotationSources);
    }

    public HttpVerb getHttpVerb() { return httpVerb; }
    public String getPathTemplate() { return pathTemplate; }
    public String getBodyParameterType() { return bodyParameterType; }
    public String getResponseType() { return responseType; }
    public String getControllerClass() { return controllerClass; }
    public String getMethodName() { return methodName; }
    public String getSourceFile() { return sourceFile; }
    public String getFramework() { return framework; }
    public boolean isStaticRoute() { return staticRoute; }
    public String getControllerName() { return controllerClass.substring(controllerClass.lastIndexOf('.') + 1); }

    public boolean hasSecurityAnnotation() {
        return !securityAnnotations.isEmpty();
    }

    public boolean hasAnnotation(String annotationName) {
        return securityAnnotations.containsKey(annotationName);
    }

    public String getAnnotationValue(String annotationName) {
        return securityAnnotations.get(annotationName);
    }

    /**
     * Where {@code annotationName} was declared for this endpoint. Defaults to
     * {@code METHOD} when the annotation isn't present at all — callers should
     * check {@link #hasAnnotation(String)} first.
     */
    public AnnotationSource getAnnotationSource(String annotationName) {
        return securityAnnotationSources.getOrDefault(annotationName, AnnotationSource.METHOD);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private HttpVerb httpVerb;
        private String pathTemplate;
        private String bodyParameterType;
        private String responseType;
        private String controllerClass;
        private String methodName;
        private String sourceFile;
        private String framework;
        private boolean staticRoute = false;
        private Map<String, String> securityAnnotations = new HashMap<>();
        private Map<String, AnnotationSource> securityAnnotationSources = new HashMap<>();

        public Builder httpVerb(HttpVerb v) { this.httpVerb = v; return this; }
        public Builder pathTemplate(String v) { this.pathTemplate = v; return this; }
        public Builder bodyParameterType(String v) { this.bodyParameterType = v; return this; }
        public Builder responseType(String v) { this.responseType = v; return this; }
        public Builder controllerClass(String v) { this.controllerClass = v; return this; }
        public Builder methodName(String v) { this.methodName = v; return this; }
        public Builder sourceFile(String v) { this.sourceFile = v; return this; }
        public Builder framework(String v) { this.framework = v; return this; }
        public Builder staticRoute(boolean v) { this.staticRoute = v; return this; }
        /** Method-level annotation (the common case — tests and single-source extractors use this). */
        public Builder addSecurityAnnotation(String name, String value) {
            return addSecurityAnnotation(name, value, AnnotationSource.METHOD);
        }
        public Builder addSecurityAnnotation(String name, String value, AnnotationSource source) {
            this.securityAnnotations.put(name, value);
            this.securityAnnotationSources.put(name, source);
            return this;
        }
        public EndpointInfo build() { return new EndpointInfo(this); }
    }

    @Override
    public String toString() {
        return framework + " " + httpVerb + " " + pathTemplate + " → " + controllerClass + "#" + methodName;
    }
}
