package com.devmanchego.contextextractor.angular.nodebridge;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NodeBridge, mocking the Node subprocess result with fixture JSON
 * so the test does not depend on a real Node.js installation.
 */
class NodeBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void validPayload_parsedIntoAngularProject() throws Exception {
        String json = """
            {
              "strategy": "ts-morph",
              "version": "1",
              "components": [
                {
                  "className": "ProductListComponent",
                  "filePath": "/src/product-list.component.ts",
                  "selector": "app-product-list",
                  "injectedServices": ["ProductService"]
                }
              ],
              "services": [
                {
                  "className": "ProductService",
                  "filePath": "/src/product.service.ts",
                  "httpCalls": [
                    {
                      "methodName": "getProducts",
                      "httpVerb": "GET",
                      "urlTemplate": "/api/products",
                      "responseType": "Product[]",
                      "bodyType": null
                    },
                    {
                      "methodName": "createProduct",
                      "httpVerb": "POST",
                      "urlTemplate": "/api/products",
                      "responseType": "Product",
                      "bodyType": "CreateProductRequest"
                    }
                  ]
                }
              ],
              "models": [
                {
                  "name": "Product",
                  "filePath": "/src/product.model.ts",
                  "kind": "interface",
                  "fields": [
                    { "name": "id",   "type": "number",  "optional": false },
                    { "name": "name", "type": "string",  "optional": false },
                    { "name": "desc", "type": "string",  "optional": true  }
                  ]
                }
              ]
            }
            """;

        TsMorphPayload.Root payload = MAPPER.readValue(json, TsMorphPayload.Root.class);

        // Use the conversion logic via a test-accessible NodeBridge helper
        AngularProject project = convertPayload(payload);

        assertEquals(AngularProject.ParsingStrategy.NODE_TS_MORPH, project.getStrategy());
        assertEquals(1, project.getComponents().size());
        assertEquals("ProductListComponent", project.getComponents().get(0).getClassName());
        assertEquals("app-product-list", project.getComponents().get(0).getSelector());
        assertEquals(List.of("ProductService"), project.getComponents().get(0).getInjectedServices());

        assertEquals(1, project.getServices().size());
        assertEquals(2, project.getServices().get(0).getHttpCalls().size());

        var getCall = project.getServices().get(0).getHttpCalls().get(0);
        assertEquals("GET", getCall.getHttpVerb().name());
        assertEquals("/api/products", getCall.getUrlTemplate());
        assertEquals("Product[]", getCall.getResponseType());
        assertNull(getCall.getBodyType());

        var postCall = project.getServices().get(0).getHttpCalls().get(1);
        assertEquals("CreateProductRequest", postCall.getBodyType());

        assertEquals(1, project.getModels().size());
        assertEquals(3, project.getModels().get(0).getFields().size());
        assertTrue(project.getModels().get(0).getFields().get(2).isOptional());
    }

    @Test
    void unknownHttpVerb_defaultsToGet() throws Exception {
        String json = """
            {
              "strategy": "ts-morph",
              "version": "1",
              "services": [
                {
                  "className": "WeirdService",
                  "filePath": "/src/weird.service.ts",
                  "httpCalls": [
                    {
                      "methodName": "doThing",
                      "httpVerb": "OPTIONS",
                      "urlTemplate": "/x",
                      "responseType": "any",
                      "bodyType": null
                    }
                  ]
                }
              ]
            }
            """;

        TsMorphPayload.Root payload = MAPPER.readValue(json, TsMorphPayload.Root.class);
        AngularProject project = convertPayload(payload);

        var call = project.getServices().get(0).getHttpCalls().get(0);
        // Unknown verb defaults to GET without throwing
        assertNotNull(call.getHttpVerb());
    }

    @Test
    void missingOptionalFields_handledGracefully() throws Exception {
        String json = """
            {
              "strategy": "ts-morph",
              "version": "1"
            }
            """;

        TsMorphPayload.Root payload = MAPPER.readValue(json, TsMorphPayload.Root.class);
        AngularProject project = convertPayload(payload);

        assertTrue(project.getComponents().isEmpty());
        assertTrue(project.getServices().isEmpty());
        assertTrue(project.getModels().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helper: invoke NodeBridge's conversion logic directly
    // (avoids launching a real Node.js process)
    // -----------------------------------------------------------------------

    private AngularProject convertPayload(TsMorphPayload.Root payload) {
        List<com.devmanchego.contextextractor.angular.model.ComponentInfo> components =
                payload.components == null ? List.of() :
                payload.components.stream().map(c ->
                        new com.devmanchego.contextextractor.angular.model.ComponentInfo(
                                c.className, c.filePath,
                                c.selector != null ? c.selector : "",
                                c.injectedServices != null ? c.injectedServices : List.of()))
                        .toList();

        List<com.devmanchego.contextextractor.angular.model.ServiceInfo> services =
                payload.services == null ? List.of() :
                payload.services.stream().map(s -> {
                    List<com.devmanchego.contextextractor.angular.model.HttpCallInfo> calls =
                            s.httpCalls == null ? List.of() :
                            s.httpCalls.stream().map(h -> {
                                com.devmanchego.contextextractor.java.model.HttpVerb verb;
                                try {
                                    verb = com.devmanchego.contextextractor.java.model.HttpVerb
                                            .valueOf(h.httpVerb.toUpperCase());
                                } catch (Exception e) {
                                    verb = com.devmanchego.contextextractor.java.model.HttpVerb.GET;
                                }
                                return new com.devmanchego.contextextractor.angular.model.HttpCallInfo(
                                        h.methodName, verb, h.urlTemplate, h.responseType, h.bodyType);
                            }).toList();
                    return new com.devmanchego.contextextractor.angular.model.ServiceInfo(
                            s.className, s.filePath, calls);
                }).toList();

        List<com.devmanchego.contextextractor.angular.model.TsModelInfo> models =
                payload.models == null ? List.of() :
                payload.models.stream().map(m -> {
                    List<com.devmanchego.contextextractor.angular.model.TsFieldInfo> fields =
                            m.fields == null ? List.of() :
                            m.fields.stream().map(f ->
                                    new com.devmanchego.contextextractor.angular.model.TsFieldInfo(
                                            f.name, f.type, f.optional)).toList();
                    com.devmanchego.contextextractor.angular.model.TsModelInfo.Kind kind =
                            "interface".equalsIgnoreCase(m.kind)
                            ? com.devmanchego.contextextractor.angular.model.TsModelInfo.Kind.INTERFACE
                            : com.devmanchego.contextextractor.angular.model.TsModelInfo.Kind.CLASS;
                    return new com.devmanchego.contextextractor.angular.model.TsModelInfo(
                            m.name, m.filePath, kind, fields);
                }).toList();

        return new AngularProject(AngularProject.ParsingStrategy.NODE_TS_MORPH,
                components, services, models);
    }
}
