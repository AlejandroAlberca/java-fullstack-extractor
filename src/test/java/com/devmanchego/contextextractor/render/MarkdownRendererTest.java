package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.java.model.*;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import com.devmanchego.contextextractor.matching.MatchedFlow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void emptyInput_rendersAllSections() {
        String md = renderer.render(emptyInput());

        assertTrue(md.contains("## 1. INDEX"));
        assertTrue(md.contains("## 2. API FLOWS"));
        assertTrue(md.contains("## 3. DATA MODEL"));
        assertTrue(md.contains("## 4. PERSISTENCE MAPPING"));
        assertTrue(md.contains("## 5. SPA / STATIC ROUTES"));
        assertTrue(md.contains("## 6. BACKGROUND JOBS"));
        assertTrue(md.contains("## 7. WARNINGS"));
    }

    @Test
    void uninterpretedFrontend_surfacedInWarningsSection() {
        AngularProject uninterpreted = new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                FrontendFramework.UNKNOWN, List.of(), List.of(), List.of(), List.of(), Map.of(), 17);
        assertTrue(uninterpreted.isUninterpreted(), "Fixture must actually be uninterpreted");

        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                uninterpreted, List.of(), emptyMatchResult(),
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), false, List.of(), null);

        String md = renderer.render(input);

        assertTrue(md.contains("not analysed") || md.contains("Not analysed"),
                "Warnings section must call out an uninterpreted frontend explicitly");
        assertTrue(md.contains("17"), "Warning must name the scanned file count");
    }

    @Test
    void genuinelyEmptyFrontend_doesNotTriggerUninterpretedWarning() {
        // scannedSourceFileCount defaults to 0 in jvmProject() — this must read as "nothing to
        // analyse", not as "couldn't analyse it".
        String md = renderer.render(emptyInput());
        assertFalse(md.contains("Frontend not analysed"));
    }

    @Test
    void strategyDisclosed_inWarningsSection() {
        AngularProject nodeProject = new AngularProject(
                AngularProject.ParsingStrategy.NODE_TS_MORPH, List.of(), List.of(), List.of());

        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                nodeProject, List.of(), emptyMatchResult(),
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), false, List.of(), null);

        String md = renderer.render(input);
        assertTrue(md.contains("Strategy A"), "Node.js strategy must be disclosed in warnings section");
    }

    @Test
    void jvmStrategyDisclosed_inWarningsSection() {
        String md = renderer.render(emptyInput());
        assertTrue(md.contains("Strategy B"), "JVM strategy must be disclosed in warnings section");
    }

    @Test
    void jspJQueryStrategyLabel_neverSaysNodeUnavailable() {
        // Strategy A is skipped deliberately for this framework — it doesn't apply, regardless
        // of whether Node.js happens to be installed on the machine running the extractor.
        // Claiming "Node.js not available" would simply be wrong on a machine where it is.
        AngularProject jspProject = new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                FrontendFramework.JSP_JQUERY, List.of(), List.of(), List.of());

        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                jspProject, List.of(), emptyMatchResult(),
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), false, List.of(), null);

        String md = renderer.render(input);

        assertTrue(md.contains("Strategy B"));
        assertFalse(md.contains("Node.js not available"));
    }

    @Test
    void classpathDegraded_warningPresent() {
        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                jvmProject(), List.of(), emptyMatchResult(),
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), true, List.of(), null);

        String md = renderer.render(input);
        assertTrue(md.contains("Degraded type resolution"),
                "Classpath degraded warning must appear in warnings section");
    }

    @Test
    void nameCollision_warningPresent() {
        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                jvmProject(), List.of(), emptyMatchResult(),
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), false,
                List.of("Class name 'CustomerController' is ambiguous — com.a.CustomerController, com.b.CustomerController"),
                null);

        String md = renderer.render(input);
        assertTrue(md.contains("Name collision"), "Name collision must appear in warnings section");
        assertTrue(md.contains("CustomerController"));
    }

    @Test
    void matchedFlow_appearsInApiFlowsAndIndex() {
        EndpointInfo ep = EndpointInfo.builder()
                .httpVerb(HttpVerb.GET).pathTemplate("/api/items")
                .controllerClass("com.example.ItemController").methodName("getAll")
                .responseType("Item").sourceFile("/ItemController.java")
                .framework("Spring").build();

        ServiceInfo svc = new ServiceInfo("ItemService", "/item.service.ts",
                List.of(new HttpCallInfo("getItems", HttpVerb.GET, "/api/items", "Item[]", null)));

        MatchedFlow flow = new MatchedFlow(svc,
                svc.getHttpCalls().get(0), ep, null, null);

        EndpointMatcher.MatchResult matchResult = new EndpointMatcher.MatchResult(
                List.of(flow), List.of(), List.of());

        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                jvmProject(), List.of(ep), matchResult,
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), false, List.of(), null);

        String md = renderer.render(input);
        assertTrue(md.contains("GET"), "HTTP verb must appear in output");
        assertTrue(md.contains("/api/items"), "Path must appear in output");
        assertTrue(md.contains("### 2.1"), "Flow subsection must be numbered");
    }

    @Test
    void scheduledJob_appearsInIndexAndSection6() {
        ScheduledJobInfo job = new ScheduledJobInfo(
                "ReportJob", "generateDailyReport", "/jobs/ReportJob.java",
                "0 0 6 * * *", null, null, null);

        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                jvmProject(), List.of(), emptyMatchResult(),
                List.of(), Map.of(), Map.of(), List.of(job), Map.of(), List.of(), List.of(), false, List.of(), null);

        String md = renderer.render(input);
        assertTrue(md.contains("ReportJob"), "Job class must appear in output");
        assertTrue(md.contains("generateDailyReport"), "Job method must appear in output");
        assertTrue(md.contains("0 0 6 * * *"), "Cron expression must appear in output");
        assertTrue(md.contains("### 6.1"), "Job subsection must be numbered");
        assertTrue(md.contains("Background Jobs"), "Index must list background jobs");
    }

    @Test
    void unresolvedUrlWarning_appearsInSection7() {
        String warn = "Unresolved dynamic URL in `AccountService#save`: "
                + "`/{applicationConfigService}{getEndpointFor}api/v5/account` "
                + "— add `--url-prefix-map ...`";

        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                jvmProject(), List.of(), emptyMatchResult(),
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(),
                List.of(warn), false, List.of(), null);

        String md = renderer.render(input);
        assertTrue(md.contains("Unresolved dynamic URL"), "Warning type must appear");
        assertTrue(md.contains("AccountService#save"), "Service name must appear");
    }

    @Test
    void spaRoute_appearsInSection5AndIndex_notInWarnings() {
        EndpointInfo spaEp = EndpointInfo.builder()
                .httpVerb(HttpVerb.GET).pathTemplate("/dashboard")
                .controllerClass("com.example.SpaController").methodName("forwardToIndex")
                .responseType("String").sourceFile("/SpaController.java")
                .framework("Spring").staticRoute(true).build();

        EndpointMatcher.MatchResult matchResult = new EndpointMatcher.MatchResult(
                List.of(), List.of(), List.of()); // unmatchedJava is empty — SPA filtered at matcher level

        MarkdownRenderer.RenderInput input = new MarkdownRenderer.RenderInput(
                jvmProject(), List.of(spaEp), matchResult,
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), false, List.of(), null);

        String md = renderer.render(input);
        assertTrue(md.contains("## 5. SPA / STATIC ROUTES"), "Section 5 must exist");
        assertTrue(md.contains("[STATIC_ROUTE]"), "Index must label SPA routes");
        assertTrue(md.contains("/dashboard"), "SPA path must appear in output");
        assertFalse(md.contains("Unmatched Java endpoint"), "SPA routes must not appear as unmatched warnings");
    }

    @Test
    void sectionNumbers_notReused() {
        String md = renderer.render(emptyInput());

        assertEquals(1, countOccurrences(md, "## 1. INDEX"));
        assertEquals(1, countOccurrences(md, "## 2. API FLOWS"));
        assertEquals(1, countOccurrences(md, "## 3. DATA MODEL"));
        assertEquals(1, countOccurrences(md, "## 4. PERSISTENCE MAPPING"));
        assertEquals(1, countOccurrences(md, "## 5. SPA / STATIC ROUTES"));
        assertEquals(1, countOccurrences(md, "## 6. BACKGROUND JOBS"));
        assertEquals(1, countOccurrences(md, "## 7. WARNINGS"));
    }

    @Test
    void anchorUtil_slugsConvertedCorrectly() {
        assertEquals("api-flows", AnchorUtil.toSlug("API FLOWS"));
        assertEquals("21-get-apiitems", AnchorUtil.toSlug("2.1 GET /api/items"));
        assertEquals("persistence-mapping", AnchorUtil.toSlug("PERSISTENCE MAPPING"));
    }

    @Test
    void anchorUtil_uniquenessWithinRenderer() {
        AnchorUtil anchors = new AnchorUtil();
        String slug1 = anchors.slug("GET /api/items");
        String slug2 = anchors.slug("GET /api/items");
        assertNotEquals(slug1, slug2, "Duplicate headings must produce unique anchors");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MarkdownRenderer.RenderInput emptyInput() {
        return new MarkdownRenderer.RenderInput(
                jvmProject(), List.of(), emptyMatchResult(),
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), false, List.of(), null);
    }

    private AngularProject jvmProject() {
        return new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), List.of(), List.of());
    }

    private EndpointMatcher.MatchResult emptyMatchResult() {
        return new EndpointMatcher.MatchResult(List.of(), List.of(), List.of());
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
