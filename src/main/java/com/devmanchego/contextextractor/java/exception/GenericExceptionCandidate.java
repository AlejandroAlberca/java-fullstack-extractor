package com.devmanchego.contextextractor.java.exception;

import java.util.Objects;

/**
 * AST-derived facts about a single {@code throw new XxxException(...)} statement
 * for a <em>generic</em> (non-custom, framework) exception type.
 *
 * <p>This record is a pure data carrier: it holds every signal the
 * {@link GenericExceptionClassifier} needs to decide whether the throw represents
 * a user-facing business validation (worth cataloging) or a technical failure
 * (noise). Populating it is the job of the extractor (which walks the AST and
 * the call graph); scoring it is the job of the classifier. Keeping the two
 * apart makes the scoring logic unit-testable without any parser.
 */
public record GenericExceptionCandidate(
        String exceptionType,          // simple name, e.g. "IllegalArgumentException"
        boolean hasExplicitHandler,    // an @ExceptionHandler maps this type
        boolean insideIfGuard,         // throw is the body/branch of an if condition
        boolean insideCatchBlock,      // throw sits inside a catch clause (re-wrap)
        boolean inConstructorOrInit,   // throw is in a constructor / static init / @Bean method
        String messageLiteral,         // the string-literal message, or null if absent/non-literal
        Layer layer,                   // architectural layer the throw lives in
        boolean reachableFromEndpoint, // throw is in the call graph of some HTTP endpoint
        String originClass,            // fully-qualified class containing the throw
        String originMethod,           // method name containing the throw
        int lineNumber                 // line of the throw statement
) {

    /** Architectural layer inferred from package / class-name conventions. */
    public enum Layer {
        BUSINESS,     // *.service.*, *.domain.*, *Service, *UseCase, *Handler(non-advice)
        CONTROLLER,   // *.controller.*, *Controller, *Resource
        INFRA_UTIL,   // *.config.*, *.util.*, *.infrastructure.*, *Config, *Utils, *Client
        OTHER         // anything not matched above
    }

    public GenericExceptionCandidate {
        Objects.requireNonNull(exceptionType);
        Objects.requireNonNull(layer);
    }

    /** True when a human-readable literal message with at least 3 words is present. */
    public boolean hasDescriptiveMessage() {
        if (messageLiteral == null || messageLiteral.isBlank()) {
            return false;
        }
        return messageLiteral.trim().split("\\s+").length >= 3;
    }
}
