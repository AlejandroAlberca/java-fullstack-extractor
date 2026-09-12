package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.devmanchego.contextextractor.java.mvc.SpringViewNameResolver.ViewResolverConfig;
import com.devmanchego.contextextractor.java.mvc.SpringViewNameResolver.ViewRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpringViewMergeTest {

    @TempDir
    Path tempDir;

    private final ViewResolverConfig config = ViewResolverConfig.DEFAULT;

    private Path webapp() { return tempDir.resolve("src/main/webapp"); }

    private Path jsp(String relativeToJspRoot) throws IOException {
        Path file = webapp().resolve("WEB-INF/jsp").resolve(relativeToJspRoot);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<html></html>");
        return file;
    }

    private static ViewRoute route(HttpVerb verb, String urlTemplate, String viewNameTemplate) {
        return new ViewRoute(verb, urlTemplate, List.of(viewNameTemplate), "com.example.X", "m", "X.java", List.of());
    }

    private static RouteNode leaf(String path, String componentName, String title) {
        return new RouteNode(path, componentName, title, null, false, List.of());
    }

    private RouteNode findByPath(List<RouteNode> routes, String path) {
        for (RouteNode r : routes) {
            if (path.equals(r.getPath())) return r;
            RouteNode found = findByPath(r.getChildren(), path);
            if (found != null) return found;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Supersede
    // -----------------------------------------------------------------------

    @Test
    void unresolvedRoute_isResolvedByAMatchingController() throws IOException {
        Path file = jsp("achat/fap.jsp");
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("ViewAchatFapPage", new ComponentInfo("ViewAchatFapPage",
                JspRouteReconstructor.UNRESOLVED_FILE, null, List.of()));
        List<RouteNode> routes = List.of(leaf("/view/achat/fap", "ViewAchatFapPage", "FAP"));

        var result = SpringViewMerge.merge(routes, components, webapp(),
                List.of(route(HttpVerb.GET, "/view/achat/{partie}", "achat/{partie}")), config);

        assertEquals(file.toString(), result.componentsByName().get("ViewAchatFapPage").getFilePath());
        RouteNode updated = findByPath(result.routes(), "/view/achat/fap");
        assertNull(updated.getOrigin().viewMappingNote(), "now exact — no inferred/unresolved caveat");
        assertTrue(updated.getOrigin().sources().stream().anyMatch(s -> s.contains("com.example.X#m()")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("had not resolved it at all")));
    }

    @Test
    void wrongConventionGuess_isSupersededByTheExactControllerMapping() throws IOException {
        jsp("index.jsp");
        Path correctFile = jsp("recherche/recherche.jsp");
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("RootPage", new ComponentInfo("RootPage", webapp().resolve("WEB-INF/jsp/index.jsp").toString(),
                null, List.of()));
        List<RouteNode> routes = List.of(leaf("/", "RootPage", "Welcome"));

        var result = SpringViewMerge.merge(routes, components, webapp(),
                List.of(route(HttpVerb.GET, "/", "recherche/recherche")), config);

        assertEquals(correctFile.toString(), result.componentsByName().get("RootPage").getFilePath());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("superseding the naming-convention mapping")));
    }

    @Test
    void agreeingMapping_stillConfirmedExactly_butNoSupersedeWarning() throws IOException {
        Path file = jsp("achat/fap.jsp");
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("P", new ComponentInfo("P", file.toString(), null, List.of()));
        List<RouteNode> routes = List.of(leaf("/view/achat/fap", "P", "FAP"));

        var result = SpringViewMerge.merge(routes, components, webapp(),
                List.of(route(HttpVerb.GET, "/view/achat/{partie}", "achat/{partie}")), config);

        assertEquals(file.toString(), result.componentsByName().get("P").getFilePath());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("superseding") || w.contains("had not resolved")));
    }

    @Test
    void controllerViewNameWithNoBackingFile_doesNotSupersede() throws IOException {
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("P", new ComponentInfo("P", JspRouteReconstructor.UNRESOLVED_FILE, null, List.of()));
        List<RouteNode> routes = List.of(leaf("/view/achat", "P", "Achat"));

        // "achat/achat" template matches, but achat/achat.jsp is never created — must not fabricate a path.
        var result = SpringViewMerge.merge(routes, components, webapp(),
                List.of(route(HttpVerb.GET, "/view/achat", "achat/achat")), config);

        assertEquals(JspRouteReconstructor.UNRESOLVED_FILE, result.componentsByName().get("P").getFilePath());
    }

    // -----------------------------------------------------------------------
    // Promote
    // -----------------------------------------------------------------------

    @Test
    void unreferencedView_matchingAControllerTemplate_isPromotedWithTheExactUrl() throws IOException {
        Path file = jsp("errors/400.jsp");
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("Errors400Page", new ComponentInfo("Errors400Page", file.toString(), null, List.of()));
        RouteNode unreferenced = new RouteNode("(unreferenced) WEB-INF/jsp/errors/400.jsp", "Errors400Page",
                "WEB-INF/jsp/errors/400.jsp", null, false, List.of());
        List<RouteNode> routes = List.of(
                new RouteNode("", null, JspRouteReconstructor.UNREFERENCED_SECTION, null, false, List.of(unreferenced)));

        var result = SpringViewMerge.merge(routes, components, webapp(),
                List.of(route(HttpVerb.GET, "/error/{code}", "errors/{code}")), config);

        assertNull(findByPath(result.routes(), "(unreferenced) WEB-INF/jsp/errors/400.jsp"));
        RouteNode promoted = findByPath(result.routes(), "/error/400");
        assertNotNull(promoted);
        assertEquals(SpringViewMerge.CONTROLLER_DECLARED_SECTION, findSectionTitleOf(result.routes(), promoted));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("unreferenced from the UI, but")));
    }

    @Test
    void unreferencedSectionBecomesEmpty_isDroppedEntirely() throws IOException {
        Path file = jsp("errors/400.jsp");
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("Errors400Page", new ComponentInfo("Errors400Page", file.toString(), null, List.of()));
        RouteNode unreferenced = new RouteNode("(unreferenced) WEB-INF/jsp/errors/400.jsp", "Errors400Page",
                "WEB-INF/jsp/errors/400.jsp", null, false, List.of());
        List<RouteNode> routes = List.of(
                new RouteNode("", null, JspRouteReconstructor.UNREFERENCED_SECTION, null, false, List.of(unreferenced)));

        var result = SpringViewMerge.merge(routes, components, webapp(),
                List.of(route(HttpVerb.GET, "/error/{code}", "errors/{code}")), config);

        assertTrue(result.routes().stream().noneMatch(r -> JspRouteReconstructor.UNREFERENCED_SECTION.equals(r.getTitle())));
    }

    @Test
    void promotionMatchingAnAlreadyPlacedUrl_isDropped_notDuplicated() throws IOException {
        Path file = jsp("errors/403.jsp");
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("ExistingErrorPage", new ComponentInfo("ExistingErrorPage", file.toString(), null, List.of()));
        components.put("Errors403Page", new ComponentInfo("Errors403Page", file.toString(), null, List.of()));
        RouteNode existing = leaf("/error/403", "ExistingErrorPage", "Error 403"); // e.g. already placed via web.xml
        RouteNode unreferenced = new RouteNode("(unreferenced) WEB-INF/jsp/errors/403.jsp", "Errors403Page",
                "WEB-INF/jsp/errors/403.jsp", null, false, List.of());
        List<RouteNode> routes = List.of(existing,
                new RouteNode("", null, JspRouteReconstructor.UNREFERENCED_SECTION, null, false, List.of(unreferenced)));

        var result = SpringViewMerge.merge(routes, components, webapp(),
                List.of(route(HttpVerb.GET, "/error/{code}", "errors/{code}")), config);

        assertTrue(result.routes().stream().noneMatch(r -> SpringViewMerge.CONTROLLER_DECLARED_SECTION.equals(r.getTitle())),
                "no duplicate second route for the same URL");
        assertNull(findByPath(result.routes(), "(unreferenced) WEB-INF/jsp/errors/403.jsp"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not duplicated")));
    }

    @Test
    void nonGetControllerRoute_neverPromotesAView() throws IOException {
        Path file = jsp("achat/save.jsp");
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("SavePage", new ComponentInfo("SavePage", file.toString(), null, List.of()));
        RouteNode unreferenced = new RouteNode("(unreferenced) WEB-INF/jsp/achat/save.jsp", "SavePage",
                "WEB-INF/jsp/achat/save.jsp", null, false, List.of());
        List<RouteNode> routes = List.of(
                new RouteNode("", null, JspRouteReconstructor.UNREFERENCED_SECTION, null, false, List.of(unreferenced)));

        var result = SpringViewMerge.merge(routes, components, webapp(),
                List.of(route(HttpVerb.POST, "/achat/save", "achat/save")), config);

        assertNotNull(findByPath(result.routes(), "(unreferenced) WEB-INF/jsp/achat/save.jsp"),
                "a POST-only controller route never proves GET navigability");
    }

    // -----------------------------------------------------------------------
    // Robustness
    // -----------------------------------------------------------------------

    @Test
    void unresolvedViewRoute_reportedAsAWarning_neverCrashes() {
        ViewRoute unresolved = new ViewRoute(HttpVerb.GET, "/x", List.of(), "com.example.X", "compute", "X.java",
                List.of("computeView()"));
        List<RouteNode> routes = List.of(leaf("/x", "P", "X"));
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("P", new ComponentInfo("P", JspRouteReconstructor.UNRESOLVED_FILE, null, List.of()));

        var result = SpringViewMerge.merge(routes, components, webapp(), List.of(unresolved), config);

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("computeView()") && w.contains("not a statically resolvable")));
        assertEquals(JspRouteReconstructor.UNRESOLVED_FILE, result.componentsByName().get("P").getFilePath());
    }

    @Test
    void noViewRoutesAtAll_leavesTheTreeUnchanged() {
        List<RouteNode> routes = List.of(leaf("/x", "P", "X"));
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        components.put("P", new ComponentInfo("P", JspRouteReconstructor.UNRESOLVED_FILE, null, List.of()));

        var result = SpringViewMerge.merge(routes, components, webapp(), List.of(), config);

        assertEquals(routes, result.routes());
        assertTrue(result.warnings().isEmpty());
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private String findSectionTitleOf(List<RouteNode> routes, RouteNode target) {
        for (RouteNode section : routes) {
            if (section.getChildren().contains(target)) return section.getTitle();
        }
        return null;
    }
}
