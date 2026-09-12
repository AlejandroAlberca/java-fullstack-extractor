package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnroutedPagesFallbackTest {

    private ComponentInfo comp(String name) {
        return new ComponentInfo(name, "/src/app/" + name + ".ts", "app-" + name, List.of());
    }

    @Test
    void synthesize_buildsOneUnroutedPagePerComponent_alphabetically() {
        List<RouteNode> routes = UnroutedPagesFallback.synthesize(List.of(
                comp("ProductListComponent"), comp("AccountListComponent")));

        assertEquals(2, routes.size());
        // Sorted by class name
        assertEquals("AccountListComponent", routes.get(0).getComponentName());
        assertEquals("ProductListComponent", routes.get(1).getComponentName());
        // Every node is an unrouted page with the sentinel path and no children
        for (RouteNode r : routes) {
            assertTrue(r.isPage(), "synthesized node should be a page");
            assertEquals(UnroutedPagesFallback.UNROUTED_PATH, r.getPath());
            assertNull(r.getTitle(), "title left null so renderer humanizes the name");
            assertTrue(r.getChildren().isEmpty());
        }
    }

    @Test
    void synthesize_skipsComponentsWithoutClassName() {
        List<RouteNode> routes = UnroutedPagesFallback.synthesize(List.of(
                new ComponentInfo(null, "/x.ts", "app-x", List.of()),
                comp("OrderListComponent")));

        assertEquals(1, routes.size());
        assertEquals("OrderListComponent", routes.get(0).getComponentName());
    }

    @Test
    void sitemapRenderer_withFallbackFlag_emitsNoteAndUnroutedMarker() {
        List<RouteNode> routes = UnroutedPagesFallback.synthesize(List.of(comp("AccountListComponent")));
        Map<String, ComponentInfo> byName = Map.of("AccountListComponent", comp("AccountListComponent"));

        String md = new SitemapRenderer().render(routes, byName, true);

        assertTrue(md.contains(UnroutedPagesFallback.NOTE), "should emit the fallback note");
        assertTrue(md.contains("[unrouted] Account List: AccountListComponent"),
                "should render the unrouted marker and humanized label");
    }

    @Test
    void frontendPagesRenderer_withFallbackFlag_emitsNote() {
        List<RouteNode> routes = UnroutedPagesFallback.synthesize(List.of(comp("AccountListComponent")));
        Map<String, ComponentInfo> byName = Map.of("AccountListComponent", comp("AccountListComponent"));
        EndpointMatcher.MatchResult empty = new EndpointMatcher.MatchResult(List.of(), List.of(), List.of());

        String md = new FrontendPagesRenderer().render(routes, byName, empty, true);

        assertTrue(md.contains(UnroutedPagesFallback.NOTE));
        assertTrue(md.contains("AccountListComponent"));
    }
}
