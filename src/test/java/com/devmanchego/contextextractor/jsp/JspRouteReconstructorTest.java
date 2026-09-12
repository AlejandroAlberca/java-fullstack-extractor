package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JspRouteReconstructorTest {

    @TempDir
    Path tempDir;

    private final JspRouteReconstructor reconstructor = new JspRouteReconstructor();

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private Path webappRoot() {
        return tempDir.resolve("src/main/webapp");
    }

    // -----------------------------------------------------------------------
    // Source 1: menu-derived tree — labels, hierarchy, authorisation
    // -----------------------------------------------------------------------

    @Test
    void menuLink_becomesALeafWithItsLabel() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <ul class="nav">
                <li><a href="${pageContext.request.contextPath}/view/recherche">Recherche EOTP</a></li>
                </ul>
                """);
        write("src/main/webapp/WEB-INF/jsp/recherche/recherche.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode leaf = findByPath(result.routes(), "/view/recherche");
        assertNotNull(leaf);
        assertEquals("Recherche EOTP", leaf.getTitle());
    }

    @Test
    void nestedDropdown_producesASectionWithChildren() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <ul class="nav">
                <li><a href="#" class="link-top">Achats</a>
                  <ul class="dropdown-menu">
                    <li><a href="${pageContext.request.contextPath}/view/achat/initfap">Créer une FAP</a></li>
                    <li><a href="${pageContext.request.contextPath}/view/achat/mestaches">Mes tâches</a></li>
                  </ul>
                </li>
                </ul>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/initfap.jsp", "<html></html>");
        write("src/main/webapp/WEB-INF/jsp/achat/mestaches.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode section = result.routes().stream()
                .filter(r -> "Achats".equals(r.getTitle())).findFirst().orElseThrow();
        assertFalse(section.isPage(), "A pure section (href=\"#\") must not itself be a page");
        assertEquals(2, section.getChildren().size());
        assertTrue(section.getChildren().stream().anyMatch(c -> "/view/achat/initfap".equals(c.getPath())));
    }

    @Test
    void secAuthorize_permissionsCaptured() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
                <ul class="nav">
                <sec:authorize access="hasPermission('','ACHAT')">
                <li><a href="${pageContext.request.contextPath}/view/achat/corbeille">Etapes en cours</a></li>
                </sec:authorize>
                </ul>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/corbeille.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertEquals(java.util.Set.of("ACHAT"), result.permissionsByUrl().get("/view/achat/corbeille"));
    }

    @Test
    void nestedSecAuthorize_bothPermissionsCaptured() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
                <sec:authorize access="hasPermission('','ACHAT')">
                <sec:authorize access="hasPermission('','INTERNE')">
                <a href="${pageContext.request.contextPath}/view/achat/historique">Historique</a>
                </sec:authorize>
                </sec:authorize>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/historique.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertEquals(java.util.Set.of("ACHAT", "INTERNE"), result.permissionsByUrl().get("/view/achat/historique"));
    }

    @Test
    void externalAndJavascriptLinks_neverBecomeRoutes() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="https://example.com">External</a>
                <a href="#" class="toggle">Toggle</a>
                <a href="javascript:void(0)">JS action</a>
                <a href="mailto:x@example.com">Mail</a>
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        // The never-included menu fragment is itself an unreferenced view; no link became a route.
        assertTrue(result.routes().stream().allMatch(r -> JspRouteReconstructor.UNREFERENCED_SECTION.equals(r.getTitle())));
    }

    // -----------------------------------------------------------------------
    // Source 2: broad URL literal harvesting
    // -----------------------------------------------------------------------

    @Test
    void urlNotInMenu_stillBecomesALeaf() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/mesfapdae">Mes FAP/DAE</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/mesfapdae.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertNotNull(findByPath(result.routes(), "/view/achat/mesfapdae"));
    }

    @Test
    void scriptUrlLiteral_withAppendedQueryString_stillHarvested() throws IOException {
        // The exact real-world shape this guards against: a navigation target built as
        // `basepath + '/view/reporting/rapport?' + requete` — the query-string '?' sits inside
        // the same quoted literal as the path, immediately before the closing quote.
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<html></html>");
        write("src/main/js/pages/AccueilRapport.js", """
                function go(requete) {
                    window.location = basepath + '/view/reporting/rapport?' + requete;
                }
                """);
        write("src/main/webapp/WEB-INF/jsp/reporting/rapport.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertNotNull(findByPath(result.routes(), "/view/reporting/rapport"));
    }

    @Test
    void scriptUrlLiteral_alsoHarvested() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<html></html>");
        write("src/main/js/pages/nav.js", """
                function go() {
                    window.location = basepath + '/view/achat/mestaches';
                }
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/mestaches.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertNotNull(findByPath(result.routes(), "/view/achat/mestaches"));
    }

    @Test
    void staticAssetReferences_neverBecomeRoutes() throws IOException {
        // link/script/img reference resources, not navigation — unlike <a href>, which Source 1
        // already scopes correctly by construction. A file extension is what actually
        // distinguishes a stylesheet or script from a JSP view's virtual MVC path.
        write("src/main/webapp/WEB-INF/jsp/fragments/header.jsp", """
                <link rel="stylesheet" href="${pageContext.request.contextPath}/assets/css/commons.css"/>
                <script src="${pageContext.request.contextPath}/assets/commons.js"></script>
                <img src="${pageContext.request.contextPath}/img/logo.png"/>
                """);
        write("src/main/webapp/WEB-INF/jsp/index.jsp",
                "<%@ include file=\"/WEB-INF/jsp/fragments/header.jsp\" %>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertNull(findByPath(result.routes(), "/assets/css/commons.css"));
        assertNull(findByPath(result.routes(), "/assets/commons.js"));
        assertNull(findByPath(result.routes(), "/img/logo.png"));
    }

    @Test
    void structuredMenuLink_takesPrecedenceOverAStrayInlineMentionOfTheSameUrl() throws IOException {
        // Realistic asymmetry: the real nav menu nests its link under a labelled dropdown
        // section; an unrelated page happens to also link the same URL as a flat, standalone
        // reference (e.g. a cross-reference button). The structured one wins.
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <li><a href="#">Achats</a>
                  <ul>
                    <li><a href="${pageContext.request.contextPath}/view/achat/corbeille">Etapes en cours</a></li>
                  </ul>
                </li>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/corbeille">see current steps</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/corbeille.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        long matches = countByPath(result.routes(), "/view/achat/corbeille");
        assertEquals(1, matches, "The same URL must not appear twice just because two files mention it");
        assertEquals("Etapes en cours", findByPath(result.routes(), "/view/achat/corbeille").getTitle());
    }

    @Test
    void twoEquallyFlatMentions_firstWins_conflictWarned() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/achat/a.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/shared">Label A</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/b.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/shared">Label B</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/shared.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        // achat/a.jsp sorts before achat/b.jsp — deterministic, stable precedence.
        assertEquals("Label A", findByPath(result.routes(), "/view/achat/shared").getTitle());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("two different labels")));
    }

    // -----------------------------------------------------------------------
    // Source 3: state-dispatch extraction
    // -----------------------------------------------------------------------

    @Test
    void stateDispatchSwitch_extractedAsStateToUrlMapping() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<html></html>");
        write("src/main/js/structure/CorbeilleUtils.js", """
                const CorbeilleUtils = {
                    calculerChemin: function (etatTache, idWorkflow) {
                        let retour;
                        switch (etatTache) {
                        case "CREATION":
                        case "TASK_1_FAP_INIT":
                            retour = basepath + "/view/achat/initfap";
                            break;
                        case "SAISIE":
                        case "CONTROLE_MANAGEMENT":
                            retour = Uri.initURL("/view/achat/fap", {id: idWorkflow});
                            break;
                        default:
                            retour = "";
                        }
                        return retour;
                    }
                };
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/initfap.jsp", "<html></html>");
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertEquals(java.util.Set.of("CREATION", "TASK_1_FAP_INIT"),
                result.triggeringStatesByUrl().get("/view/achat/initfap"));
        assertEquals(java.util.Set.of("SAISIE", "CONTROLE_MANAGEMENT"),
                result.triggeringStatesByUrl().get("/view/achat/fap"));
        assertNotNull(findByPath(result.routes(), "/view/achat/initfap"),
                "A URL reached only via state dispatch must still appear in the tree");
    }

    // -----------------------------------------------------------------------
    // Source 4: web.xml
    // -----------------------------------------------------------------------

    @Test
    void webXmlErrorPage_becomesARoute() throws IOException {
        write("src/main/webapp/WEB-INF/web.xml", """
                <web-app>
                <error-page><error-code>403</error-code><location>/error/403</location></error-page>
                </web-app>
                """);
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode errorsSection = result.routes().stream()
                .filter(r -> "Errors".equals(r.getTitle())).findFirst().orElseThrow();
        assertTrue(errorsSection.getChildren().stream().anyMatch(c -> "/error/403".equals(c.getPath())));
    }

    @Test
    void webXmlDuplicatingAMenuUrl_menuWins_conflictWarned() throws IOException {
        write("src/main/webapp/WEB-INF/web.xml", """
                <web-app>
                <error-page><error-code>404</error-code><location>/view/recherche</location></error-page>
                </web-app>
                """);
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/recherche">Recherche EOTP</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/recherche/recherche.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertEquals("Recherche EOTP", findByPath(result.routes(), "/view/recherche").getTitle());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("web.xml declares")));
    }

    // -----------------------------------------------------------------------
    // Source 5: URL -> file resolution (convention + fallback)
    // -----------------------------------------------------------------------

    @Test
    void directConvention_resolvesWithoutInferredWarning() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/fap">FAP</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        ComponentInfo comp = result.componentsByName().get("ViewAchatFapPage");
        assertNotNull(comp);
        assertTrue(comp.getFilePath().replace('\\', '/').endsWith("achat/fap.jsp"));
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("inferred")));
    }

    @Test
    void directoryIndexFallback_resolvesAndIsFlaggedInferred() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/recherche">Recherche</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/recherche/recherche.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        ComponentInfo comp = result.componentsByName().get("ViewRecherchePage");
        assertNotNull(comp);
        assertTrue(comp.getFilePath().replace('\\', '/').endsWith("recherche/recherche.jsp"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("directory-index fallback")));
    }

    @Test
    void unresolvableUrl_keptInTreeWithoutAFile_warned() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/logout">Déconnexion</a>
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode leaf = findByPath(result.routes(), "/logout");
        assertNotNull(leaf, "The route must be kept even though no JSP file backs it");
        ComponentInfo comp = result.componentsByName().get(leaf.getComponentName());
        assertEquals("(unresolved)", comp.getFilePath());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("could not be resolved")));
    }

    // -----------------------------------------------------------------------
    // Acceptance criteria
    // -----------------------------------------------------------------------

    @Test
    void everyJspView_isEitherPlacedOrReportedUnreferenced() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/fap">FAP</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp",
                "<%@ include file=\"/WEB-INF/jsp/fragments/messages.jsp\" %>");
        write("src/main/webapp/WEB-INF/jsp/fragments/messages.jsp", "<div>messages</div>");
        Path orphan = write("src/main/webapp/WEB-INF/jsp/orphan.jsp", "<html>never linked</html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        // fap.jsp: resolved as a route's file. messages.jsp: referenced via static include.
        // orphan.jsp: reached by neither -- must be explicitly reported, not silently absent.
        assertTrue(result.warnings().stream().anyMatch(w ->
                w.contains("Unreferenced JSP view") && w.contains("orphan.jsp")));
        assertTrue(result.warnings().stream().noneMatch(w ->
                w.contains("Unreferenced JSP view") && w.contains("fap.jsp")));
        assertTrue(result.warnings().stream().noneMatch(w ->
                w.contains("Unreferenced JSP view") && w.contains("messages.jsp")));
    }

    @Test
    void everyRouteNode_carriesTheSourceThatProducedIt() throws IOException {
        // Indirect but concrete check: a route's provenance is recoverable from the warnings/
        // maps this reconstruction produces for each mechanism (menu label presence => menu
        // source; entries in triggeringStatesByUrl => state-dispatch source; an inferred
        // warning => fallback-resolved source) -- there is always a traceable "why is this here".
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/fap">FAP</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<html></html>");
        write("src/main/webapp/WEB-INF/jsp/achat/orphanTarget.jsp", "<html></html>");
        write("src/main/js/x.js", "var u = '/view/achat/orphanTarget';");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        // The menu-sourced route has a real label (its source).
        assertEquals("FAP", findByPath(result.routes(), "/view/achat/fap").getTitle());
        // The broad-harvest-sourced route has no menu label -- the URL itself, honestly.
        assertEquals("/view/achat/orphanTarget", findByPath(result.routes(), "/view/achat/orphanTarget").getTitle());
    }

    // -----------------------------------------------------------------------
    // Opus review: precedence, filters, web.xml shapes, welcome/forward, includes, provenance
    // -----------------------------------------------------------------------

    @Test
    void menuEntryWins_evenWhenAnIdenticallyLabelledFlatMentionIsFoundFirst() throws IOException {
        // achat/ sorts before fragments/: the flat link, with the very same label, is seen first.
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/corbeille">Etapes en cours</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
                <ul><li><a href="#">Achats</a>
                  <ul><li><sec:authorize access="hasPermission('','ACHAT')"><a href="${pageContext.request.contextPath}/view/achat/corbeille">Etapes en cours</a></sec:authorize></li></ul>
                </li></ul>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/corbeille.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode section = result.routes().stream().filter(r -> "Achats".equals(r.getTitle())).findFirst().orElseThrow();
        assertEquals("/view/achat/corbeille", section.getChildren().get(0).getPath());
        assertEquals(java.util.Set.of("ACHAT"), result.permissionsByUrl().get("/view/achat/corbeille"));
    }

    @Test
    void linkToAStaticDocument_neverBecomesARoute() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/docs/guide.pdf">User guide</a>
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertNull(findByPath(result.routes(), "/docs/guide.pdf"));
    }

    @Test
    void urlWithRuntimeElInItsPath_skippedRatherThanInvented() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <a href="${pageContext.request.contextPath}/view/${type}/edit">Edit</a>
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertTrue(result.originsByUrl().keySet().stream().noneMatch(u -> u.contains("$") || u.contains("{")),
                result.originsByUrl().keySet().toString());
    }

    @Test
    void webXmlErrorPages_everyShape_commentsIgnored() throws IOException {
        write("src/main/webapp/WEB-INF/web.xml", """
                <web-app>
                <error-page><location>/error/404</location><error-code>404</error-code></error-page>
                <error-page><exception-type>java.lang.IllegalStateException</exception-type><location>/error/state</location></error-page>
                <error-page><location>/error/500</location></error-page>
                <error-page><error-code>500</error-code><location>/error/500</location></error-page>
                <!-- <error-page><error-code>418</error-code><location>/error/teapot</location></error-page> -->
                </web-app>
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode errors = result.routes().stream().filter(r -> "Errors".equals(r.getTitle())).findFirst().orElseThrow();
        assertEquals("Error 404", findByPath(errors.getChildren(), "/error/404").getTitle(), "children in either order");
        assertEquals("Error: IllegalStateException", findByPath(errors.getChildren(), "/error/state").getTitle());
        assertEquals("Error (default) / Error 500", findByPath(errors.getChildren(), "/error/500").getTitle());
        assertNull(findByPath(result.routes(), "/error/teapot"), "A commented-out mapping is not a mapping");
    }

    @Test
    void webXmlLocationNamingAJsp_resolvesExactly() throws IOException {
        write("src/main/webapp/WEB-INF/web.xml", """
                <web-app><error-page><error-code>404</error-code><location>/WEB-INF/jsp/errors/404.jsp</location></error-page></web-app>
                """);
        Path jsp = write("src/main/webapp/WEB-INF/jsp/errors/404.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode leaf = findByPath(result.routes(), "/WEB-INF/jsp/errors/404.jsp");
        assertNotNull(leaf);
        assertEquals(jsp.toAbsolutePath().normalize(),
                Path.of(result.componentsByName().get(leaf.getComponentName()).getFilePath()));
        assertNull(leaf.getOrigin().viewMappingNote(), "An explicit .jsp location is an exact mapping");
        assertTrue(result.unreferencedViews().isEmpty());
    }

    @Test
    void welcomeFile_answersTheRootUrl_andItsForwardTargetIsHarvested() throws IOException {
        write("src/main/webapp/WEB-INF/web.xml", """
                <web-app><welcome-file-list><welcome-file>index.jsp</welcome-file></welcome-file-list></web-app>
                """);
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<jsp:forward page=\"/home\"/>");
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/"><img src="logo.png"/></a>
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode root = findByPath(result.routes(), "/");
        assertNotNull(root);
        assertEquals("Welcome page", root.getTitle());
        assertTrue(result.componentsByName().get(root.getComponentName()).getFilePath()
                .replace('\\', '/').endsWith("WEB-INF/jsp/index.jsp"));
        assertTrue(root.getOrigin().viewMappingNote().startsWith("inferred"));
        assertNull(findByPath(result.routes(), "/index"), "The welcome file answers '/', not a URL of its own name");

        RouteNode home = findByPath(result.routes(), "/home");
        assertNotNull(home, "A <jsp:forward> target is a navigation target");
        assertTrue(home.getOrigin().sources().stream().anyMatch(s -> s.startsWith("jsp:forward")));
    }

    @Test
    void dynamicIncludeTarget_isNotReportedUnreferenced() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/fap">FAP</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<jsp:include page=\"/WEB-INF/jsp/fragments/footer.jsp\"/>");
        write("src/main/webapp/WEB-INF/jsp/fragments/footer.jsp", "<footer>f</footer>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertTrue(result.unreferencedViews().stream().noneMatch(p -> p.endsWith("footer.jsp")),
                result.unreferencedViews().toString());
    }

    @Test
    void ajaxEndpointLiterals_neverBecomePages_butNavigationSpaceLiteralsDo() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/fap">FAP</a>
                """);
        write("src/main/js/pages/fap.js", """
                $.post(basepath + '/workflow/valider', data);
                $.getJSON(basepath + '/utilisateur/courant');
                window.location = basepath + '/view/achat/mesfapdae';
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertNull(findByPath(result.routes(), "/workflow/valider"));
        assertNull(findByPath(result.routes(), "/utilisateur/courant"));
        assertNotNull(findByPath(result.routes(), "/view/achat/mesfapdae"),
                "Same first segment as the application's own links: a page, even with no JSP behind it");
    }

    @Test
    void constantCaseLabels_alsoRecognisedAsStates() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<html></html>");
        write("src/main/js/nav.js", """
                switch (etat) {
                    case Etat.SAISIE:
                    case Etat.CONTROLE:
                        window.location = basepath + '/view/achat/fap';
                        break;
                }
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertEquals(java.util.Set.of("Etat.SAISIE", "Etat.CONTROLE"), result.triggeringStatesByUrl().get("/view/achat/fap"));
    }

    @Test
    void everyRouteNode_carriesARouteOrigin_namingItsSources() throws IOException {
        write("src/main/webapp/WEB-INF/web.xml", """
                <web-app><error-page><error-code>403</error-code><location>/error/403</location></error-page></web-app>
                """);
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/fap">FAP</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<%@ include file=\"/WEB-INF/jsp/fragments/menu.jsp\" %>");
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<html></html>");
        write("src/main/webapp/WEB-INF/jsp/achat/initfap.jsp", "<html></html>");
        write("src/main/js/CorbeilleUtils.js", """
                switch (e) { case "CREATION": retour = "/view/achat/initfap"; break; }
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        List<RouteNode> leaves = new java.util.ArrayList<>();
        collectLeaves(result.routes(), leaves);
        assertFalse(leaves.isEmpty());
        for (RouteNode leaf : leaves) {
            assertNotNull(leaf.getOrigin(), leaf.getPath());
            assertFalse(leaf.getOrigin().sources().isEmpty(), leaf.getPath());
        }
        assertEquals(List.of("link (WEB-INF/jsp/fragments/menu.jsp)"),
                findByPath(result.routes(), "/view/achat/fap").getOrigin().sources());
        RouteNode initfap = findByPath(result.routes(), "/view/achat/initfap");
        assertTrue(initfap.getOrigin().sources().contains("state dispatch (src/main/js/CorbeilleUtils.js)"),
                initfap.getOrigin().sources().toString());
        assertEquals(java.util.Set.of("CREATION"), initfap.getOrigin().triggeringStates(),
                "Triggering states are on the node itself, not only in a side map");
        assertTrue(findByPath(result.routes(), "/error/403").getOrigin().sources().get(0).startsWith("web.xml error-page"));
    }

    @Test
    void unreferencedViews_placedInTheTree_underTheirOwnSection() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/errors/400.jsp", "<html></html>");

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        RouteNode section = result.routes().get(result.routes().size() - 1);
        assertEquals(JspRouteReconstructor.UNREFERENCED_SECTION, section.getTitle());
        RouteNode leaf = section.getChildren().get(0);
        assertEquals("WEB-INF/jsp/errors/400.jsp", leaf.getTitle());
        assertTrue(leaf.isPage(), "Still a page: its fields and actions get documented like any other view");
        assertEquals("Errors400Page", leaf.getComponentName());
    }

    @Test
    void collidingSyntheticNames_neverShareAComponent() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat-fap">One</a>
                <a href="${pageContext.request.contextPath}/view/achat/fap">Two</a>
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        String a = findByPath(result.routes(), "/view/achat-fap").getComponentName();
        String b = findByPath(result.routes(), "/view/achat/fap").getComponentName();
        assertNotEquals(a, b);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("same synthetic page name")));
    }

    @Test
    void ajaxEndpointSharingAViewName_isNotAPage_whenLinksDefineTheNavigationSpace() throws IOException {
        // Real shape: a DataTables ajax url that happens to match reporting/historique.jsp,
        // while the page itself lives at /view/reporting/historique.
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/reporting/historique">Suivi des actions</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/reporting/historique.jsp", "<html></html>");
        write("src/main/js/pages/RapportHistorique.js", """
                table.DataTable({ ajax: { url: basepath + '/reporting/historique/' + encodeURIComponent(p) } });
                """);

        var result = reconstructor.reconstruct(tempDir, webappRoot());

        assertNull(findByPath(result.routes(), "/reporting/historique"));
        assertNotNull(findByPath(result.routes(), "/view/reporting/historique"));
    }

    private void collectLeaves(List<RouteNode> routes, List<RouteNode> acc) {
        for (RouteNode r : routes) {
            if (r.getChildren().isEmpty()) acc.add(r);
            collectLeaves(r.getChildren(), acc);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RouteNode findByPath(List<RouteNode> routes, String path) {
        for (RouteNode r : routes) {
            if (path.equals(r.getPath())) return r;
            RouteNode found = findByPath(r.getChildren(), path);
            if (found != null) return found;
        }
        return null;
    }

    private long countByPath(List<RouteNode> routes, String path) {
        long count = routes.stream().filter(r -> path.equals(r.getPath())).count();
        for (RouteNode r : routes) count += countByPath(r.getChildren(), path);
        return count;
    }
}
