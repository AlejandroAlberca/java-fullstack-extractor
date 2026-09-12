package com.devmanchego.contextextractor.java.exception;

import java.util.List;

/**
 * Outcome of running {@link GenericExceptionClassifier} over a
 * {@link GenericExceptionCandidate}.
 *
 * <p>Carries not just the include/exclude decision but the numeric score and a
 * human-readable list of the reasons that contributed to it. The reasons are
 * surfaced in the generated catalog so a reader (developer or LLM) can see
 * <em>why</em> a generic exception was kept or dropped, instead of trusting an
 * opaque boolean.
 */
public record ClassificationResult(
        boolean included,
        int score,
        Confidence confidence,
        List<String> reasons
) {

    /** Confidence bucket derived from the score. */
    public enum Confidence {
        HIGH,      // strong business-facing signals (score >= HIGH_THRESHOLD)
        MEDIUM,    // included but heuristic (INCLUDE_THRESHOLD <= score < HIGH_THRESHOLD)
        EXCLUDED   // below the inclusion threshold
    }

    public String confidenceLabel() {
        return switch (confidence) {
            case HIGH -> "Generic exception — high confidence (handler + business context)";
            case MEDIUM -> "Generic exception — medium confidence (heuristic)";
            case EXCLUDED -> "Excluded (technical / not user-facing)";
        };
    }
}
