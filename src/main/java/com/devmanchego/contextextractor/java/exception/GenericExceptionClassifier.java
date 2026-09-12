package com.devmanchego.contextextractor.java.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a <em>generic</em> (framework) exception throw is a user-facing
 * business validation worth cataloging, or technical noise that should be dropped.
 *
 * <p>The decision is a weighted score over AST-derived signals (see
 * {@link GenericExceptionCandidate}). Positive weights reward signals that a throw
 * is an intentional, request-reachable, business error; negative weights punish
 * signals that it is a re-wrapped, defensive, or setup-time technical failure.
 *
 * <p>The single strongest signal is an explicit {@code @ExceptionHandler}: writing
 * a handler that maps the type to an HTTP status is a deliberate declaration of
 * user-facing intent. The rest of the model refines the ambiguous middle.
 *
 * <p>The classifier is stateless and holds no parser references, so it can be unit
 * tested with hand-built candidates.
 */
public final class GenericExceptionClassifier {

    // --- Thresholds -------------------------------------------------------
    static final int INCLUDE_THRESHOLD = 3;   // score >= this  → included
    static final int HIGH_THRESHOLD = 5;      // score >= this  → HIGH confidence

    // --- Weights ----------------------------------------------------------
    static final int W_HANDLER = 3;
    static final int W_IF_GUARD = 2;
    static final int W_VALIDATION_TYPE = 2;
    static final int W_REACHABLE = 2;
    static final int W_DESCRIPTIVE_MSG = 1;
    static final int W_BUSINESS_LAYER = 1;

    static final int W_CATCH_BLOCK = -3;
    static final int W_TECHNICAL_TYPE = -3;
    static final int W_INFRA_LAYER = -2;
    static final int W_NO_MESSAGE = -2;
    static final int W_CONSTRUCTOR_INIT = -2;

    /** Java exception types that model argument/state validation (business archetypes). */
    private static final Set<String> VALIDATION_TYPES = Set.of(
            "IllegalArgumentException",
            "IllegalStateException"
    );

    /** Java exception types that are almost always bugs / plumbing, never user validations. */
    private static final Set<String> TECHNICAL_TYPES = Set.of(
            "NullPointerException",
            "ClassCastException",
            "ArrayIndexOutOfBoundsException",
            "IndexOutOfBoundsException",
            "UnsupportedOperationException",
            "ConcurrentModificationException",
            "ArithmeticException",
            "AssertionError"
    );

    /**
     * Scores a candidate and returns the include/exclude decision with an audit
     * trail of the contributing reasons.
     */
    public ClassificationResult classify(GenericExceptionCandidate c) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        // --- Positive signals ---
        if (c.hasExplicitHandler()) {
            score += W_HANDLER;
            reasons.add("+%d explicit @ExceptionHandler maps this type".formatted(W_HANDLER));
        }
        if (c.insideIfGuard()) {
            score += W_IF_GUARD;
            reasons.add("+%d thrown inside an if-guard (validation)".formatted(W_IF_GUARD));
        }
        if (VALIDATION_TYPES.contains(c.exceptionType())) {
            score += W_VALIDATION_TYPE;
            reasons.add("+%d validation-archetype type (%s)".formatted(W_VALIDATION_TYPE, c.exceptionType()));
        }
        if (c.reachableFromEndpoint()) {
            score += W_REACHABLE;
            reasons.add("+%d reachable from an HTTP endpoint".formatted(W_REACHABLE));
        }
        if (c.hasDescriptiveMessage()) {
            score += W_DESCRIPTIVE_MSG;
            reasons.add("+%d descriptive literal message".formatted(W_DESCRIPTIVE_MSG));
        }
        if (c.layer() == GenericExceptionCandidate.Layer.BUSINESS) {
            score += W_BUSINESS_LAYER;
            reasons.add("+%d thrown in business layer".formatted(W_BUSINESS_LAYER));
        }

        // --- Negative signals ---
        if (c.insideCatchBlock()) {
            score += W_CATCH_BLOCK;
            reasons.add("%d thrown inside a catch block (re-wrap)".formatted(W_CATCH_BLOCK));
        }
        if (TECHNICAL_TYPES.contains(c.exceptionType())) {
            score += W_TECHNICAL_TYPE;
            reasons.add("%d technical exception type (%s)".formatted(W_TECHNICAL_TYPE, c.exceptionType()));
        }
        if (c.layer() == GenericExceptionCandidate.Layer.INFRA_UTIL) {
            score += W_INFRA_LAYER;
            reasons.add("%d thrown in infra/util layer".formatted(W_INFRA_LAYER));
        }
        if (!c.hasDescriptiveMessage() && (c.messageLiteral() == null || c.messageLiteral().isBlank())) {
            score += W_NO_MESSAGE;
            reasons.add("%d no literal message".formatted(W_NO_MESSAGE));
        }
        if (c.inConstructorOrInit()) {
            score += W_CONSTRUCTOR_INIT;
            reasons.add("%d thrown in constructor / initializer / @Bean".formatted(W_CONSTRUCTOR_INIT));
        }

        boolean included = score >= INCLUDE_THRESHOLD;
        ClassificationResult.Confidence confidence =
                !included ? ClassificationResult.Confidence.EXCLUDED
                        : score >= HIGH_THRESHOLD ? ClassificationResult.Confidence.HIGH
                        : ClassificationResult.Confidence.MEDIUM;

        return new ClassificationResult(included, score, confidence, List.copyOf(reasons));
    }
}
