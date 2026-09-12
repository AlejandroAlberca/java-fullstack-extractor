package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpringEndpointExtractorTest {

    private SpringEndpointExtractor extractor;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        extractor = new SpringEndpointExtractor();
        parser = new JavaParser();
    }

    @Test
    void simpleCrudController_extractsAllVerbs() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/products")
            public class ProductController {
                @GetMapping
                public List<ProductDto> findAll() { return null; }
                @GetMapping("/{id}")
                public ResponseEntity<ProductDto> findById(@PathVariable Long id) { return null; }
                @PostMapping
                public ResponseEntity<ProductDto> create(@RequestBody CreateProductRequest req) { return null; }
                @PutMapping("/{id}")
                public ResponseEntity<ProductDto> update(@PathVariable Long id, @RequestBody UpdateProductRequest req) { return null; }
                @DeleteMapping("/{id}")
                public ResponseEntity<Void> delete(@PathVariable Long id) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(5, result.size());
        assertVerb(result, HttpVerb.GET, "/api/products");
        assertVerb(result, HttpVerb.GET, "/api/products/{id}");
        assertVerb(result, HttpVerb.POST, "/api/products");
        assertVerb(result, HttpVerb.PUT, "/api/products/{id}");
        assertVerb(result, HttpVerb.DELETE, "/api/products/{id}");
    }

    @Test
    void responseEntityUnwrapping_stripsWrapper() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class MyController {
                @GetMapping("/items/{id}")
                public ResponseEntity<ItemDto> get(@PathVariable Long id) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(1, result.size());
        assertEquals("ItemDto", result.get(0).getResponseType());
    }

    @Test
    void monoUnwrapping_stripsReactiveWrapper() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class ReactiveController {
                @GetMapping("/items")
                public Mono<ItemDto> get() { return null; }
                @GetMapping("/all")
                public Flux<ItemDto> getAll() { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(2, result.size());
        assertEquals("ItemDto", result.get(0).getResponseType());
        assertEquals("ItemDto[]", result.get(1).getResponseType());
    }

    @Test
    void nestedRequestMappingPrefixes_composePath() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/v2/orders")
            public class OrderController {
                @GetMapping("/pending")
                public List<OrderDto> pending() { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(1, result.size());
        assertEquals("/api/v2/orders/pending", result.get(0).getPathTemplate());
    }

    @Test
    void requestBodyExtraction_identifiesBodyType() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class UserController {
                @PostMapping("/users")
                public UserDto create(@RequestBody CreateUserRequest req) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals("CreateUserRequest", result.get(0).getBodyParameterType());
    }

    @Test
    void nonControllerClass_producesNoEndpoints() {
        String source = """
            package com.example;
            public class PlainService {
                public void doSomething() {}
            }
            """;

        List<EndpointInfo> result = parse(source);
        assertTrue(result.isEmpty());
    }

    @Test
    void httpServletSubclass_producesNoEndpoints() {
        String source = """
            package com.example;
            import javax.servlet.http.HttpServlet;
            public class MyServlet extends HttpServlet {
                protected void doGet(javax.servlet.http.HttpServletRequest req,
                                     javax.servlet.http.HttpServletResponse resp) {}
            }
            """;

        List<EndpointInfo> result = parse(source);
        assertTrue(result.isEmpty(), "HttpServlet subclasses must produce zero endpoint entries");
    }

    @Test
    void spaController_markedAsStaticRoute() {
        String source = """
            package com.example;
            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.RequestMapping;
            @Controller
            public class SpaController {
                @RequestMapping(value = {"/", "/dashboard", "/settings"})
                public String forwardToIndex() { return "forward:/index.html"; }
            }
            """;

        List<EndpointInfo> eps = parse(source);
        assertFalse(eps.isEmpty(), "SPA controller must produce endpoints");
        eps.forEach(ep -> assertTrue(ep.isStaticRoute(),
                "All SpaController endpoints must be marked staticRoute=true"));
    }

    @Test
    void restController_notMarkedAsStaticRoute() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class ApiController {
                @GetMapping("/api/data")
                public String getData() { return null; }
            }
            """;

        List<EndpointInfo> eps = parse(source);
        eps.forEach(ep -> assertFalse(ep.isStaticRoute(),
                "@RestController endpoints must not be staticRoute"));
    }

    @Test
    void framework_labeledAsSpring() {
        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class C {
                @GetMapping("/x")
                public String x() { return null; }
            }
            """;

        EndpointInfo ep = parse(source).get(0);
        assertEquals("Spring", ep.getFramework());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<EndpointInfo> parse(String source) {
        ParseResult<CompilationUnit> pr = parser.parse(source);
        assertTrue(pr.isSuccessful(), "Parse failed: " + pr.getProblems());
        return extractor.extract(pr.getResult().orElseThrow(), "TestFile.java");
    }

    private void assertVerb(List<EndpointInfo> endpoints, HttpVerb verb, String path) {
        assertTrue(endpoints.stream()
                .anyMatch(ep -> ep.getHttpVerb() == verb && ep.getPathTemplate().equals(path)),
                "Expected " + verb + " " + path + " in " + endpoints);
    }

    @Test
    void unwrapReturnType_chainedWrappers() {
        assertEquals("Dto", SpringEndpointExtractor.unwrapReturnType("ResponseEntity<Mono<Dto>>"));
        assertEquals("Dto[]", SpringEndpointExtractor.unwrapReturnType("Flux<Dto>"));
        assertEquals("Dto[]", SpringEndpointExtractor.unwrapReturnType("List<Dto>"));
        assertEquals("Dto[]", SpringEndpointExtractor.unwrapReturnType("Page<Dto>"));
        assertEquals("String", SpringEndpointExtractor.unwrapReturnType("String"));
    }

    @Test
    void requestMappingWithConstantReference_pathResolved() {
        Map<String, String> constants = Map.of(
                "WebConstants.API_V1_ROOT_URL", "/api/v1",
                "API_V1_ROOT_URL", "/api/v1");
        SpringEndpointExtractor extractorWithConstants = new SpringEndpointExtractor(constants);

        String source = """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping(WebConstants.API_V1_ROOT_URL + "/users/internal")
            public class UserController {
                @GetMapping("/current/preferences")
                public Object getPreferences() { return null; }
            }
            """;

        ParseResult<CompilationUnit> pr = parser.parse(source);
        assertTrue(pr.isSuccessful());
        List<EndpointInfo> result = extractorWithConstants.extract(pr.getResult().get(), "UserController.java");

        assertEquals(1, result.size());
        assertEquals("/api/v1/users/internal/current/preferences", result.get(0).getPathTemplate(),
                "Constant reference in @RequestMapping must be resolved to its string value");
    }

}
