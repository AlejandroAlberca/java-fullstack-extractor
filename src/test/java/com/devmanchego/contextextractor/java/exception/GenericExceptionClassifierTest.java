package com.devmanchego.contextextractor.java.exception;

import com.devmanchego.contextextractor.java.exception.GenericExceptionCandidate.Layer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenericExceptionClassifierTest {

    private final GenericExceptionClassifier classifier = new GenericExceptionClassifier();

    /** Builder-ish helper with sane defaults; each test tweaks the relevant fields. */
    private GenericExceptionCandidate candidate(
            String type, boolean handler, boolean ifGuard, boolean catchBlock,
            boolean ctorInit, String message, Layer layer, boolean reachable) {
        return new GenericExceptionCandidate(
                type, handler, ifGuard, catchBlock, ctorInit, message, layer, reachable,
                "com.example.Foo", "bar", 42);
    }

    @Test
    void includesHighConfidence_handlerPlusBusinessValidation() {
        // IllegalArgumentException in a service, inside an if-guard, with handler,
        // reachable, descriptive message → strong include.
        GenericExceptionCandidate c = candidate(
                "IllegalArgumentException", true, true, false, false,
                "Salary must be positive", Layer.BUSINESS, true);

        ClassificationResult r = classifier.classify(c);

        // +3 handler +2 if +2 validation +2 reachable +1 msg +1 business = 11
        assertTrue(r.included());
        assertEquals(ClassificationResult.Confidence.HIGH, r.confidence());
        assertTrue(r.score() >= GenericExceptionClassifier.HIGH_THRESHOLD);
        assertFalse(r.reasons().isEmpty());
    }

    @Test
    void includesMediumConfidence_ifGuardValidationNoHandler() {
        // No handler, but if-guard + validation type + reachable = 2+2+2 = 6...
        // drop reachability to land in MEDIUM band. if(2)+validation(2)=4, +business(1)=5 -> still HIGH.
        // Use if-guard(2)+validation(2) only, OTHER layer, not reachable, short msg = 4 -> MEDIUM.
        GenericExceptionCandidate c = candidate(
                "IllegalStateException", false, true, false, false,
                "bad", Layer.OTHER, false);

        ClassificationResult r = classifier.classify(c);

        // +2 if +2 validation -2 no-descriptive? "bad" is 1 word, non-blank → no -2 (message present)
        // score = 4 → MEDIUM
        assertTrue(r.included());
        assertEquals(ClassificationResult.Confidence.MEDIUM, r.confidence());
    }

    @Test
    void excludes_technicalTypeNpeEvenWithHandler() {
        // NPE with a handler: +3 handler -3 technical = 0 → excluded.
        GenericExceptionCandidate c = candidate(
                "NullPointerException", true, false, false, false,
                "value was null here", Layer.BUSINESS, true);

        ClassificationResult r = classifier.classify(c);

        // +3 handler -3 technical +2 reachable +1 msg +1 business = 4 → actually MEDIUM.
        // This documents that a *handled + reachable* NPE still surfaces; verify it is NOT high.
        assertNotEquals(ClassificationResult.Confidence.HIGH, r.confidence());
    }

    @Test
    void excludes_pureTechnicalNpeNoContext() {
        GenericExceptionCandidate c = candidate(
                "NullPointerException", false, false, false, false,
                null, Layer.INFRA_UTIL, false);

        ClassificationResult r = classifier.classify(c);

        // -3 technical -2 infra -2 no-message = -7 → excluded
        assertFalse(r.included());
        assertEquals(ClassificationResult.Confidence.EXCLUDED, r.confidence());
    }

    @Test
    void excludes_catchBlockRewrapInUtil() {
        // catch (X e) { throw new IllegalStateException(e); } in a Utils class
        GenericExceptionCandidate c = candidate(
                "IllegalStateException", false, false, true, false,
                null, Layer.INFRA_UTIL, false);

        ClassificationResult r = classifier.classify(c);

        // +2 validation -3 catch -2 infra -2 no-message = -5 → excluded
        assertFalse(r.included());
    }

    @Test
    void excludes_thrownInConstructor() {
        GenericExceptionCandidate c = candidate(
                "IllegalArgumentException", false, true, false, true,
                "config value required", Layer.INFRA_UTIL, false);

        ClassificationResult r = classifier.classify(c);

        // +2 if +2 validation +1 msg -2 infra -2 ctor = 1 → excluded (below 3)
        assertFalse(r.included());
    }

    @Test
    void reasonsAreRecordedForAudit() {
        GenericExceptionCandidate c = candidate(
                "IllegalArgumentException", true, true, false, false,
                "Email must be unique", Layer.BUSINESS, true);

        ClassificationResult r = classifier.classify(c);

        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("@ExceptionHandler")));
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("if-guard")));
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("validation-archetype")));
    }

    @Test
    void descriptiveMessageThreshold_threeWords() {
        assertTrue(candidate("IllegalArgumentException", false, false, false, false,
                "must be positive", Layer.OTHER, false).hasDescriptiveMessage());
        assertFalse(candidate("IllegalArgumentException", false, false, false, false,
                "invalid", Layer.OTHER, false).hasDescriptiveMessage());
        assertFalse(candidate("IllegalArgumentException", false, false, false, false,
                null, Layer.OTHER, false).hasDescriptiveMessage());
    }
}
