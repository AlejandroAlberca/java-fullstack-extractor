package com.devmanchego.contextextractor.java.mvc;

import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.devmanchego.contextextractor.java.mvc.SpringViewNameResolver.ViewResolverConfig;
import com.devmanchego.contextextractor.java.mvc.SpringViewNameResolver.ViewRoute;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpringViewNameResolverTest {

    private final SpringViewNameResolver resolver = new SpringViewNameResolver();

    private CompilationUnit parse(String source) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        return StaticJavaParser.parse(source);
    }

    private List<ViewRoute> extract(String source) {
        return resolver.extract(List.of(parse(source)));
    }

    private ViewRoute only(String source) {
        List<ViewRoute> routes = extract(source);
        assertEquals(1, routes.size(), routes.toString());
        return routes.get(0);
    }

    // -----------------------------------------------------------------------
    // View-returning vs body-returning — the class of methods this phase must not misclassify
    // -----------------------------------------------------------------------

    @Test
    void literalViewName_isResolved() {
        ViewRoute route = only("""
                @Controller
                @RequestMapping("/view/achat")
                public class ViewAchatController {
                    @RequestMapping(method = RequestMethod.GET)
                    public String domaine(ModelMap map) { return "achat/achat"; }
                }
                """);

        assertTrue(route.resolved());
        assertEquals(HttpVerb.GET, route.verb());
        assertEquals("/view/achat", route.urlTemplate());
        assertEquals(List.of("achat/achat"), route.viewNameTemplates());
    }

    @Test
    void concatenationWithAPathVariable_becomesATemplate_usingItsBoundSegmentName() {
        ViewRoute route = only("""
                @Controller
                @RequestMapping("/view/achat")
                public class ViewAchatController {
                    @RequestMapping(value = "/{partie}", method = RequestMethod.GET)
                    public String partie(@PathVariable String partie, ModelMap map) { return "achat/" + partie; }
                }
                """);

        assertEquals("/view/achat/{partie}", route.urlTemplate());
        assertEquals(List.of("achat/{partie}"), route.viewNameTemplates());
    }

    @Test
    void pathVariableWithAnExplicitBoundName_usesThatNameAsThePlaceholder() {
        ViewRoute route = only("""
                @Controller
                @RequestMapping("/error")
                public class ErrorController {
                    @RequestMapping(value = "/{code}", method = RequestMethod.GET)
                    public String show(@PathVariable("code") String c, ModelMap m) { return "errors/" + c; }
                }
                """);

        assertEquals("/error/{code}", route.urlTemplate());
        assertEquals(List.of("errors/{code}"), route.viewNameTemplates());
    }

    @Test
    void staticFinalFieldReturned_isResolvedToItsLiteralValue() {
        ViewRoute route = only("""
                @Controller
                @RequestMapping("/")
                public class ViewDefaultPageController {
                    public static final String PAGE_RECHERCHE = "recherche/recherche";
                    @RequestMapping(value = "", method = RequestMethod.GET)
                    public String defaultPage() { return PAGE_RECHERCHE; }
                }
                """);

        assertEquals("/", route.urlTemplate());
        assertEquals(List.of("recherche/recherche"), route.viewNameTemplates());
    }

    @Test
    void voidReturningMethod_isNeverAViewRoute() {
        // The real shape: a @Controller class whose methods stream a file directly.
        List<ViewRoute> routes = extract("""
                @Controller
                @RequestMapping("/telechargement")
                public class FileController {
                    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
                    public void getFile(@PathVariable Long id, HttpServletResponse response) { }
                }
                """);

        assertTrue(routes.isEmpty());
    }

    @Test
    void responseBodyMethod_onAControllerClass_isNotAView() {
        // A @Controller can still mix in a JSON endpoint via @ResponseBody — that method serves a
        // body, not a view, even though its return type happens to be String too.
        List<ViewRoute> routes = extract("""
                @Controller
                @RequestMapping("/api")
                public class MixedController {
                    @ResponseBody
                    @RequestMapping(value = "/status", method = RequestMethod.GET)
                    public String status() { return "OK"; }
                }
                """);

        assertTrue(routes.isEmpty());
    }

    @Test
    void restController_isNeverConsidered() {
        List<ViewRoute> routes = extract("""
                @RestController
                @RequestMapping("/api")
                public class ApiController {
                    @RequestMapping(value = "/x", method = RequestMethod.GET)
                    public String x() { return "literal-but-irrelevant"; }
                }
                """);

        assertTrue(routes.isEmpty());
    }

    @Test
    void nonStringReturnType_isNeverAViewRoute() {
        List<ViewRoute> routes = extract("""
                @Controller
                @RequestMapping("/x")
                public class X {
                    @RequestMapping(method = RequestMethod.GET)
                    public ModelAndView index() { return new ModelAndView("x/index"); }
                }
                """);

        assertTrue(routes.isEmpty());
    }

    @Test
    void getMappingAndVerbShortcuts_areRecognisedLikeRequestMapping() {
        List<ViewRoute> routes = extract("""
                @Controller
                public class X {
                    @GetMapping("/a")
                    public String a() { return "a"; }
                    @PostMapping("/b")
                    public String b() { return "b"; }
                }
                """);

        assertEquals(2, routes.size());
        assertEquals(HttpVerb.GET, routes.get(0).verb());
        assertEquals(HttpVerb.POST, routes.get(1).verb());
    }

    // -----------------------------------------------------------------------
    // Unresolvable return values — reported, never guessed
    // -----------------------------------------------------------------------

    @Test
    void computedViewName_isReportedUnresolved_methodStillReturnedForItsUrlTemplate() {
        ViewRoute route = only("""
                @Controller
                @RequestMapping("/x")
                public class X {
                    @RequestMapping(method = RequestMethod.GET)
                    public String go(HttpServletRequest req) { return computeView(req); }
                }
                """);

        assertFalse(route.resolved());
        assertEquals("/x", route.urlTemplate());
        assertEquals(1, route.unresolvedReturns().size());
    }

    @Test
    void oneResolvedBranchAndOneComputedBranch_bothReported() {
        ViewRoute route = only("""
                @Controller
                @RequestMapping("/x")
                public class X {
                    @RequestMapping(method = RequestMethod.GET)
                    public String go(boolean admin) {
                        if (admin) return "admin/index";
                        return computeView();
                    }
                }
                """);

        assertTrue(route.resolved());
        assertEquals(List.of("admin/index"), route.viewNameTemplates());
        assertEquals(1, route.unresolvedReturns().size());
    }

    @Test
    void concatenationWithAnUnboundIdentifier_isUnresolved() {
        ViewRoute route = only("""
                @Controller
                @RequestMapping("/x")
                public class X {
                    @RequestMapping(method = RequestMethod.GET)
                    public String go(Model model) { return "prefix/" + model.someField; }
                }
                """);

        assertFalse(route.resolved());
    }

    // -----------------------------------------------------------------------
    // Resolver config discovery
    // -----------------------------------------------------------------------

    @Test
    void noConfigurationFound_fallsBackToTheWebInfJspDefault() {
        ViewResolverConfig config = resolver.resolveConfig(List.of(parse("public class X {}")));

        assertEquals("/WEB-INF/jsp/", config.prefix());
        assertEquals(".jsp", config.suffix());
        assertTrue(config.assumedDefault());
    }

    @Test
    void configuredResolverBean_isDiscovered() {
        ViewResolverConfig config = resolver.resolveConfig(List.of(parse("""
                public class WebConfig {
                    @Bean
                    public ViewResolver viewResolver() {
                        InternalResourceViewResolver r = new InternalResourceViewResolver();
                        r.setPrefix("/WEB-INF/views/");
                        r.setSuffix(".jspx");
                        return r;
                    }
                }
                """)));

        assertEquals("/WEB-INF/views/", config.prefix());
        assertEquals(".jspx", config.suffix());
        assertFalse(config.assumedDefault());
    }
}
