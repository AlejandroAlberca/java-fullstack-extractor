package com.devmanchego.contextextractor;

import com.devmanchego.contextextractor.cli.CliArguments;
import com.devmanchego.contextextractor.cli.CliValidator;
import com.devmanchego.contextextractor.render.FrontendPagesRenderer;
import com.devmanchego.contextextractor.render.FrontendPagesZipExporter;
import com.devmanchego.contextextractor.render.SecurityMatrixRenderer;
import com.devmanchego.contextextractor.render.SitemapRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 08: runs the whole pipeline — {@code CliValidator} through {@code Application.run} — end
 * to end against a committed JSP + jQuery fixture exercising every JSP phase together (00–07):
 * a menu with UI-fragment authorisation, static includes, a form with labels and a required
 * marker, jQuery AJAX calls (literal and path-parameterised), a shared bundle correctly excluded
 * from a page's own binding, and two Spring MVC view-resolution outcomes — one URL where the
 * controller's exact mapping disagrees with (and supersedes) the naming-convention guess, and one
 * JSP the frontend never links that the controller alone proves reachable.
 *
 * <p>This is deliberately not a re-verification of any single phase's internal logic — each has
 * its own unit tests for that. It is the check those tests cannot do individually: that the whole
 * pipeline, wired together as a real invocation would run it, produces every document category a
 * SPA-framework project produces, without a crash, and that the JSP-specific ones actually reflect
 * this fixture's real content rather than an empty stub.
 */
class JspJQueryEndToEndTest {

    @TempDir
    Path outputDir;

    private Path fixture(String... segments) throws URISyntaxException {
        URL resource = getClass().getClassLoader().getResource("fixtures/jsp-jquery-app");
        assertNotNull(resource, "test fixture not found on the classpath");
        Path path = Path.of(resource.toURI());
        for (String s : segments) path = path.resolve(s);
        return path;
    }

    @Test
    void fullPipelineRun_producesEveryDocumentCategory_reflectingTheRealFixture() throws Exception {
        Path backend = fixture("backend");
        Path frontend = fixture("frontend");

        CliArguments cliArgs = CliValidator.validate(new String[]{
                backend.toString(), frontend.toString(), "full-stack-spec.md",
                "--output-dir", outputDir.toString(),
                "--frontend-framework", "jsp",
                "--skip-db-connection"
        });

        assertDoesNotThrow(() -> Application.run(cliArgs));

        // -----------------------------------------------------------------
        // Every document category a SPA-framework run produces — Application.run
        // writes these unconditionally; a JSP-specific run must too.
        // -----------------------------------------------------------------
        assertFileExists(cliArgs.getOutputFile(), "main spec document");
        assertFileExists(SitemapRenderer.sitemapOutputPath(cliArgs.getOutputFile()), "sitemap");
        assertFileExists(FrontendPagesRenderer.pagesOutputPath(cliArgs.getOutputFile()), "frontend pages document");
        assertFileExists(SecurityMatrixRenderer.securityMatrixOutputPath(cliArgs.getOutputFile()), "security matrix");
        assertFileExists(FrontendPagesZipExporter.zipOutputPath(cliArgs.getOutputFile()), "frontend pages ZIP");
        assertFileExists(cliArgs.getRootIndexFile(), "root index document");

        // -----------------------------------------------------------------
        // Sitemap: routes from every JSP source, including the two Phase 07 outcomes.
        // -----------------------------------------------------------------
        String sitemap = Files.readString(SitemapRenderer.sitemapOutputPath(cliArgs.getOutputFile()));
        assertTrue(sitemap.contains("/view/dossier/liste"), sitemap);
        assertTrue(sitemap.contains("/view/dossier/detail"), sitemap);
        assertTrue(sitemap.contains("Spring controller"),
                "at least one route must carry Phase 07 provenance:\n" + sitemap);
        assertTrue(sitemap.contains("/error/404") && sitemap.contains("Controller-declared"),
                "the never-linked errors/404.jsp must be promoted by Phase 07:\n" + sitemap);
        assertTrue(sitemap.contains("Welcome page") && sitemap.contains("DefaultController#defaultPage()"),
                "the root URL's file must come from the controller, not the welcome-file guess:\n" + sitemap);
        assertFalse(sitemap.contains("view: inferred"),
                "the root URL's mapping is now exact — no inferred caveat should remain:\n" + sitemap);

        // -----------------------------------------------------------------
        // Frontend pages: real fields, real AJAX call sites, real field<->request correlation,
        // real UI Interactions — not just an empty "Form Fields" stub.
        // -----------------------------------------------------------------
        String pages = Files.readString(FrontendPagesRenderer.pagesOutputPath(cliArgs.getOutputFile()));
        assertTrue(pages.contains("`recherche`"), "the search field must appear:\n" + pages);
        assertTrue(pages.contains("`statut`") && pages.contains("required"), pages);
        assertTrue(pages.contains("AJAX Call Sites"), pages);
        assertTrue(pages.contains("PUT") && pages.contains("/dossier/42"),
                "the save endpoint, its id resolved from the module's own constant, must be reconstructed:\n" + pages);
        assertTrue(pages.contains("Field ↔ Request Correlation"), pages);
        assertTrue(pages.contains("UI Interactions") || pages.contains("Enregistrer"),
                "the Save button's business-trigger event must be extracted:\n" + pages);

        // -----------------------------------------------------------------
        // Security matrix: the UI-fragment rule from <sec:authorize> in menu.jsp.
        // -----------------------------------------------------------------
        String security = Files.readString(SecurityMatrixRenderer.securityMatrixOutputPath(cliArgs.getOutputFile()));
        assertTrue(security.contains("DOSSIER"), security);
        assertTrue(security.contains("UI-Fragment"), security);
    }

    private void assertFileExists(Path file, String label) {
        assertTrue(Files.isRegularFile(file), label + " was not written: " + file);
        try {
            assertTrue(Files.size(file) > 0, label + " is empty: " + file);
        } catch (IOException e) {
            fail("Could not read " + label + ": " + e.getMessage());
        }
    }
}
