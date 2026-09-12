package com.devmanchego.contextextractor.angular.resolve;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UrlPrefixResolverTest {

    // -----------------------------------------------------------------------
    // normalizePatternKey
    // -----------------------------------------------------------------------

    @Test
    void normalizePatternKey_humanReadable_convertedToTokenForm() {
        String key = "applicationConfigService.getEndpointFor(API_ROOT_V5)";
        String normalized = UrlPrefixResolver.normalizePatternKey(key);
        assertEquals("{applicationConfigService}{getEndpointFor}{API_ROOT_V5}", normalized);
    }

    @Test
    void normalizePatternKey_alreadyInTokenForm_unchanged() {
        String key = "{svc}{method}{CONST}";
        assertEquals(key, UrlPrefixResolver.normalizePatternKey(key));
    }

    @Test
    void normalizePatternKey_null_returnsEmpty() {
        assertEquals("", UrlPrefixResolver.normalizePatternKey(null));
    }

    // -----------------------------------------------------------------------
    // parseMapEntry
    // -----------------------------------------------------------------------

    @Test
    void parseMapEntry_validEntry_parsed() {
        var entry = UrlPrefixResolver.parseMapEntry(
                "{applicationConfigService}{getEndpointFor}{API_ROOT_V5}=/api/v5/");
        assertTrue(entry.isPresent());
        assertEquals("{applicationConfigService}{getEndpointFor}{API_ROOT_V5}",
                entry.get().getKey());
        assertEquals("/api/v5/", entry.get().getValue());
    }

    @Test
    void parseMapEntry_missingValue_returnsEmpty() {
        assertTrue(UrlPrefixResolver.parseMapEntry("keyonly").isEmpty());
        assertTrue(UrlPrefixResolver.parseMapEntry(null).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Layer 1: webpack constants
    // -----------------------------------------------------------------------

    @Test
    void applyWebpackConstants_tokenReplaced() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of("API_ROOT_V5", "api/v5/"), Map.of());

        String result = resolver.applyWebpackConstants(
                "/{applicationConfigService}{getEndpointFor}{API_ROOT_V5}account");

        assertEquals("/{applicationConfigService}{getEndpointFor}api/v5/account", result);
    }

    @Test
    void applyWebpackConstants_noToken_unchanged() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of("API_ROOT_V5", "api/v5/"), Map.of());

        String url = "/api/v5/account";
        assertEquals(url, resolver.applyWebpackConstants(url));
    }

    @Test
    void applyWebpackConstants_multipleTokens_allReplaced() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of("ROOT", "/api/", "VER", "v5"), Map.of());

        assertEquals("/{svc}/api/v5/path",
                resolver.applyWebpackConstants("/{svc}/{ROOT}{VER}/path"));
    }

    // -----------------------------------------------------------------------
    // Layer 2: user prefix map
    // -----------------------------------------------------------------------

    @Test
    void applyPrefixMap_humanReadableKey_matchedAndReplaced() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of(),
                Map.of("applicationConfigService.getEndpointFor(API_ROOT_V5)", "/api/v5/"));

        String result = resolver.applyPrefixMap(
                "/{applicationConfigService}{getEndpointFor}{API_ROOT_V5}account");

        assertEquals("/api/v5/account", result);
    }

    @Test
    void applyPrefixMap_tokenFormKey_matchedAndReplaced() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of(),
                Map.of("{svc}{getBase}", "/base/"));

        String result = resolver.applyPrefixMap("/{svc}{getBase}resource");
        assertEquals("/base/resource", result);
    }

    @Test
    void applyPrefixMap_noMatch_unchanged() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of(), Map.of("{other}{method}", "/other/"));

        String url = "/{svc}{getBase}resource";
        assertEquals(url, resolver.applyPrefixMap(url));
    }

    // -----------------------------------------------------------------------
    // End-to-end: both layers
    // -----------------------------------------------------------------------

    @Test
    void applyToProject_bothLayers_fullyResolved() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of("API_ROOT_V5", "api/v5/"),
                Map.of("applicationConfigService.getEndpointFor(API_ROOT_V5)", "/api/v5/"));

        HttpCallInfo call = new HttpCallInfo("save", HttpVerb.POST,
                "/{applicationConfigService}{getEndpointFor}{API_ROOT_V5}account",
                "AccountResponse", "AccountRequest");
        ServiceInfo svc = new ServiceInfo("AccountService", "/account.service.ts", List.of(call));
        AngularProject project = new AngularProject(
                AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), List.of(svc), List.of());

        List<String> warnings = new ArrayList<>();
        AngularProject resolved = resolver.applyToProject(project, warnings);

        HttpCallInfo resolvedCall = resolved.getServices().get(0).getHttpCalls().get(0);
        assertEquals("/api/v5/account", resolvedCall.getUrlTemplate());
        assertTrue(warnings.isEmpty(), "No warnings expected when fully resolved");
    }

    @Test
    void applyToProject_partiallyResolved_warningEmitted() {
        // Only webpack layer — no user prefix map
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of("API_ROOT_V5", "api/v5/"), Map.of());

        HttpCallInfo call = new HttpCallInfo("save", HttpVerb.POST,
                "/{applicationConfigService}{getEndpointFor}{API_ROOT_V5}account",
                null, null);
        ServiceInfo svc = new ServiceInfo("AccountService", "/account.service.ts", List.of(call));
        AngularProject project = new AngularProject(
                AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), List.of(svc), List.of());

        List<String> warnings = new ArrayList<>();
        resolver.applyToProject(project, warnings);

        assertEquals(1, warnings.size(), "One warning expected for partially-resolved URL");
        assertTrue(warnings.get(0).contains("AccountService#save"));
        assertTrue(warnings.get(0).contains("--url-prefix-map"),
                "Warning must suggest the fix");
    }

    @Test
    void applyToProject_noTokensInUrl_noChange() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of("API_ROOT_V5", "api/v5/"), Map.of());

        HttpCallInfo call = new HttpCallInfo("get", HttpVerb.GET, "/api/v5/items", "Item[]", null);
        ServiceInfo svc = new ServiceInfo("ItemService", "/item.service.ts", List.of(call));
        AngularProject project = new AngularProject(
                AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), List.of(svc), List.of());

        List<String> warnings = new ArrayList<>();
        AngularProject resolved = resolver.applyToProject(project, warnings);

        assertSame(call, resolved.getServices().get(0).getHttpCalls().get(0),
                "Unmodified call must be the same instance (fast path)");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void doubleSlash_normalizedAfterResolution() {
        UrlPrefixResolver resolver = new UrlPrefixResolver(
                Map.of(),
                Map.of("{svc}{getBase}", "/api/v5/"));

        // suffix already starts with /
        String result = resolver.applyPrefixMap("/{svc}{getBase}/account");
        assertEquals("/api/v5/account", result,
                "Double slash must be collapsed after concatenation");
    }
}
