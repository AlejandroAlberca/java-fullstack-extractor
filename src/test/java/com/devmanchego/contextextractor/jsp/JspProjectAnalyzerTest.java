package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.model.ServiceInfo;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JspProjectAnalyzerTest {

    @TempDir
    Path tempDir;

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void reconstructedPages_becomeComponentsAndRoutes_keyedToTheirJspFile() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <ul><li><a href="#">Achats</a>
                  <ul><li><a href="${pageContext.request.contextPath}/view/achat/fap">Créer une FAP</a></li></ul>
                </li></ul>
                """);
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<%@ include file=\"/WEB-INF/jsp/fragments/menu.jsp\" %>");
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<html></html>");

        AngularProject project = new JspProjectAnalyzer().analyze(tempDir);

        assertEquals(FrontendFramework.JSP_JQUERY, project.getFramework());
        ComponentInfo fap = project.getComponents().stream()
                .filter(c -> "ViewAchatFapPage".equals(c.getClassName())).findFirst().orElseThrow();
        assertTrue(fap.getFilePath().replace('\\', '/').endsWith("WEB-INF/jsp/achat/fap.jsp"));
        RouteNode section = project.getRoutes().stream()
                .filter(r -> "Achats".equals(r.getTitle())).findFirst().orElseThrow();
        assertEquals("/view/achat/fap", section.getChildren().get(0).getPath());
    }

    @Test
    void boundView_carriesItsHttpCallsAsAServiceThroughTheSharedModel() throws IOException {
        // End-to-end: Phase 04 places the route, Phase 05 binds it to its bundle and populates
        // getServices()/injectedServices — with no bridging code required by this test.
        write("webpack.config.js", """
                module.exports = { entry: { "fap": './src/main/js/pages/fap.js' },
                                    output: { filename: '[name].js' } };
                """);
        write("src/main/js/pages/fap.js", "$.ajax({url: '/workflow/list', method: 'GET'});");
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp",
                "<script src=\"${pageContext.request.contextPath}/assets/fap.js\"></script>");
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/fap">FAP</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<%@ include file=\"/WEB-INF/jsp/fragments/menu.jsp\" %>");

        AngularProject project = new JspProjectAnalyzer().analyze(tempDir);

        ComponentInfo fap = project.getComponents().stream()
                .filter(c -> "ViewAchatFapPage".equals(c.getClassName())).findFirst().orElseThrow();
        assertEquals(List.of("ViewAchatFapPageBundle"), fap.getInjectedServices());
        ServiceInfo bundle = project.getServices().stream()
                .filter(s -> "ViewAchatFapPageBundle".equals(s.getClassName())).findFirst().orElseThrow();
        assertEquals("/workflow/list", bundle.getHttpCalls().get(0).getUrlTemplate());
    }

    @Test
    void backendControllers_resolveViewsExactly_andPromoteAnUnreferencedOne() throws IOException {
        // End-to-end Phase 07: a URL the naming convention left unresolved gets an exact file
        // from the controller, and a JSP the frontend never links surfaces as reachable through it.
        write("src/main/webapp/WEB-INF/jsp/fragments/menu.jsp", """
                <a href="${pageContext.request.contextPath}/view/achat/fap">FAP</a>
                """);
        write("src/main/webapp/WEB-INF/jsp/index.jsp", "<%@ include file=\"/WEB-INF/jsp/fragments/menu.jsp\" %>");
        write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<html></html>");
        write("src/main/webapp/WEB-INF/jsp/errors/400.jsp", "<html>never linked</html>");

        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        List<CompilationUnit> backendCUs = List.of(
                StaticJavaParser.parse("""
                        package fr.edf.efapha.web.controller.structure;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.ui.ModelMap;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        import org.springframework.web.bind.annotation.RequestMethod;
                        @Controller
                        @RequestMapping("/view/achat")
                        public class ViewAchatController {
                            @RequestMapping(value = "/{partie}", method = RequestMethod.GET)
                            public String partie(@PathVariable String partie, ModelMap map) {
                                return "achat/" + partie;
                            }
                        }
                        """),
                StaticJavaParser.parse("""
                        package fr.edf.efapha.web.controller.structure;
                        import org.springframework.stereotype.Controller;
                        import org.springframework.ui.ModelMap;
                        import org.springframework.web.bind.annotation.PathVariable;
                        import org.springframework.web.bind.annotation.RequestMapping;
                        import org.springframework.web.bind.annotation.RequestMethod;
                        @Controller
                        @RequestMapping("/error")
                        public class ErrorController {
                            @RequestMapping(value = "/{code}", method = RequestMethod.GET)
                            public String domaine(@PathVariable String code, ModelMap map) {
                                return "errors/" + code;
                            }
                        }
                        """));

        AngularProject project = new JspProjectAnalyzer().analyze(tempDir, backendCUs);

        RouteNode fap = findByPath(project.getRoutes(), "/view/achat/fap");
        assertNotNull(fap);
        assertNull(fap.getOrigin().viewMappingNote());
        assertTrue(fap.getOrigin().sources().stream().anyMatch(s -> s.contains("ViewAchatController#partie()")));

        RouteNode promoted = findByPath(project.getRoutes(), "/error/400");
        assertNotNull(promoted, "the unreferenced errors/400.jsp must surface via ErrorController");
        ComponentInfo promotedComp = project.getComponents().stream()
                .filter(c -> c.getClassName().equals(promoted.getComponentName())).findFirst().orElseThrow();
        assertTrue(promotedComp.getFilePath().replace('\\', '/').endsWith("WEB-INF/jsp/errors/400.jsp"));
    }

    private RouteNode findByPath(List<RouteNode> routes, String path) {
        for (RouteNode r : routes) {
            if (path.equals(r.getPath())) return r;
            RouteNode found = findByPath(r.getChildren(), path);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void noWebappRoot_producesAnEmptyModel_neverThrows() throws IOException {
        write("src/app.js", "console.log(1);");

        AngularProject project = new JspProjectAnalyzer().analyze(tempDir);

        assertTrue(project.isEmpty());
    }

    @Test
    void buildOutput_ignoredWhenLocatingTheWebappRoot() throws IOException {
        // "build" sorts before "src": without the exclusion, the stale copy would win.
        write("build/exploded/WEB-INF/jsp/stale.jsp", "<html></html>");
        write("src/main/webapp/WEB-INF/jsp/real.jsp", "<html></html>");

        Path root = JspProjectAnalyzer.findWebappRoot(tempDir);

        assertEquals(tempDir.resolve("src/main/webapp").toAbsolutePath().normalize(), root.toAbsolutePath().normalize());
    }
}
