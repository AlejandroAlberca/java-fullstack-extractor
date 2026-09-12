package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MicronautEndpointExtractorTest {

    private MicronautEndpointExtractor extractor;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        extractor = new MicronautEndpointExtractor();
        parser = new JavaParser();
    }

    @Test
    void controllerPrefixAndMethodPath_composed() {
        String source = """
            package com.example;
            import io.micronaut.http.annotation.*;
            @Controller("/api/books")
            public class BookController {
                @Get("/{id}")
                public BookDto find(Long id) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(1, result.size());
        assertEquals(HttpVerb.GET, result.get(0).getHttpVerb());
        assertEquals("/api/books/{id}", result.get(0).getPathTemplate());
    }

    @Test
    void implicitPathVariableBinding_nameMatchedToPlaceholder() {
        String source = """
            package com.example;
            import io.micronaut.http.annotation.*;
            @Controller("/items")
            public class ItemController {
                @Put("/{id}")
                public ItemDto update(Long id, UpdateRequest body) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(1, result.size());
        // 'id' matches the {id} placeholder — should be treated as path var, not body
        assertEquals("UpdateRequest", result.get(0).getBodyParameterType());
    }

    @Test
    void httpResponseUnwrapping() {
        String source = """
            package com.example;
            import io.micronaut.http.annotation.*;
            import io.micronaut.http.HttpResponse;
            @Controller("/wrap")
            public class WrapController {
                @Get
                public HttpResponse<ItemDto> get() { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);
        assertEquals("ItemDto", result.get(0).getResponseType());
    }

    @Test
    void noControllerAnnotation_producesNoEndpoints() {
        String source = """
            package com.example;
            public class PlainClass {
                public void work() {}
            }
            """;

        assertTrue(parse(source).isEmpty());
    }

    @Test
    void allVerbAnnotations_recognized() {
        String source = """
            package com.example;
            import io.micronaut.http.annotation.*;
            @Controller("/r")
            public class R {
                @Get    public String g() { return null; }
                @Post   public String po(String b) { return null; }
                @Put    public String pu(String b) { return null; }
                @Delete public void d() {}
                @Patch  public String pa(String b) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);
        assertEquals(5, result.size());
        assertEquals("Micronaut", result.get(0).getFramework());
    }

    @Test
    void httpServletSubclass_producesNoEndpoints() {
        String source = """
            package com.example;
            public class Servlet extends javax.servlet.http.HttpServlet {
                protected void service(javax.servlet.http.HttpServletRequest q,
                                       javax.servlet.http.HttpServletResponse r) {}
            }
            """;

        assertTrue(parse(source).isEmpty());
    }

    private List<EndpointInfo> parse(String source) {
        ParseResult<CompilationUnit> pr = parser.parse(source);
        assertTrue(pr.isSuccessful(), "Parse failed: " + pr.getProblems());
        return extractor.extract(pr.getResult().orElseThrow(), "TestFile.java");
    }
}
