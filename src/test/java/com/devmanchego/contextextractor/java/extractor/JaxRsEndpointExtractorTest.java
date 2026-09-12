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

class JaxRsEndpointExtractorTest {

    private JaxRsEndpointExtractor extractor;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        extractor = new JaxRsEndpointExtractor();
        parser = new JavaParser();
    }

    @Test
    void classAndMethodPathComposition_composesCorrectly() {
        String source = """
            package com.example;
            import jakarta.ws.rs.*;
            @Path("/api/customers")
            public class CustomerResource {
                @GET
                @Path("/{id}")
                public CustomerDto find(@PathParam("id") Long id) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(1, result.size());
        assertEquals(HttpVerb.GET, result.get(0).getHttpVerb());
        assertEquals("/api/customers/{id}", result.get(0).getPathTemplate());
    }

    @Test
    void bodyParameterInferredByExclusion_firstNonBindingParam() {
        String source = """
            package com.example;
            import jakarta.ws.rs.*;
            @Path("/orders")
            public class OrderResource {
                @POST
                public OrderDto create(CreateOrderRequest body) { return null; }
                @PUT
                @Path("/{id}")
                public OrderDto update(@PathParam("id") Long id, UpdateOrderRequest body) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(2, result.size());
        assertEquals("CreateOrderRequest", result.get(0).getBodyParameterType());
        assertEquals("UpdateOrderRequest", result.get(1).getBodyParameterType());
    }

    @Test
    void queryParamAndHeaderParam_notTreatedAsBody() {
        String source = """
            package com.example;
            import jakarta.ws.rs.*;
            @Path("/search")
            public class SearchResource {
                @GET
                public List<Dto> search(@QueryParam("q") String query, @HeaderParam("X-Token") String token) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertEquals(1, result.size());
        assertNull(result.get(0).getBodyParameterType(),
                "Parameters with @QueryParam or @HeaderParam must not be the body parameter");
    }

    @Test
    void responseUnwrapping_stripsJaxRsResponse() {
        String source = """
            package com.example;
            import jakarta.ws.rs.*;
            @Path("/items")
            public class ItemResource {
                @GET
                public Response getAll() { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);

        assertNull(result.get(0).getResponseType(),
                "JAX-RS Response type should be unwrapped to null (opaque)");
    }

    @Test
    void completionStageUnwrapping() {
        String source = """
            package com.example;
            import jakarta.ws.rs.*;
            @Path("/async")
            public class AsyncResource {
                @GET
                public CompletionStage<ItemDto> getAsync() { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);
        assertEquals("ItemDto", result.get(0).getResponseType());
    }

    @Test
    void classWithoutPathAnnotation_producesNoEndpoints() {
        String source = """
            package com.example;
            public class NotAResource {
                public void doWork() {}
            }
            """;

        assertTrue(parse(source).isEmpty());
    }

    @Test
    void allHttpVerbs_recognized() {
        String source = """
            package com.example;
            import jakarta.ws.rs.*;
            @Path("/r")
            public class R {
                @GET public String g() { return null; }
                @POST public String p(String b) { return null; }
                @PUT public String pu(@PathParam("id") Long id, String b) { return null; }
                @DELETE public void d() {}
                @PATCH public String pa(String b) { return null; }
            }
            """;

        List<EndpointInfo> result = parse(source);
        assertEquals(5, result.size());
        assertEquals("JaxRs", result.get(0).getFramework());
    }

    @Test
    void httpServletSubclass_producesNoEndpoints() {
        String source = """
            package com.example;
            import javax.servlet.http.HttpServlet;
            public class LegacyServlet extends HttpServlet {
                protected void doPost(javax.servlet.http.HttpServletRequest req,
                                      javax.servlet.http.HttpServletResponse resp) {}
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
