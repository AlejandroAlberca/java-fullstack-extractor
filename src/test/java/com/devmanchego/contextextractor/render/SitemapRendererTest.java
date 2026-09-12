package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.model.RouteOrigin;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SitemapRendererTest {

    private final SitemapRenderer renderer = new SitemapRenderer();

    private static RouteNode page(String path, String component, String title) {
        return new RouteNode(path, component, title, null, false, List.of());
    }

    @Test
    void titledSectionWithoutComponent_rendersAHeadingAndItsChildren() {
        // A JSP menu section: no page of its own. Skipping it used to drop every page beneath it.
        RouteNode section = new RouteNode("", null, "Achats", null, false, List.of(
                page("/view/achat/fap", "ViewAchatFapPage", "Créer une FAP")));

        String md = renderer.render(List.of(section), Map.of());

        assertTrue(md.contains("- **Achats**\n"), md);
        assertTrue(md.contains("  - [/view/achat/fap] Créer une FAP: ViewAchatFapPage"), md);
    }

    @Test
    void anonymousGroupingRoute_isTransparent_childrenStillRendered() {
        // Angular guarded group: path '', canActivate, children — no component, no title.
        RouteNode group = new RouteNode("", null, null, null, false, List.of(page("users", "UserListComponent", null)));

        String md = renderer.render(List.of(group), Map.of());

        assertTrue(md.contains("- [users] User List: UserListComponent"), md);
    }

    @Test
    void wildcardRoute_neverRendered() {
        RouteNode wildcard = new RouteNode("**", null, null, null, false, List.of(page("x", "XComponent", null)));

        String md = renderer.render(List.of(page("home", "HomeComponent", null), wildcard), Map.of());

        assertFalse(md.contains("[x]"), md);
    }

    @Test
    void routeOrigin_rendersPermissionsStatesMappingAndSources() {
        RouteOrigin origin = new RouteOrigin(List.of("link (WEB-INF/jsp/fragments/menu.jsp)"),
                Set.of("ACHAT"), Set.of("SAISIE"), "inferred — the directory-index fallback");
        RouteNode node = new RouteNode("/view/achat/fap", "ViewAchatFapPage", "FAP", null, false, List.of(), origin);

        String md = renderer.render(List.of(node), Map.of());

        assertTrue(md.contains("requires: ACHAT"), md);
        assertTrue(md.contains("reached from states: `SAISIE`"), md);
        assertTrue(md.contains("view: inferred"), md);
        assertTrue(md.contains("source: link (WEB-INF/jsp/fragments/menu.jsp)"), md);
    }

    @Test
    void triggeringStates_produceAStateTransitionTable() {
        RouteOrigin origin = new RouteOrigin(List.of("state dispatch (CorbeilleUtils.js)"), Set.of(),
                new LinkedHashSet<>(List.of("CREATION", "TASK_1_FAP_INIT")), null);
        RouteNode node = new RouteNode("/view/achat/initfap", "ViewAchatInitfapPage", "Créer une FAP",
                null, false, List.of(), origin);

        String md = renderer.render(List.of(node), Map.of());

        assertTrue(md.contains("## State Transitions"), md);
        assertTrue(md.contains("| `/view/achat/initfap` | Créer une FAP | `CREATION`, `TASK_1_FAP_INIT` |"), md);
    }

    @Test
    void declaredRoutesWithoutOrigin_renderAsBefore_withNoTransitionTable() {
        String md = renderer.render(List.of(page("home", "HomeComponent", "Home")), Map.of());

        assertTrue(md.contains("- [home] Home: HomeComponent\n"), md);
        assertFalse(md.contains("State Transitions"));
        assertFalse(md.contains("source:"));
    }
}
