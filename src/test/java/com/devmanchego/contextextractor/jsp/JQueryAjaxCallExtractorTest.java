package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.devmanchego.contextextractor.jsp.JQueryAjaxCallExtractor.CallSite;
import com.devmanchego.contextextractor.matching.PathNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JQueryAjaxCallExtractorTest {

    private final JQueryAjaxCallExtractor extractor = new JQueryAjaxCallExtractor();

    private List<CallSite> sites(String js) {
        return extractor.extract(js);
    }

    private CallSite only(String js) {
        List<CallSite> sites = sites(js);
        assertEquals(1, sites.size(), sites.toString());
        return sites.get(0);
    }

    private CallSite extracted(String js) {
        CallSite site = only(js);
        assertTrue(site.extracted(), "unparseable: " + site.unparseableReason());
        return site;
    }

    // -----------------------------------------------------------------------
    // Verb — both key spellings, any quoting, case, whitespace
    // -----------------------------------------------------------------------

    @Test
    void methodAndTypeKeys_inAnyQuotingCaseAndSpacing_resolveToTheSameVerb() {
        CallSite a = extracted("$.ajax({url:'/a',method:'POST'});");
        CallSite b = extracted("$.ajax({ url : \"/b\" , type : \"post\" });");
        CallSite c = extracted("$.ajax({\n    url: '/c',\n    type :   'Post'\n});");

        assertEquals(HttpVerb.POST, a.verb());
        assertEquals(HttpVerb.POST, b.verb());
        assertEquals(HttpVerb.POST, c.verb());
        assertEquals("method", a.verbSource());
        assertEquals("type", b.verbSource());
    }

    @Test
    void noVerbKey_isJQuerysDefaultGet_statedAsSuch() {
        CallSite site = extracted("$.ajax({ url: '/list' });");

        assertEquals(HttpVerb.GET, site.verb());
        assertEquals("default", site.verbSource());
    }

    @Test
    void verbHeldInALocalVariable_isResolved() {
        CallSite site = extracted("function f() { const verbe = 'DELETE'; $.ajax({ url: '/x', type: verbe }); }");

        assertEquals(HttpVerb.DELETE, site.verb());
    }

    @Test
    void verbThatIsARuntimeValue_isReportedUnparseable() {
        CallSite site = only("function f(v) { $.ajax({ url: '/x', type: v.verbe }); }");

        assertFalse(site.extracted());
        assertTrue(site.unparseableReason().contains("HTTP verb"), site.unparseableReason());
    }

    @Test
    void shortcutsImplyTheirVerb() {
        List<CallSite> s = sites("""
                $.get('/a/list', function (d) {});
                $.post('/b/create', { x: 1 }, function (d) {});
                $.getJSON(basepath + '/c/data');
                """);

        assertEquals(List.of(HttpVerb.GET, HttpVerb.POST, HttpVerb.GET), s.stream().map(CallSite::verb).toList());
        assertEquals("fields {x}", s.get(1).payload());
        assertEquals(List.of("success: inline"), s.get(1).callbacks());
    }

    // -----------------------------------------------------------------------
    // URL reconstruction
    // -----------------------------------------------------------------------

    @Test
    void concatenation_contextPathDropped_literalsKept_expressionsBecomeParams() {
        CallSite site = extracted("$.ajax({ url: basepath + '/workflow/' + id + '/detail', type: 'GET' });");

        assertEquals(List.of("/workflow/{param}/detail"), site.urlTemplates());
        assertEquals("literal", site.urlProvenance());
    }

    @Test
    void templateLiteral_contextPathDropped_expressionsBecomeParams() {
        CallSite site = extracted("$.ajax({ url: `${basepath}/fap/${fap.id}/eotp`, type: 'GET' });");

        assertEquals(List.of("/fap/{param}/eotp"), site.urlTemplates());
    }

    @Test
    void queryStringAndTrailingSlash_normalisedAwayLikeBackendPaths() {
        List<CallSite> s = sites("""
                $.ajax({ url: basepath + '/fapTypePrestations/' });
                $.ajax({ url: basepath + '/workflow/verifier?workflowid=' + w + '&tacheid=' + t });
                """);

        assertEquals(List.of("/fapTypePrestations"), s.get(0).urlTemplates());
        assertEquals(List.of("/workflow/verifier"), s.get(1).urlTemplates());
    }

    @Test
    void reconstructedTemplate_canonicalisesLikeTheBackendEndpoint() {
        CallSite site = extracted("$.ajax({ url: basepath + '/fap/' + fap.id, type: 'PUT' });");

        String backend = PathNormalizer.canonicalize(PathNormalizer.normalize(HttpVerb.PUT, "/fap/{id}/")).pathTemplate();
        String frontend = PathNormalizer.canonicalize(PathNormalizer.normalize(HttpVerb.PUT, site.urlTemplates().get(0))).pathTemplate();
        assertEquals(backend, frontend);
    }

    @Test
    void conditionalUrl_yieldsBothBranches() {
        CallSite site = extracted("$.ajax({ url: actif ? basepath + '/domaine/actifs' : basepath + '/domaine' });");

        assertEquals(List.of("/domaine/actifs", "/domaine"), site.urlTemplates());
    }

    // -----------------------------------------------------------------------
    // Names in the URL
    // -----------------------------------------------------------------------

    @Test
    void urlInALocalVariable_isResolved() {
        CallSite site = extracted("""
                function load(id) {
                    const url = basepath + '/fap/' + id;
                    $.ajax({ url: url, type: 'GET' });
                }
                """);

        assertEquals(List.of("/fap/{param}"), site.urlTemplates());
        assertTrue(site.urlProvenance().startsWith("variable `url`"), site.urlProvenance());
    }

    @Test
    void urlBuiltByAHelper_usesTheHelpersFirstArgument() {
        CallSite site = extracted("""
                function load(header) {
                    const url = Uri.initURL('/workflow/listeEtapesValidees/' + header.workflowId, { etat: header.etat });
                    $.ajax({ url: url, type: 'GET' });
                }
                """);

        assertEquals(List.of("/workflow/listeEtapesValidees/{param}"), site.urlTemplates());
        assertTrue(site.urlProvenance().contains("built by initURL()"), site.urlProvenance());
    }

    @Test
    void urlInAPropertyWithASingleDefinition_isResolved() {
        CallSite site = extracted("""
                const Page = {
                    saveUrl: basepath + '/fee/save',
                    save: function () { $.ajax({ url: this.saveUrl, method: 'POST' }); }
                };
                """);

        assertEquals(List.of("/fee/save"), site.urlTemplates());
        assertTrue(site.urlProvenance().startsWith("property `saveUrl`"), site.urlProvenance());
    }

    @Test
    void genericPropertyWithSeveralValues_isNeverSubstituted() {
        // The real false positive this guards against: button definitions {id: 'annulerFap'} made
        // '/fapEotps/' + btn.id read as '/fapEotps/annulerFap'.
        CallSite site = extracted("""
                const buttons = [{ id: 'annulerFap' }, { id: 'terminerFap' }];
                function supprimer(btn, page) {
                    $.ajax({ url: basepath + '/fapEotps/' + btn.id, type: 'GET' });
                    $.ajax({ url: basepath + '/fapEotps/' + page.fap.id, type: 'GET' });
                }
                """.replace("$.ajax({ url: basepath + '/fapEotps/' + page.fap.id, type: 'GET' });\n", ""));

        assertEquals(List.of("/fapEotps/{param}"), site.urlTemplates());
    }

    @Test
    void fieldOfARuntimeObject_isAParameter() {
        CallSite site = extracted("function f(page) { $.ajax({ url: basepath + '/fap/' + page.fap.id }); }");

        assertEquals(List.of("/fap/{param}"), site.urlTemplates());
    }

    @Test
    void parameterTracedToItsCallSites_fewLiteralValuesAreEnumerated() {
        CallSite site = extracted("""
                const Page = {
                    send: function (action) { $.ajax({ url: basepath + '/fap/' + action, method: 'PUT' }); },
                    save: function () { this.send('enregistrer'); },
                    finish: function () { this.send('terminer'); }
                };
                """);

        assertEquals(List.of("/fap/enregistrer", "/fap/terminer"), site.urlTemplates());
    }

    @Test
    void wholeUrlParameter_isResolvedAtEveryCallSite() {
        CallSite site = extracted("""
                const Page = {
                    call: function (url) { $.ajax({ url: url, type: 'GET' }); },
                    a: function () { this.call(basepath + '/a'); },
                    b: function (id) { this.call(basepath + '/b/' + id); }
                };
                """);

        assertEquals(List.of("/a", "/b/{param}"), site.urlTemplates());
        assertTrue(site.urlProvenance().startsWith("parameter `url` of call(), from its call sites"), site.urlProvenance());
    }

    @Test
    void parameterWithManyLiteralValues_isTreatedAsAPathParameter() {
        CallSite site = extracted("""
                const P = {
                    libelle: function (id) { $.ajax({ url: basepath + '/etape/' + id }); },
                    a: function () { this.libelle('T1'); }, b: function () { this.libelle('T2'); },
                    c: function () { this.libelle('T3'); }, d: function () { this.libelle('T4'); },
                    e: function () { this.libelle('T5'); }
                };
                """);

        assertEquals(List.of("/etape/{param}"), site.urlTemplates());
    }

    @Test
    void parameterNeverPassed_isReportedUnparseable_withTheReason() {
        CallSite site = only("const P = { load: function (url) { $.ajax({ url: url }); } };");

        assertFalse(site.extracted());
        assertTrue(site.unparseableReason().contains("never calls"), site.unparseableReason());
        assertEquals("load", site.function());
    }

    @Test
    void urlReadFromAMarkupDataAttribute_isReported_withTheAttribute() {
        CallSite site = only("""
                const P = {
                    importUrl: basepath,
                    init: function () { this.importUrl = this.importUrl + $("#importsPage").data("importactionurl"); },
                    lister: function () { $.ajax({ url: this.importUrl, type: 'GET' }); }
                };
                """);

        assertFalse(site.extracted());
        assertTrue(site.unparseableReason().contains("`data-importactionurl` of #importsPage"), site.unparseableReason());
    }

    // -----------------------------------------------------------------------
    // Settings object
    // -----------------------------------------------------------------------

    @Test
    void settingsInAVariable_areResolved_urlAsFirstArgumentToo() {
        List<CallSite> s = sites("""
                function f() {
                    const opts = { url: '/o', type: 'DELETE' };
                    $.ajax(opts);
                    $.ajax(basepath + '/two', { method: 'PUT' });
                }
                """);

        assertEquals(HttpVerb.DELETE, s.get(0).verb());
        assertEquals(List.of("/o"), s.get(0).urlTemplates());
        assertEquals(HttpVerb.PUT, s.get(1).verb());
        assertEquals(List.of("/two"), s.get(1).urlTemplates());
    }

    @Test
    void settingsNotALiteral_orWithoutUrl_areReportedUnparseable() {
        List<CallSite> s = sites("""
                $.ajax(buildOptions());
                $.ajax({ type: 'GET', success: function () {} });
                """);

        assertEquals(2, s.size());
        assertTrue(s.get(0).unparseableReason().contains("is not a literal"), s.get(0).unparseableReason());
        assertTrue(s.get(1).unparseableReason().contains("no `url`"), s.get(1).unparseableReason());
    }

    @Test
    void everyCallSite_isExtractedOrReported_neverSilentlyDropped() {
        List<CallSite> s = sites("""
                $.ajax({ url: '/a' });
                $.ajax({ url: somethingUnknown });
                $.post('/b');
                """);

        assertEquals(3, s.size());
        assertEquals(2, s.stream().filter(CallSite::extracted).count());
        assertTrue(s.get(1).unparseableReason().contains("somethingUnknown"), s.get(1).unparseableReason());
    }

    // -----------------------------------------------------------------------
    // Payload and callbacks
    // -----------------------------------------------------------------------

    @Test
    void payloadKinds_areDescribed() {
        List<CallSite> s = sites("""
                function f(page) {
                    const fap = page.toJsonFap();
                    const fileData = new FormData();
                    $.ajax({ url: '/1', type: 'PUT', data: JSON.stringify(fap) });
                    $.ajax({ url: '/2', type: 'POST', data: JSON.stringify({ code: c, libelle: l }) });
                    $.ajax({ url: '/3', type: 'GET', data: $('#rechercheForm').serialize() });
                    $.ajax({ url: '/4', type: 'POST', data: fileData, processData: false });
                    $.ajax({ url: '/5', type: 'POST', data: { a: 1, b: 2 } });
                }
                """);

        assertEquals("JSON of toJsonFap() (via `fap`)", s.get(0).payload());
        assertEquals("JSON of fields {code, libelle}", s.get(1).payload());
        assertEquals("serialized form #rechercheForm", s.get(2).payload());
        assertEquals("multipart form data (FormData)", s.get(3).payload());
        assertEquals("fields {a, b}", s.get(4).payload());
    }

    @Test
    void innerDeclarationShadowsAnOuterOne_forPayloadAndUrl() {
        // The real shape: the module object is itself named `fap`, and sendFap() declares its own `fap`.
        List<CallSite> s = sites("""
                const fap = { eotps: [], saisieTerminee: false };
                const url = '/outer';
                const Page = {
                    sendFap: function (page) {
                        const fap = page.toJsonFap();
                        const url = basepath + '/fap/save';
                        $.ajax({ url: url, method: 'PUT', data: JSON.stringify(fap) });
                    }
                };
                """);

        assertEquals("JSON of toJsonFap() (via `fap`)", s.get(0).payload());
        assertEquals(List.of("/fap/save"), s.get(0).urlTemplates());
    }

    @Test
    void callbacks_inlineNamedAndChained() {
        CallSite site = extracted("""
                $.ajax({
                    url: '/x',
                    success: function (data) { render(data); },
                    error: PageMessages.displayMessageFromAjaxError
                }).done(function () {}).fail(onFail);
                """);

        assertEquals(List.of("success: inline", "error: PageMessages.displayMessageFromAjaxError",
                "done: inline", "fail: onFail"), site.callbacks());
    }

    // -----------------------------------------------------------------------
    // Robustness: tokens, not characters
    // -----------------------------------------------------------------------

    @Test
    void commentsWithApostrophesAndParentheses_neverUnbalanceTheScan() {
        CallSite site = extracted("""
                // l'étape (en cours) — l'apostrophe ne doit rien casser
                const Page = {
                    save: function () {
                        /* it's ) tricky ( */
                        $.ajax({ url: '/s', method: 'POST' }); // l'appel
                    }
                };
                """);

        assertEquals(List.of("/s"), site.urlTemplates());
        assertEquals("save", site.function());
    }

    @Test
    void callSitesInsideStringsOrComments_areNotCallSites() {
        assertTrue(sites("""
                // $.ajax({ url: '/commented' });
                const s = "$.ajax({ url: '/in-a-string' })";
                """).isEmpty());
    }

    @Test
    void dataTablesAjaxOption_objectAndUrlForms() {
        List<CallSite> s = sites("""
                $('#t').DataTable({ ajax: { url: basepath + '/corbeille/list', type: 'POST', data: function (d) { return d; } } });
                $('#u').DataTable({ ajax: basepath + '/historique/list' });
                """);

        assertEquals("DataTables", s.get(0).kind());
        assertEquals(HttpVerb.POST, s.get(0).verb());
        assertEquals(List.of("/corbeille/list"), s.get(0).urlTemplates());
        assertEquals("request parameters built by a function", s.get(0).payload());
        assertEquals(HttpVerb.GET, s.get(1).verb());
        assertEquals(List.of("/historique/list"), s.get(1).urlTemplates());
    }

    @Test
    void fetch_isExtracted() {
        CallSite site = extracted("fetch(basepath + '/api/ping', { method: 'DELETE', body: JSON.stringify(x) });");

        assertEquals(HttpVerb.DELETE, site.verb());
        assertEquals(List.of("/api/ping"), site.urlTemplates());
        assertEquals("fetch", site.kind());
    }
}
