package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.HttpCallInfo;
import com.devmanchego.contextextractor.angular.model.ServiceInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JspBundleExtractorTest {

    @TempDir
    Path tempDir;

    private final JspBundleExtractor extractor = new JspBundleExtractor();

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private Path webappRoot() { return tempDir.resolve("src/main/webapp"); }

    private void writeWebpackConfig(String... entryLines) throws IOException {
        write("webpack.config.js", """
                module.exports = {
                    entry: {
                %s
                    },
                    output: { filename: '[name].js' }
                };
                """.formatted(String.join("\n", entryLines)));
    }

    // -----------------------------------------------------------------------
    // View -> bundle entry resolution
    // -----------------------------------------------------------------------

    @Test
    void viewWithOwnScriptTag_bindsToItsEntry() throws IOException {
        writeWebpackConfig("\"fap\": './src/main/js/pages/fap.js',");
        write("src/main/js/pages/fap.js", "$.ajax({url: '/workflow/list', method: 'GET'});");
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/fap.js\"></script>");

        Map<String, ComponentInfo> components = Map.of("ViewAchatFapPage",
                new ComponentInfo("ViewAchatFapPage", tempDir.resolve("src/main/webapp/WEB-INF/jsp/achat/fap.jsp").toString(),
                        null, List.of()));
        List<String> warnings = new ArrayList<>();

        var result = extractor.bindApiCalls(components, tempDir, webappRoot(), warnings);

        ComponentInfo bound = result.componentsByName().get("ViewAchatFapPage");
        assertEquals(List.of("ViewAchatFapPageBundle"), bound.getInjectedServices());
        assertEquals(1, result.services().size());
        assertEquals("/workflow/list", result.services().get(0).getHttpCalls().get(0).getUrlTemplate());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void viewWithNoMatchingScriptTag_isReportedUnbound() throws IOException {
        writeWebpackConfig("\"fap\": './src/main/js/pages/fap.js',");
        write("src/main/js/pages/fap.js", "$.ajax({url: '/x', method: 'GET'});");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/orphan.jsp", "<html>no scripts here</html>");

        Map<String, ComponentInfo> components = Map.of("OrphanPage",
                new ComponentInfo("OrphanPage", jsp.toString(), null, List.of()));
        List<String> warnings = new ArrayList<>();

        var result = extractor.bindApiCalls(components, tempDir, webappRoot(), warnings);

        assertTrue(result.componentsByName().get("OrphanPage").getInjectedServices().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("unbound")));
    }

    @Test
    void scriptInheritedFromASharedIncludedFragment_isNotCountedAsTheViewsOwnEntry() throws IOException {
        // The shared footer's own bundle (a global menu) must not compete with the page's own.
        writeWebpackConfig(
                "\"menu\": './src/main/js/structure/menu.js',",
                "\"fap\": './src/main/js/pages/fap.js',");
        write("src/main/js/structure/menu.js", "$.ajax({url: '/menu/items', method: 'GET'});");
        write("src/main/js/pages/fap.js", "$.ajax({url: '/fap/data', method: 'GET'});");
        write("src/main/webapp/WEB-INF/jsp/fragments/footer.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/menu.js\"></script>");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", """
                <script src="${pageContext.request.contextPath}/assets/fap.js"></script>
                <%@ include file="/WEB-INF/jsp/fragments/footer.jsp" %>
                """);

        Map<String, ComponentInfo> components = Map.of("ViewAchatFapPage",
                new ComponentInfo("ViewAchatFapPage", jsp.toString(), null, List.of()));
        List<String> warnings = new ArrayList<>();

        var result = extractor.bindApiCalls(components, tempDir, webappRoot(), warnings);

        assertEquals(1, result.services().size());
        assertEquals("/fap/data", result.services().get(0).getHttpCalls().get(0).getUrlTemplate());
        assertTrue(warnings.stream().noneMatch(w -> w.contains("ambiguous")));
    }

    @Test
    void twoOwnScriptTagsMatchingDifferentEntries_isAmbiguous_leftUnbound() throws IOException {
        writeWebpackConfig(
                "\"a\": './src/main/js/pages/a.js',",
                "\"b\": './src/main/js/pages/b.js',");
        write("src/main/js/pages/a.js", "$.ajax({url: '/a', method: 'GET'});");
        write("src/main/js/pages/b.js", "$.ajax({url: '/b', method: 'GET'});");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/dual.jsp", """
                <script src="${pageContext.request.contextPath}/assets/a.js"></script>
                <script src="${pageContext.request.contextPath}/assets/b.js"></script>
                """);

        Map<String, ComponentInfo> components = Map.of("DualPage",
                new ComponentInfo("DualPage", jsp.toString(), null, List.of()));
        List<String> warnings = new ArrayList<>();

        var result = extractor.bindApiCalls(components, tempDir, webappRoot(), warnings);

        assertTrue(result.componentsByName().get("DualPage").getInjectedServices().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ambiguous")));
    }

    @Test
    void unresolvedRoute_hasNothingToBind_neverThrows() {
        Map<String, ComponentInfo> components = Map.of("LogoutPage",
                new ComponentInfo("LogoutPage", JspRouteReconstructor.UNRESOLVED_FILE, null, List.of()));

        var result = extractor.bindApiCalls(components, tempDir, webappRoot(), new ArrayList<>());

        assertTrue(result.services().isEmpty());
        assertTrue(result.componentsByName().get("LogoutPage").getInjectedServices().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Module closure — transitive, terminates on cycles
    // -----------------------------------------------------------------------

    @Test
    void httpCallInAnImportedModule_isReachedTransitively() throws IOException {
        writeWebpackConfig("\"fap\": './src/main/js/pages/fap.js',");
        write("src/main/js/pages/fap.js", "import Utils from '../structure/Utils';\nUtils.go();");
        write("src/main/js/structure/Utils.js", "$.ajax({url: '/deep/call', method: 'GET'});");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/fap.js\"></script>");

        Map<String, ComponentInfo> components = Map.of("ViewAchatFapPage",
                new ComponentInfo("ViewAchatFapPage", jsp.toString(), null, List.of()));

        var result = extractor.bindApiCalls(components, tempDir, webappRoot(), new ArrayList<>());

        assertEquals(1, result.services().size());
        assertTrue(result.services().get(0).getHttpCalls().stream()
                .anyMatch(c -> "/deep/call".equals(c.getUrlTemplate())));
    }

    @Test
    void circularImports_terminate_withoutInfiniteLoop() throws IOException {
        writeWebpackConfig("\"a\": './src/main/js/pages/a.js',");
        write("src/main/js/pages/a.js", "import B from './b';\n$.ajax({url: '/a-call', method: 'GET'});");
        write("src/main/js/pages/b.js", "import A from './a';\n$.ajax({url: '/b-call', method: 'GET'});");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/a.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/a.js\"></script>");

        Map<String, ComponentInfo> components = Map.of("APage",
                new ComponentInfo("APage", jsp.toString(), null, List.of()));

        var result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> extractor.bindApiCalls(components, tempDir, webappRoot(), new ArrayList<>()));

        List<String> urls = result.services().get(0).getHttpCalls().stream().map(HttpCallInfo::getUrlTemplate).toList();
        assertTrue(urls.contains("/a-call"));
        assertTrue(urls.contains("/b-call"));
    }

    @Test
    void npmPackageImport_isNotTraversed() throws IOException {
        writeWebpackConfig("\"fap\": './src/main/js/pages/fap.js',");
        write("src/main/js/pages/fap.js", "import URI from 'urijs';\n$.ajax({url: '/only-call', method: 'GET'});");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/fap.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/fap.js\"></script>");

        Map<String, ComponentInfo> components = Map.of("FapPage",
                new ComponentInfo("FapPage", jsp.toString(), null, List.of()));

        // Must not throw trying to resolve 'urijs' as a relative file, and must still find the
        // call in the entry file itself.
        var result = extractor.bindApiCalls(components, tempDir, webappRoot(), new ArrayList<>());

        assertEquals(1, result.services().get(0).getHttpCalls().size());
    }

    // -----------------------------------------------------------------------
    // HTTP call extraction — jQuery $.ajax / verb shortcuts / fetch
    // -----------------------------------------------------------------------

    @Test
    void ajaxWithMethodField_extractsVerbAndUrl() throws IOException {
        writeWebpackConfig("\"x\": './src/main/js/pages/x.js',");
        write("src/main/js/pages/x.js", """
                function saveIt() {
                    $.ajax({
                        url: '/workflow/save',
                        method: 'POST',
                        success: function (data) { console.log(data); }
                    });
                }
                """);
        Path jsp = write("src/main/webapp/WEB-INF/jsp/x.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/x.js\"></script>");

        var result = extractor.bindApiCalls(
                Map.of("XPage", new ComponentInfo("XPage", jsp.toString(), null, List.of())),
                tempDir, webappRoot(), new ArrayList<>());

        HttpCallInfo call = result.services().get(0).getHttpCalls().get(0);
        assertEquals(HttpVerb.POST, call.getHttpVerb());
        assertEquals("/workflow/save", call.getUrlTemplate());
        assertEquals("saveIt", call.getMethodName());
    }

    @Test
    void getPostGetJSONShortcuts_recognisedWithTheirImpliedVerb() throws IOException {
        writeWebpackConfig("\"x\": './src/main/js/pages/x.js',");
        write("src/main/js/pages/x.js", """
                $.get('/a/list', function(d){});
                $.post('/b/create', {x:1}, function(d){});
                $.getJSON('/c/data', function(d){});
                """);
        Path jsp = write("src/main/webapp/WEB-INF/jsp/x.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/x.js\"></script>");

        var result = extractor.bindApiCalls(
                Map.of("XPage", new ComponentInfo("XPage", jsp.toString(), null, List.of())),
                tempDir, webappRoot(), new ArrayList<>());

        List<HttpCallInfo> calls = result.services().get(0).getHttpCalls();
        assertTrue(calls.stream().anyMatch(c -> c.getHttpVerb() == HttpVerb.GET && "/a/list".equals(c.getUrlTemplate())));
        assertTrue(calls.stream().anyMatch(c -> c.getHttpVerb() == HttpVerb.POST && "/b/create".equals(c.getUrlTemplate())));
        assertTrue(calls.stream().anyMatch(c -> c.getHttpVerb() == HttpVerb.GET && "/c/data".equals(c.getUrlTemplate())));
    }

    @Test
    void concatenatedUrl_literalSegmentsKept_variableSegmentsBecomeParam() throws IOException {
        writeWebpackConfig("\"x\": './src/main/js/pages/x.js',");
        write("src/main/js/pages/x.js",
                "$.ajax({url: basepath + '/workflow/' + id + '/detail', method: 'GET'});");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/x.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/x.js\"></script>");

        var result = extractor.bindApiCalls(
                Map.of("XPage", new ComponentInfo("XPage", jsp.toString(), null, List.of())),
                tempDir, webappRoot(), new ArrayList<>());

        assertEquals("/workflow/{param}/detail", result.services().get(0).getHttpCalls().get(0).getUrlTemplate());
    }

    @Test
    void fullyDynamicUrl_withNoLiteralSegment_isDropped() throws IOException {
        writeWebpackConfig("\"x\": './src/main/js/pages/x.js',");
        write("src/main/js/pages/x.js", "$.ajax({url: url, method: 'GET'});");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/x.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/x.js\"></script>");

        var result = extractor.bindApiCalls(
                Map.of("XPage", new ComponentInfo("XPage", jsp.toString(), null, List.of())),
                tempDir, webappRoot(), new ArrayList<>());

        // No usable call -> no service at all for this view.
        assertTrue(result.services().isEmpty());
    }

    @Test
    void fetchCall_extracted() throws IOException {
        writeWebpackConfig("\"x\": './src/main/js/pages/x.js',");
        write("src/main/js/pages/x.js", "fetch('/api/ping', {method: 'DELETE'});");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/x.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/x.js\"></script>");

        var result = extractor.bindApiCalls(
                Map.of("XPage", new ComponentInfo("XPage", jsp.toString(), null, List.of())),
                tempDir, webappRoot(), new ArrayList<>());

        HttpCallInfo call = result.services().get(0).getHttpCalls().get(0);
        assertEquals(HttpVerb.DELETE, call.getHttpVerb());
        assertEquals("/api/ping", call.getUrlTemplate());
    }

    // -----------------------------------------------------------------------
    // Robustness
    // -----------------------------------------------------------------------

    @Test
    void noWebpackConfigAnywhere_returnsEverythingUnbound_neverThrows() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/x.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/x.js\"></script>");
        List<String> warnings = new ArrayList<>();

        var result = extractor.bindApiCalls(
                Map.of("XPage", new ComponentInfo("XPage", jsp.toString(), null, List.of())),
                tempDir, webappRoot(), warnings);

        assertTrue(result.services().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("No webpack bundle entries")));
    }
}
