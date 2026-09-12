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
 * P2: a frontend project this tool cannot interpret must be reported as such — never as a
 * silent, complete-looking empty model. See {@link AngularProject#isUninterpreted()}.
 */
class FrontendAnalyzerUninterpretedTest {

    @TempDir
    Path tempDir;

    @Test
    void projectWithUnrecognisableSourceFiles_isReportedAsUninterpreted() throws IOException {
        // Plain scripts with none of the shapes any strategy or fallback recognises: no
        // @Component/@Injectable decorators, no JSX, no React/Vue imports, no HttpClient calls.
        Files.writeString(tempDir.resolve("legacy-page.js"),
                "function onLoad() { console.log('ready'); } window.onload = onLoad;");
        Files.writeString(tempDir.resolve("legacy-utils.js"),
                "var Utils = { formatDate: function(d) { return d.toString(); } };");

        AngularProject result = new FrontendAnalyzer(tempDir, Optional.empty()).analyze();

        assertTrue(result.getScannedSourceFileCount() >= 2,
                "Expected the two .js fixture files to be counted, found: "
                        + result.getScannedSourceFileCount());
        assertTrue(result.isEmpty(), "Fixture deliberately has nothing any strategy recognises");
        assertTrue(result.isUninterpreted(),
                "A non-trivial source tree that yields nothing must be reported as uninterpreted, "
                        + "not silently treated as a confirmed-empty frontend");
    }

    @Test
    void emptyDirectory_isNotUninterpreted() throws IOException {
        // No source files at all — this is "no frontend project here", a materially different
        // situation from "found one and couldn't read it", and must not trigger the warning.
        AngularProject result = new FrontendAnalyzer(tempDir, Optional.empty()).analyze();

        assertEquals(0, result.getScannedSourceFileCount());
        assertFalse(result.isUninterpreted());
    }

    @Test
    void nodeModulesAndBuildOutput_areExcludedFromTheFileCount() throws IOException {
        Files.createDirectories(tempDir.resolve("node_modules/some-lib"));
        Files.writeString(tempDir.resolve("node_modules/some-lib/index.js"), "module.exports = {};");
        Files.createDirectories(tempDir.resolve("dist"));
        Files.writeString(tempDir.resolve("dist/bundle.js"), "/* built output */");

        AngularProject result = new FrontendAnalyzer(tempDir, Optional.empty()).analyze();

        assertEquals(0, result.getScannedSourceFileCount(),
                "node_modules/ and dist/ content must not count as this project's own source");
        assertFalse(result.isUninterpreted());
    }
}
