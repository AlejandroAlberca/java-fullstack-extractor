package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SectionsRendererTest {

    private final SectionsRenderer renderer = new SectionsRenderer();

    @Test
    void genuinelyNoRoutes_rendersTheOriginalMessage() {
        // scannedSourceFileCount 0 — nothing was found on disk, not "found something and
        // couldn't read it". The existing, less alarming message is still correct here.
        AngularProject project = new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), List.of(), List.of());

        String md = renderer.render(project, emptyMatchResult());

        assertTrue(md.contains("route tree could not be extracted"));
        assertFalse(md.contains("Not analysed"));
    }

    @Test
    void uninterpretedFrontend_rendersNotAnalysedInsteadOfEmpty() {
        AngularProject uninterpreted = new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                FrontendFramework.UNKNOWN, List.of(), List.of(), List.of(), List.of(), Map.of(), 9);
        assertTrue(uninterpreted.isUninterpreted());

        String md = renderer.render(uninterpreted, emptyMatchResult());

        assertTrue(md.contains("Not analysed"));
        assertTrue(md.contains("9"), "Message must name the scanned file count");
        // Must not read as a confirmed-empty application section.
        assertFalse(md.contains("route tree could not be extracted"));
    }

    @Test
    void nonEmptyRoutes_rendersSectionsNormally() {
        RouteNode route = new RouteNode("home", "HomeComponent", null, null, false, List.of());
        ComponentInfo component = new ComponentInfo("HomeComponent", "home.component.ts",
                "app-home", List.of());
        AngularProject project = new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                FrontendFramework.ANGULAR, List.of(component), List.of(), List.of(), List.of(route));

        String md = renderer.render(project, emptyMatchResult());

        assertTrue(md.contains("# APPLICATION SECTIONS"));
        assertTrue(md.contains("## Sections"));
        assertFalse(md.contains("Not analysed"));
    }

    private EndpointMatcher.MatchResult emptyMatchResult() {
        return new EndpointMatcher.MatchResult(List.of(), List.of(), List.of());
    }
}
