package com.devmanchego.contextextractor.angular.model;

import com.devmanchego.contextextractor.frontend.FrontendFramework;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the P2 predicates that let a document distinguish "genuinely nothing here" from
 * "the parser couldn't interpret this frontend" — see {@link AngularProject#isUninterpreted()}.
 */
class AngularProjectTest {

    private AngularProject emptyProject(int scannedSourceFileCount) {
        return new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                FrontendFramework.UNKNOWN, List.of(), List.of(), List.of(), List.of(), java.util.Map.of(),
                scannedSourceFileCount);
    }

    @Test
    void emptyModel_withNoScannedFiles_isNotUninterpreted() {
        // No source files were even found — this is "no frontend project here", not
        // "we found one and couldn't read it".
        AngularProject project = emptyProject(0);
        assertTrue(project.isEmpty());
        assertFalse(project.isUninterpreted());
    }

    @Test
    void emptyModel_withScannedFiles_isUninterpreted() {
        AngularProject project = emptyProject(42);
        assertTrue(project.isEmpty());
        assertTrue(project.isUninterpreted());
        assertEquals(42, project.getScannedSourceFileCount());
    }

    @Test
    void nonEmptyModel_isNeverUninterpreted_regardlessOfFileCount() {
        ComponentInfo component = new ComponentInfo("AppComponent", "app.component.ts",
                "app-root", List.of());
        AngularProject project = new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                FrontendFramework.ANGULAR, List.of(component), List.of(), List.of(), List.of(),
                java.util.Map.of(), 100);

        assertFalse(project.isEmpty());
        assertFalse(project.isUninterpreted());
    }

    @Test
    void legacyConstructors_defaultScannedFileCountToZero_soExistingCallersAreUnaffected() {
        AngularProject project = new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), List.of(), List.of());
        assertEquals(0, project.getScannedSourceFileCount());
        assertFalse(project.isUninterpreted());
    }

    @Test
    void withScannedSourceFileCount_preservesEverythingElse() {
        AngularProject original = emptyProject(0).withFramework(FrontendFramework.REACT);
        AngularProject updated = original.withScannedSourceFileCount(7);

        assertEquals(FrontendFramework.REACT, updated.getFramework());
        assertEquals(7, updated.getScannedSourceFileCount());
        assertTrue(updated.isUninterpreted());
    }
}
