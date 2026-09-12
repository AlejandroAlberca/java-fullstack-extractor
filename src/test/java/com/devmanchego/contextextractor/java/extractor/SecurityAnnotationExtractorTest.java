package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies P1's core fix: a class-level @PreAuthorize/@Secured/@RolesAllowed annotation is
 * inherited by every handler method that doesn't declare its own, with the source correctly
 * attributed for downstream reporting.
 */
class SecurityAnnotationExtractorTest {

    private final SpringEndpointExtractor extractor = new SpringEndpointExtractor();
    private final JavaParser parser = new JavaParser();

    @Test
    void classLevelPreAuthorize_inheritedByMethodWithNoOwnAnnotation() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController
            @RequestMapping("/acheteur")
            @PreAuthorize("hasPermission('', 'ACHAT')")
            public class AcheteurController {
                @GetMapping
                public java.util.List<Object> getAll() { return null; }
            }
            """;

        EndpointInfo endpoint = parseOne(source);

        assertTrue(endpoint.hasSecurityAnnotation());
        assertTrue(endpoint.hasAnnotation("PreAuthorize"));
        assertEquals(EndpointInfo.AnnotationSource.CLASS, endpoint.getAnnotationSource("PreAuthorize"));
        assertEquals("hasPermission('', 'ACHAT')", endpoint.getAnnotationValue("PreAuthorize"));
    }

    @Test
    void methodLevelAnnotation_overridesClassLevelOfSameType() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController
            @RequestMapping("/acheteur")
            @PreAuthorize("hasPermission('', 'ACHAT')")
            public class AcheteurController {
                @GetMapping("/admin-only")
                @PreAuthorize("hasRole('ADMIN')")
                public java.util.List<Object> adminOnly() { return null; }
            }
            """;

        EndpointInfo endpoint = parseOne(source);

        assertEquals(EndpointInfo.AnnotationSource.METHOD, endpoint.getAnnotationSource("PreAuthorize"));
        assertEquals("hasRole('ADMIN')", endpoint.getAnnotationValue("PreAuthorize"));
    }

    @Test
    void noClassOrMethodAnnotation_endpointCarriesNoSecurityAnnotation() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/public")
            public class PublicController {
                @GetMapping
                public String ping() { return "ok"; }
            }
            """;

        EndpointInfo endpoint = parseOne(source);

        assertFalse(endpoint.hasSecurityAnnotation());
    }

    @Test
    void classLevelAnnotation_appliesToEveryMethodInTheClass() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @RestController
            @RequestMapping("/admin")
            @PreAuthorize("hasPermission('', 'ADMINISTRATION')")
            public class AdminController {
                @GetMapping("/a")
                public String a() { return null; }
                @GetMapping("/b")
                public String b() { return null; }
            }
            """;

        List<EndpointInfo> endpoints = extractor.extract(parse(source), "AdminController.java");

        assertEquals(2, endpoints.size());
        for (EndpointInfo ep : endpoints) {
            assertTrue(ep.hasSecurityAnnotation(), ep.getPathTemplate() + " should inherit class-level rule");
            assertEquals(EndpointInfo.AnnotationSource.CLASS, ep.getAnnotationSource("PreAuthorize"));
        }
    }

    @Test
    void requestMappingMethodStyle_alsoInheritsClassLevelAnnotation() {
        // Covers SpringEndpointExtractor's second code path: @RequestMapping(method=...)
        // on the handler method, as opposed to @GetMapping/@PostMapping/etc.
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.security.access.prepost.PreAuthorize;
            @Controller
            @RequestMapping("/view/achat")
            @PreAuthorize("hasPermission('', 'ACHAT')")
            public class ViewAchatController {
                @RequestMapping(value = "/{partie}", method = RequestMethod.GET)
                public String partie(@PathVariable String partie) { return "achat/" + partie; }
            }
            """;

        EndpointInfo endpoint = parseOne(source);

        assertTrue(endpoint.hasSecurityAnnotation());
        assertEquals(EndpointInfo.AnnotationSource.CLASS, endpoint.getAnnotationSource("PreAuthorize"));
    }

    private EndpointInfo parseOne(String source) {
        List<EndpointInfo> result = extractor.extract(parse(source), "TestFile.java");
        assertEquals(1, result.size(), "Expected exactly one endpoint in: " + result);
        return result.get(0);
    }

    private CompilationUnit parse(String source) {
        ParseResult<CompilationUnit> pr = parser.parse(source);
        assertTrue(pr.isSuccessful(), "Parse failed: " + pr.getProblems());
        return pr.getResult().orElseThrow();
    }
}
