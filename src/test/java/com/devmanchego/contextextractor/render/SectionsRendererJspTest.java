package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Reconstructed JSP trees: pathless titled sections holding absolute page URLs. */
class SectionsRendererJspTest {

    @Test
    void absoluteChildPaths_underAPathlessMenuSection_areNotDoubleSlashed() {
        RouteNode section = new RouteNode("", null, "Achats", null, false, List.of(
                new RouteNode("/view/achat/fap", "ViewAchatFapPage", "Créer une FAP", null, false, List.of())));
        AngularProject project = new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR, FrontendFramework.JSP_JQUERY,
                List.of(new ComponentInfo("ViewAchatFapPage", "/x/achat/fap.jsp", null, List.of())),
                List.of(), List.of(), List.of(section));

        String md = new SectionsRenderer().render(project, new EndpointMatcher.MatchResult(List.of(), List.of(), List.of()));

        assertFalse(md.contains("//view"), md);
        assertTrue(md.contains("`/view/achat/fap`"), md);
        assertFalse(md.contains("Achats — `/`"), "A menu section has no URL of its own:\n" + md);
    }
}
