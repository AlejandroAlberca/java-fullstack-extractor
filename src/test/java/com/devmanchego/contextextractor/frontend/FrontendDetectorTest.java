package com.devmanchego.contextextractor.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrontendDetectorTest {

    @TempDir
    Path tempDir;

    private void writePackageJson(String content) throws IOException {
        Files.writeString(tempDir.resolve("package.json"), content);
    }

    @Test
    void detectAngular_fromAngularCore() throws IOException {
        writePackageJson("{\"dependencies\":{\"@angular/core\":\"^17.0.0\"}}");
        assertEquals(FrontendFramework.ANGULAR, FrontendDetector.detect(tempDir));
    }

    @Test
    void detectReact_fromReactDep() throws IOException {
        writePackageJson("{\"dependencies\":{\"react\":\"^18.0.0\"}}");
        assertEquals(FrontendFramework.REACT, FrontendDetector.detect(tempDir));
    }

    @Test
    void detectNextJs_takePrecedenceOverReact() throws IOException {
        writePackageJson("{\"dependencies\":{\"next\":\"^14.0.0\",\"react\":\"^18.0.0\"}}");
        assertEquals(FrontendFramework.NEXTJS, FrontendDetector.detect(tempDir));
    }

    @Test
    void detectVue3_fromVue3Version() throws IOException {
        writePackageJson("{\"dependencies\":{\"vue\":\"^3.4.0\"}}");
        assertEquals(FrontendFramework.VUE3, FrontendDetector.detect(tempDir));
    }

    @Test
    void detectVue2_fromVue2Version() throws IOException {
        writePackageJson("{\"dependencies\":{\"vue\":\"^2.7.0\"}}");
        assertEquals(FrontendFramework.VUE2, FrontendDetector.detect(tempDir));
    }

    @Test
    void detectNuxt_takePrecedenceOverVue() throws IOException {
        writePackageJson("{\"dependencies\":{\"nuxt\":\"^3.0.0\",\"vue\":\"^3.4.0\"}}");
        assertEquals(FrontendFramework.NUXT, FrontendDetector.detect(tempDir));
    }

    @Test
    void detectInDevDependencies() throws IOException {
        writePackageJson("{\"devDependencies\":{\"@angular/core\":\"^17.0.0\"}}");
        assertEquals(FrontendFramework.ANGULAR, FrontendDetector.detect(tempDir));
    }

    @Test
    void noPackageJson_returnsUnknown() {
        assertEquals(FrontendFramework.UNKNOWN, FrontendDetector.detect(tempDir));
    }

    @Test
    void emptyPackageJson_returnsUnknown() throws IOException {
        writePackageJson("{}");
        assertEquals(FrontendFramework.UNKNOWN, FrontendDetector.detect(tempDir));
    }

    @Test
    void malformedPackageJson_returnsUnknown() throws IOException {
        writePackageJson("not valid json {{");
        assertEquals(FrontendFramework.UNKNOWN, FrontendDetector.detect(tempDir));
    }

    @Test
    void angularTakesPrecedenceOverEverything() throws IOException {
        writePackageJson("{\"dependencies\":{\"@angular/core\":\"^17\",\"react\":\"^18\",\"vue\":\"^3\"}}");
        assertEquals(FrontendFramework.ANGULAR, FrontendDetector.detect(tempDir));
    }

    // -----------------------------------------------------------------------
    // Phase 00: JSP + jQuery detection
    // -----------------------------------------------------------------------

    @Test
    void detectJspJQuery_whenBothJspViewsAndWebpackConfigPresent() throws IOException {
        writePackageJson("{\"dependencies\":{\"jquery\":\"^3.6.0\"}}");
        Files.createDirectories(tempDir.resolve("src/main/webapp/WEB-INF/jsp"));
        Files.writeString(tempDir.resolve("src/main/webapp/WEB-INF/jsp/index.jsp"), "<html></html>");
        Files.writeString(tempDir.resolve("webpack.config.js"), "module.exports = {};");

        assertEquals(FrontendFramework.JSP_JQUERY, FrontendDetector.detect(tempDir));
    }

    @Test
    void jspViewsWithoutBundlerConfig_doesNotMatch() throws IOException {
        // A stray .jsp fixture with no bundler at all isn't the webpack-bundled jQuery layer
        // this framework tag describes — both signals are required, not just one.
        writePackageJson("{}");
        Files.createDirectories(tempDir.resolve("jsp"));
        Files.writeString(tempDir.resolve("jsp/page.jsp"), "<html></html>");

        assertEquals(FrontendFramework.UNKNOWN, FrontendDetector.detect(tempDir));
    }

    @Test
    void webpackConfigWithoutJspViews_doesNotMatch() throws IOException {
        // A webpack config with no JSP views at all is more likely a plain SPA this detector
        // doesn't recognise yet, not the JSP + jQuery profile.
        writePackageJson("{}");
        Files.writeString(tempDir.resolve("webpack.config.js"), "module.exports = {};");

        assertEquals(FrontendFramework.UNKNOWN, FrontendDetector.detect(tempDir));
    }

    @Test
    void jspJQuery_neverMatchesWhenAnSpaFrameworkDependencyIsPresent() throws IOException {
        // No existing framework's detection outcome changes: a genuine Angular app that
        // happens to carry a stray .jsp file (e.g. a legacy leftover) must still detect as
        // ANGULAR, never JSP_JQUERY — the SPA dependency checks run first and win.
        writePackageJson("{\"dependencies\":{\"@angular/core\":\"^17.0.0\"}}");
        Files.createDirectories(tempDir.resolve("legacy"));
        Files.writeString(tempDir.resolve("legacy/old.jsp"), "<html></html>");
        Files.writeString(tempDir.resolve("webpack.config.js"), "module.exports = {};");

        assertEquals(FrontendFramework.ANGULAR, FrontendDetector.detect(tempDir));
    }

    @Test
    void jspJQuery_excludesNodeModulesFromTheScan() throws IOException {
        // A dependency's own fixtures must never produce a false match.
        writePackageJson("{}");
        Files.createDirectories(tempDir.resolve("node_modules/some-lib/test-fixtures"));
        Files.writeString(tempDir.resolve("node_modules/some-lib/test-fixtures/sample.jsp"), "<html></html>");
        Files.writeString(tempDir.resolve("node_modules/some-lib/webpack.config.js"), "module.exports = {};");

        assertEquals(FrontendFramework.UNKNOWN, FrontendDetector.detect(tempDir));
    }
}
