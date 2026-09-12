package com.devmanchego.contextextractor.matching;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndpointMatcherTest {

    @Test
    void exactMatch_producesFlow() {
        EndpointInfo javaEp = endpoint(HttpVerb.GET, "/api/products", "ProductController", "getAll");
        HttpCallInfo angularCall = call(HttpVerb.GET, "/api/products", "getProducts", "Product[]", null);
        ServiceInfo svc = new ServiceInfo("ProductService", "/svc.ts", List.of(angularCall));
        AngularProject angular = project(List.of(svc));

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(javaEp), List.of());

        assertEquals(1, result.flows().size());
        assertTrue(result.unmatchedAngularCalls().isEmpty());
        assertTrue(result.unmatchedJavaEndpoints().isEmpty());
    }

    @Test
    void pathWithPlaceholders_matchedAfterNormalization() {
        EndpointInfo javaEp = endpoint(HttpVerb.GET, "/api/items/{id}", "ItemController", "findById");
        // Angular uses template literal: `/api/items/${id}`
        HttpCallInfo angularCall = call(HttpVerb.GET, "/api/items/{id}", "getItem", "Item", null);
        ServiceInfo svc = new ServiceInfo("ItemService", "/svc.ts", List.of(angularCall));
        AngularProject angular = project(List.of(svc));

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(javaEp), List.of());

        assertEquals(1, result.flows().size());
    }

    @Test
    void unmatchedAngularCall_reportedInWarnings() {
        EndpointInfo javaEp = endpoint(HttpVerb.GET, "/api/products", "ProductController", "getAll");
        HttpCallInfo unmatchedCall = call(HttpVerb.GET, "/api/orders", "getOrders", "Order[]", null);
        ServiceInfo svc = new ServiceInfo("OrderService", "/svc.ts", List.of(unmatchedCall));
        AngularProject angular = project(List.of(svc));

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(javaEp), List.of());

        assertEquals(1, result.unmatchedAngularCalls().size());
        assertEquals(1, result.unmatchedJavaEndpoints().size());
    }

    @Test
    void verbMismatch_doesNotMatch() {
        EndpointInfo javaEp = endpoint(HttpVerb.POST, "/api/items", "ItemController", "create");
        HttpCallInfo angularCall = call(HttpVerb.GET, "/api/items", "getItems", "Item[]", null);
        ServiceInfo svc = new ServiceInfo("ItemService", "/svc.ts", List.of(angularCall));
        AngularProject angular = project(List.of(svc));

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(javaEp), List.of());

        assertTrue(result.flows().isEmpty());
        assertEquals(1, result.unmatchedAngularCalls().size());
        assertEquals(1, result.unmatchedJavaEndpoints().size());
    }

    @Test
    void tsModelCorrelatedViaEndpointMatch() {
        EndpointInfo javaEp = endpoint(HttpVerb.GET, "/api/products", "ProductController", "getAll", "Product[]");
        HttpCallInfo angularCall = call(HttpVerb.GET, "/api/products", "getProducts", "Product[]", null);
        ServiceInfo svc = new ServiceInfo("ProductService", "/svc.ts", List.of(angularCall));
        AngularProject angular = project(List.of(svc));
        TsModelInfo model = new TsModelInfo("Product", "/product.model.ts",
                TsModelInfo.Kind.INTERFACE, List.of());

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(javaEp), List.of(model));

        assertEquals(1, result.flows().size());
        assertTrue(result.flows().get(0).getTsResponseModel().isPresent(),
                "TsModelInfo must be correlated to the matched flow");
    }

    @Test
    void matchingAcrossJaxRsEndpoints() {
        EndpointInfo jaxRsEp = EndpointInfo.builder()
                .httpVerb(HttpVerb.DELETE).pathTemplate("/api/customers/{id}")
                .controllerClass("com.example.CustomerResource").methodName("delete")
                .framework("JaxRs").build();

        HttpCallInfo angularCall = call(HttpVerb.DELETE, "/api/customers/{id}", "deleteCustomer", "void", null);
        ServiceInfo svc = new ServiceInfo("CustomerService", "/svc.ts", List.of(angularCall));
        AngularProject angular = project(List.of(svc));

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(jaxRsEp), List.of());

        assertEquals(1, result.flows().size());
    }

    @Test
    void differentPlaceholderNames_matchedCanonically() {
        // Angular: DELETE /api/v1/session/{sessionId}  ↔  Java: DELETE /api/v1/session/{id}
        // Both must match because placeholder position is the same, only name differs.
        EndpointInfo javaEp = endpoint(HttpVerb.DELETE, "/api/v1/session/{id}", "SessionCtrl", "clear");
        HttpCallInfo angularCall = call(HttpVerb.DELETE, "/api/v1/session/{sessionId}", "clearSession", "void", null);
        ServiceInfo svc = new ServiceInfo("ApiService", "/api.service.ts", List.of(angularCall));
        AngularProject angular = project(List.of(svc));

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(javaEp), List.of());

        assertEquals(1, result.flows().size(), "Different placeholder names in same position must match");
        assertTrue(result.unmatchedAngularCalls().isEmpty());
        assertTrue(result.unmatchedJavaEndpoints().isEmpty());
    }

    @Test
    void dotNotationPlaceholder_matchedCanonically() {
        // Angular: POST /api/accommodationRequests/${accommodationRequest.id}/updateSignatories
        // normalizes to {accommodationRequest.id} which canonicalizes to {param}
        // Java:    POST /api/accommodationRequests/{id}/updateSignatories → {param}
        EndpointInfo javaEp = endpoint(HttpVerb.POST,
                "/api/accommodationRequests/{id}/updateSignatories", "AccReqCtrl", "updateSignatories");
        HttpCallInfo angularCall = call(HttpVerb.POST,
                "/api/accommodationRequests/{accommodationRequest.id}/updateSignatories",
                "updateSignatories", "any", null);
        ServiceInfo svc = new ServiceInfo("AccommodationManagerService", "/svc.ts", List.of(angularCall));
        AngularProject angular = project(List.of(svc));

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(javaEp), List.of());

        assertEquals(1, result.flows().size(),
                "{accommodationRequest.id} and {id} must match via canonical {param} form");
        assertTrue(result.unmatchedAngularCalls().isEmpty());
        assertTrue(result.unmatchedJavaEndpoints().isEmpty());
    }

    @Test
    void pathNormalization_trailingSlashIgnored() {
        EndpointInfo javaEp = endpoint(HttpVerb.GET, "/api/items", "C", "m");
        HttpCallInfo angularCall = call(HttpVerb.GET, "/api/items/", "get", "any", null);
        ServiceInfo svc = new ServiceInfo("S", "/s.ts", List.of(angularCall));
        AngularProject angular = project(List.of(svc));

        EndpointMatcher.MatchResult result = EndpointMatcher.match(angular, List.of(javaEp), List.of());
        assertEquals(1, result.flows().size());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static EndpointInfo endpoint(HttpVerb verb, String path, String ctrl, String method) {
        return endpoint(verb, path, ctrl, method, null);
    }

    private static EndpointInfo endpoint(HttpVerb verb, String path, String ctrl, String method, String responseType) {
        return EndpointInfo.builder()
                .httpVerb(verb).pathTemplate(path)
                .controllerClass(ctrl).methodName(method)
                .responseType(responseType)
                .framework("Spring").build();
    }

    private static HttpCallInfo call(HttpVerb verb, String url, String methodName,
                                     String responseType, String bodyType) {
        return new HttpCallInfo(methodName, verb, url, responseType, bodyType);
    }

    private static AngularProject project(List<ServiceInfo> services) {
        return new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), services, List.of());
    }
}
