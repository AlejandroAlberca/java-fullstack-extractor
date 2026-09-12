package com.devmanchego.contextextractor.frontend;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 00: JSP + jQuery is registered end to end through {@link FrontendAnalyzer} — detected
 * automatically, selectable via override, and routed through Strategy B (never Strategy A,
 * which doesn't apply to a framework with no TypeScript project for ts-morph to load).
 *
 * <p>The page model itself comes from route reconstruction ({@code JspProjectAnalyzer}).
 */
class FrontendAnalyzerJspJQueryTest {

    @TempDir
    Path tempDir;

    private void writeJspJQueryFixture() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), "{\"dependencies\":{\"jquery\":\"^3.6.0\"}}");
        Files.createDirectories(tempDir.resolve("src/main/webapp/WEB-INF/jsp"));
        Files.writeString(tempDir.resolve("src/main/webapp/WEB-INF/jsp/index.jsp"), "<html></html>");
        Files.writeString(tempDir.resolve("webpack.config.js"), "module.exports = {};");
    }

    @Test
    void autoDetection_selectsJspJQuery_withoutAnOverrideFlag() throws IOException {
        writeJspJQueryFixture();

        AngularProject result = new FrontendAnalyzer(tempDir, Optional.empty()).analyze();

        assertEquals(FrontendFramework.JSP_JQUERY, result.getFramework());
    }

    @Test
    void overrideFlag_selectsJspJQuery_whenAutoDetectionWouldBeAmbiguous() {
        // Empty project — nothing for auto-detection to key on at all.
        AngularProject result = new FrontendAnalyzer(tempDir, Optional.of(FrontendFramework.JSP_JQUERY)).analyze();

        assertEquals(FrontendFramework.JSP_JQUERY, result.getFramework());
    }

    @Test
    void strategyA_isNeverAttempted_evenWhenNodeIsAvailable() throws IOException {
        // Strategy A tags a successful result NODE_TS_MORPH. Since ts-morph is a TypeScript-
        // project analyser with no TypeScript project to load here, a JSP_JQUERY result must
        // always carry the Strategy B tag, regardless of whether Node.js happens to be on
        // PATH in the environment running this test.
        writeJspJQueryFixture();

        AngularProject result = new FrontendAnalyzer(tempDir, Optional.empty()).analyze();

        assertEquals(AngularProject.ParsingStrategy.JVM_ANTLR, result.getStrategy());
    }


    @Test
    void jspProject_isReconstructedIntoPages_withoutThrowing() throws IOException {
        writeJspJQueryFixture();

        AngularProject result = new FrontendAnalyzer(tempDir, Optional.empty()).analyze();

        assertFalse(result.isEmpty(), "Route reconstruction runs for JSP + jQuery");
        assertTrue(result.getComponents().stream()
                .anyMatch(c -> c.getFilePath().replace('\\', '/').endsWith("WEB-INF/jsp/index.jsp")),
                "Every JSP view is accounted for — here as an unreferenced view");
    }

    @Test
    void existingFrameworkDetection_isUnaffectedByThisChange() throws IOException {
        // No existing framework's detection outcome changes.
        Files.writeString(tempDir.resolve("package.json"),
                "{\"dependencies\":{\"@angular/core\":\"^17.0.0\"}}");

        AngularProject result = new FrontendAnalyzer(tempDir, Optional.empty()).analyze();

        assertEquals(FrontendFramework.ANGULAR, result.getFramework());
    }
}
