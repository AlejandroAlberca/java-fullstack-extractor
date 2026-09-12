package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.common.TemplateEventExtractorFactory;
import com.devmanchego.contextextractor.common.TemplateEventExtractorStrategy;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.jsp.DomIdentifierCorrelator.FieldCorrelation;
import com.devmanchego.contextextractor.jsp.DomIdentifierCorrelator.Result;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import com.devmanchego.contextextractor.render.FrontendPagesRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DomIdentifierCorrelatorTest {

    @TempDir
    Path tempDir;

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private Path jsp() { return tempDir.resolve("src/main/webapp/WEB-INF/jsp/page.jsp"); }

    /** A view bound (Phase 05) to one bundle entry, src/main/js/page.js. */
    private void project(String markup, String js) throws IOException {
        write("webpack.config.js",
                "module.exports = { entry: { \"page\": './src/main/js/page.js' }, output: { filename: '[name].js' } };");
        write("src/main/js/page.js", js);
        write("src/main/webapp/WEB-INF/jsp/page.jsp",
                markup + "\n<script src=\"${pageContext.request.contextPath}/assets/page.js\"></script>");
    }

    private Result correlate() {
        return new DomIdentifierCorrelator().correlate(jsp().toString());
    }

    private static FieldCorrelation field(Result r, String id) {
        return r.fields().stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no row for #" + id + " in " + r.fields()));
    }

    // -----------------------------------------------------------------------
    // Binding
    // -----------------------------------------------------------------------

    @Test
    void viewWithoutAPageBundle_isReportedUncorrelated_withTheReason() throws IOException {
        write("webpack.config.js", "module.exports = { entry: { \"page\": './src/main/js/page.js' } };");
        write("src/main/js/page.js", "");
        write("src/main/webapp/WEB-INF/jsp/page.jsp", "<input id=\"code\"/>");

        Result r = correlate();

        assertFalse(r.bound());
        assertTrue(r.unboundReason().contains("no page-specific webpack bundle"));
    }

    @Test
    void unresolvedRoute_isUnbound_neverThrows() {
        assertFalse(new DomIdentifierCorrelator().correlate(JspRouteReconstructor.UNRESOLVED_FILE).bound());
    }

    // -----------------------------------------------------------------------
    // Field -> request
    // -----------------------------------------------------------------------

    @Test
    void fieldReadInTheSameFunctionAsARequest_feedsIt() throws IOException {
        project("<input id=\"code\"/>", """
                const Page = {
                    save: function () {
                        const code = $('#code').val();
                        $.ajax({ url: '/fap/save', method: 'POST', data: JSON.stringify({ code: code }) });
                    }
                };
                """);

        FieldCorrelation code = field(correlate(), "code");

        assertEquals(List.of("POST /fap/save"), code.feeds());
        assertEquals(List.of("save()"), code.readIn());
    }

    @Test
    void payloadBuiltByAnotherFunction_throughAVariable_isCorrelated() throws IOException {
        // The real shape: const fap = page.toJsonFap(); ... data: JSON.stringify(fap)
        project("<input id=\"libelle\"/>", """
                const Page = {
                    toJson: function () {
                        return { libelle: $('#libelle').val() };
                    },
                    send: function () {
                        const page = this;
                        const fap = page.toJson();
                        $.ajax({ url: '/fap', method: 'PUT', data: JSON.stringify(fap) });
                    }
                };
                """);

        assertEquals(List.of("PUT /fap (payload built by toJson())"), field(correlate(), "libelle").feeds());
    }

    @Test
    void payloadBuiltInsideTheRequestArguments_isCorrelated() throws IOException {
        project("<input id=\"nom\"/>", """
                const Page = {
                    toJson: function () { return { nom: $('#nom').val() }; },
                    send: function () {
                        $.ajax({ url: '/x', method: 'POST', data: JSON.stringify(Page.toJson()) });
                    }
                };
                """);

        assertEquals(List.of("POST /x (payload built by toJson())"), field(correlate(), "nom").feeds());
    }

    @Test
    void oneHopThroughAnInvokedFunction_isCorrelatedAndLabelled() throws IOException {
        project("<button id=\"go\">Go</button><input id=\"nom\"/>", """
                const Page = {
                    init: function () {
                        $('#go').on('click', function () {
                            const n = $('#nom').val();
                            Page.save(n);
                        });
                    },
                    save: function (n) { $.ajax({ url: '/nom/' + n, method: 'PUT' }); }
                };
                """);

        assertEquals(List.of("PUT /nom/{param} (via save())"), field(correlate(), "nom").feeds());
    }

    @Test
    void readInOneHandler_neverFeedsTheRequestOfASiblingHandler() throws IOException {
        // Both handlers live in the same init(); scoping by the innermost (anonymous) function is
        // what keeps #fieldA from being credited to saveB's request.
        project("<input id=\"fieldA\"/><button id=\"a\">A</button><button id=\"b\">B</button>", """
                const Page = {
                    init: function () {
                        $('#a').on('click', function () { const x = $('#fieldA').val(); Page.saveA(x); });
                        $('#b').on('click', function () { Page.saveB(); });
                    },
                    saveA: function (x) { $.ajax({ url: '/a', method: 'POST' }); },
                    saveB: function () { $.ajax({ url: '/b', method: 'POST' }); }
                };
                """);

        List<String> feeds = field(correlate(), "fieldA").feeds();

        assertEquals(List.of("POST /a (via saveA())"), feeds);
    }

    @Test
    void readInsideASuccessCallback_isNotPayloadOfThatRequest() throws IOException {
        project("<input id=\"shown\"/>", """
                const Page = {
                    load: function () {
                        $.ajax({ url: '/data', method: 'GET', success: function (d) { console.log($('#shown').val()); } });
                    }
                };
                """);

        assertTrue(field(correlate(), "shown").feeds().isEmpty());
    }

    @Test
    void formSerialize_feedsEveryFieldOfTheForm() throws IOException {
        project("<form id=\"f\"><input id=\"a\"/><select id=\"b\"></select></form><input id=\"outside\"/>", """
                const Page = {
                    search: function () { $.ajax({ url: '/search', method: 'GET', data: $('#f').serialize() }); }
                };
                """);

        Result r = correlate();

        assertEquals(List.of("GET /search"), field(r, "a").feeds());
        assertEquals(List.of("GET /search"), field(r, "b").feeds());
        assertTrue(field(r, "outside").feeds().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Indirect literal identifiers
    // -----------------------------------------------------------------------

    @Test
    void selectorStringConstant_isResolved() throws IOException {
        project("<input id=\"projet\"/>", """
                const InitDae = {
                    idProjet: '#projet',
                    envoyer: function () {
                        const page = this;
                        const formData = {};
                        formData["projet"] = $(page.idProjet).val();
                        $.ajax({ url: '/dae/init', method: 'POST', data: JSON.stringify(formData) });
                    }
                };
                """);

        assertEquals(List.of("POST /dae/init"), field(correlate(), "projet").feeds());
    }

    @Test
    void cachedJQueryObject_isResolvedByName() throws IOException {
        project("<input id=\"code\"/>", """
                const Page = {
                    init: function () { this.codeId = $('#code'); },
                    save: function () {
                        const page = this;
                        $.ajax({ url: '/x', method: 'POST', data: { c: page.codeId.val() } });
                    }
                };
                """);

        assertEquals(List.of("POST /x"), field(correlate(), "code").feeds());
    }

    @Test
    void nameBoundToTwoIdentifiers_isListedAsUnresolvable_notGuessed() throws IOException {
        project("<textarea id=\"a\"></textarea><textarea id=\"b\"></textarea>", """
                const Page = {
                    one: function () { this.longueurMax = $('#a'); },
                    two: function () { this.longueurMax = $('#b'); },
                    save: function () { $.ajax({ url: '/x', method: 'POST', data: this.longueurMax.val() }); }
                };
                """);

        Result r = correlate();

        assertTrue(r.unresolvedSelectors().stream().anyMatch(s -> s.contains("longueurMax") && s.contains("#a") && s.contains("#b")));
        assertTrue(field(r, "a").feeds().isEmpty());
        assertTrue(field(r, "b").feeds().isEmpty());
    }

    @Test
    void concatenatedSelector_isListedVerbatimWithItsLocation() throws IOException {
        project("<input id=\"row1\"/>", """
                const Page = {
                    read: function (i) {
                        return $('#row' + i).val();
                    }
                };
                """);

        Result r = correlate();

        assertTrue(r.unresolvedSelectors().stream()
                .anyMatch(s -> s.contains("src/main/js/page.js:3") && s.contains("$('#row' + i)")), r.unresolvedSelectors().toString());
    }

    // -----------------------------------------------------------------------
    // Runtime population
    // -----------------------------------------------------------------------

    @Test
    void controlFilledInARequestCallback_isDynamic_withThatRequestAsItsSource() throws IOException {
        project("<select id=\"marche\"></select>", """
                const Page = {
                    load: function () {
                        $.ajax({ url: '/marche/liste', method: 'GET', success: function (data) {
                            $('#marche').empty();
                            $('#marche').append('<option>x</option>');
                        } });
                    }
                };
                """);

        assertEquals("yes — from GET /marche/liste", field(correlate(), "marche").population());
    }

    @Test
    void controlFilledOutsideAnyRequest_isDynamic_sourceStatedAsUnknown() throws IOException {
        project("<select id=\"liste\"></select>", """
                const Page = {
                    refresh: function (items) { $('#liste').html(items.join('')); }
                };
                """);

        assertEquals("yes — source not statically determined (in refresh())", field(correlate(), "liste").population());
    }

    @Test
    void fieldNeverReferencedByScript_hasNoReadsAndNoPopulation() throws IOException {
        project("<input id=\"unused\"/>", "const Page = {};");

        FieldCorrelation unused = field(correlate(), "unused");

        assertTrue(unused.readIn().isEmpty());
        assertNull(unused.population());
        assertTrue(unused.feeds().isEmpty());
        assertFalse(unused.referencedByScript());
    }

    @Test
    void fieldOnlyEverWritten_isReferenced_notReportedAsUntouchedByScript() throws IOException {
        // The real shape: radio buttons toggled through selector constants, never read back.
        project("<input type=\"radio\" id=\"TypeEC\"/>", """
                const InitDae = {
                    idRadioEC: '#TypeEC',
                    init: function () { $(this.idRadioEC).prop('checked', true); }
                };
                """);

        FieldCorrelation radio = field(correlate(), "TypeEC");

        assertTrue(radio.readIn().isEmpty());
        assertTrue(radio.referencedByScript());
    }

    // -----------------------------------------------------------------------
    // Script-generated controls
    // -----------------------------------------------------------------------

    @Test
    void generatedControlWithLiteralId_isCorrelated_runtimeIdOnesAreCounted() throws IOException {
        project("<div id=\"zone\"></div>", """
                const Page = {
                    build: function (id) {
                        $('#zone').append('<input type="text" id="dynamicCode"/>');
                        $('#zone').append('<input type="text" id="' + id + '"/>');
                    },
                    save: function () { $.ajax({ url: '/g', method: 'POST', data: { v: $('#dynamicCode').val() } }); }
                };
                """);

        Result r = correlate();

        FieldCorrelation generated = field(r, "dynamicCode");
        assertFalse(generated.fromMarkup());
        assertTrue(generated.origin().startsWith("script-generated at src/main/js/page.js:"));
        assertEquals(List.of("POST /g"), generated.feeds());
        assertEquals(1, r.uncorrelatedGeneratedControls());
    }

    // -----------------------------------------------------------------------
    // Business-trigger events
    // -----------------------------------------------------------------------

    @Test
    void buttonWhoseHandlerReachesARequest_isAnEvent_navigationOnlyHandlerIsNot() throws IOException {
        project("<button id=\"exporter\">Exporter</button><button id=\"retour\">Retour</button>", """
                const Page = {
                    init: function () {
                        const page = this;
                        $('#retour').on('click', function () { window.location = basepath + '/view/reporting/accueil'; });
                        $('#exporter').on('click', function () { page.demandeExport(); });
                    },
                    demandeExport: function () { $.ajax({ url: '/reporting/export', method: 'POST' }); }
                };
                """);

        List<TemplateEventExtractorStrategy.Event> events = correlate().events();

        assertEquals(1, events.size(), events.toString());
        assertEquals("click", events.get(0).trigger());
        assertEquals("Button 'Exporter' (#exporter) → POST /reporting/export (via demandeExport())",
                events.get(0).description());
    }

    @Test
    void handlerPassedByReference_isFollowed() throws IOException {
        project("<input type=\"button\" id=\"save\" value=\"Enregistrer\"/>", """
                const Page = {
                    init: function () { $('#save').click(Page.enregistrer); },
                    enregistrer: function () { $.ajax({ url: '/fee', method: 'PUT' }); }
                };
                """);

        List<TemplateEventExtractorStrategy.Event> events = correlate().events();

        assertEquals(1, events.size());
        assertEquals("Button 'Enregistrer' (#save) → PUT /fee", events.get(0).description());
    }

    @Test
    void factoryRoutesJspJQueryEventsThroughTheCorrelator() throws IOException {
        project("<button id=\"b\">B</button>", """
                const Page = { init: function () { $('#b').on('click', function () { $.ajax({ url: '/b', method: 'DELETE' }); }); } };
                """);

        List<TemplateEventExtractorStrategy.Event> events = TemplateEventExtractorFactory
                .createFor(FrontendFramework.JSP_JQUERY).extract(jsp().toString(), Map.of());

        assertEquals(1, events.size());
        assertTrue(events.get(0).description().endsWith("→ DELETE /b"));
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    @Test
    void pageDocument_carriesTheCorrelationTableAndTheUnresolvableList() throws IOException {
        project("<input id=\"code\"/>", """
                const Page = {
                    save: function (i) {
                        const c = $('#code').val();
                        const other = $('#dyn' + i).val();
                        $.ajax({ url: '/save', method: 'POST', data: { c: c } });
                    }
                };
                """);
        ComponentInfo comp = new ComponentInfo("PagePage", jsp().toString(), null, List.of());
        RouteNode route = new RouteNode("/view/page", "PagePage", "Page", null, false, List.of());

        String md = new FrontendPagesRenderer().render(List.of(route), Map.of("PagePage", comp),
                new EndpointMatcher.MatchResult(List.of(), List.of(), List.of()), List.of(), false,
                FrontendFramework.JSP_JQUERY);

        assertTrue(md.contains("**Field ↔ Request Correlation:** 1 of 1 field(s) feed a request"), md);
        assertTrue(md.contains("| `code` | POST /save | save() | — |"), md);
        assertTrue(md.contains("Unresolvable selectors"), md);
        assertTrue(md.contains("$('#dyn' + i)"), md);
    }
}
