package com.devmanchego.contextextractor.jsp;

import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JspFileParserTest {

    @TempDir
    Path tempDir;

    private final JspFileParser parser = new JspFileParser();

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    // -----------------------------------------------------------------------
    // Page directives
    // -----------------------------------------------------------------------

    @Test
    void pageDirective_attributesCaptured() throws IOException {
        Path jsp = write("page.jsp", """
                <%@ page language="java" pageEncoding="UTF-8" contentType="text/html" %>
                <html></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals("UTF-8", page.pageDirectiveAttributes().get("pageEncoding"));
        assertEquals("java", page.pageDirectiveAttributes().get("language"));
        assertEquals("text/html", page.pageDirectiveAttributes().get("contentType"));
    }

    @Test
    void pageDirective_repeatedImportIsAccumulated() throws IOException {
        Path jsp = write("page.jsp", """
                <%@ page import="java.util.List" %>
                <%@ page import="java.util.Map" %>
                <html></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals("java.util.List,java.util.Map", page.pageDirectiveAttributes().get("import"));
    }

    @Test
    void directiveTags_neverAppearInComposedMarkup() throws IOException {
        Path jsp = write("page.jsp", """
                <%@ page language="java" %>
                <html><body>content</body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertFalse(page.document().html().contains("<%@"));
    }

    // -----------------------------------------------------------------------
    // Static includes — acceptance: "a view assembled from nested includes
    // yields one tree containing all fragments"
    // -----------------------------------------------------------------------

    @Test
    void relativeInclude_splicedIntoTheTree() throws IOException {
        write("fragments/footer.jsp", "<footer id=\"foot\">footer text</footer>");
        Path jsp = write("page.jsp", """
                <html><body>
                <%@ include file="fragments/footer.jsp" %>
                </body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertNotNull(page.document().getElementById("foot"));
        assertEquals("footer text", page.document().getElementById("foot").text());
    }

    @Test
    void absoluteInclude_resolvedAgainstWebappRoot() throws IOException {
        write("WEB-INF/jsp/fragments/menu.jsp", "<nav id=\"menu\">menu</nav>");
        Path jsp = write("WEB-INF/jsp/achat/fap.jsp", """
                <html><body>
                <%@ include file="/WEB-INF/jsp/fragments/menu.jsp" %>
                </body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertNotNull(page.document().getElementById("menu"));
    }

    @Test
    void nestedIncludes_allComposedIntoOneTree() throws IOException {
        write("fragments/inner.jsp", "<span id=\"inner\">inner</span>");
        write("fragments/outer.jsp", """
                <div id="outer">
                <%@ include file="inner.jsp" %>
                </div>
                """);
        Path jsp = write("page.jsp", """
                <html><body>
                <%@ include file="fragments/outer.jsp" %>
                </body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        Element outer = page.document().getElementById("outer");
        Element inner = page.document().getElementById("inner");
        assertNotNull(outer);
        assertNotNull(inner);
        assertEquals(outer, inner.parent());
        assertEquals(2, page.includedFiles().size());
    }

    @Test
    void unresolvableInclude_producesWarning_doesNotThrow() throws IOException {
        Path jsp = write("page.jsp", """
                <html><body>
                <%@ include file="does-not-exist.jsp" %>
                </body></html>
                """);

        JspPage page = assertDoesNotThrow(() -> parser.parse(jsp, tempDir));

        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("could not be resolved")));
        assertTrue(page.includedFiles().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Cyclic includes — acceptance: "an artificially cyclic include set
    // produces a warning, not a stack overflow"
    // -----------------------------------------------------------------------

    @Test
    void cyclicIncludes_warnRatherThanStackOverflow() throws IOException {
        write("a.jsp", "<div id=\"a\"><%@ include file=\"b.jsp\" %></div>");
        write("b.jsp", "<div id=\"b\"><%@ include file=\"a.jsp\" %></div>");
        Path entry = write("entry.jsp", "<%@ include file=\"a.jsp\" %>");

        JspPage page = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> parser.parse(entry, tempDir));

        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("Cyclic")),
                "Expected a cycle warning: " + page.warnings());
        // Both a.jsp and b.jsp were still reached once each before the cycle was cut.
        assertEquals(2, page.includedFiles().size());
    }

    @Test
    void selfReferencingInclude_alsoDetectedAsACycle() throws IOException {
        Path entry = write("entry.jsp", "<div><%@ include file=\"entry.jsp\" %></div>");

        JspPage page = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> parser.parse(entry, tempDir));

        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("Cyclic")));
    }

    // -----------------------------------------------------------------------
    // Provenance — acceptance: "every element retains its source file"
    // -----------------------------------------------------------------------

    @Test
    void provenance_entryFileContentAttributedToEntryFile() throws IOException {
        Path jsp = write("page.jsp", "<html><body><p id=\"own\">own content</p></body></html>");

        JspPage page = parser.parse(jsp, tempDir);

        Element own = page.document().getElementById("own");
        assertEquals(page.entryFile(), page.sourceFileOf(own));
    }

    @Test
    void provenance_includedFragmentAttributedToItsOwnFile() throws IOException {
        Path footer = write("fragments/footer.jsp", "<footer id=\"foot\">footer</footer>");
        Path jsp = write("page.jsp", """
                <html><body>
                <p id="own">own</p>
                <%@ include file="fragments/footer.jsp" %>
                </body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals(page.entryFile(), page.sourceFileOf(page.document().getElementById("own")));
        assertEquals(footer.toAbsolutePath().normalize(),
                page.sourceFileOf(page.document().getElementById("foot")));
    }

    @Test
    void provenance_descendantOfIncludedFragment_alsoAttributedCorrectly() throws IOException {
        Path footer = write("fragments/footer.jsp", "<footer id=\"foot\"><span id=\"deep\">x</span></footer>");
        Path jsp = write("page.jsp", "<html><body><%@ include file=\"fragments/footer.jsp\" %></body></html>");

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals(footer.toAbsolutePath().normalize(),
                page.sourceFileOf(page.document().getElementById("deep")));
    }

    @Test
    void provenance_nestedIncludes_innerAndOuterDistinguished() throws IOException {
        Path inner = write("fragments/inner.jsp", "<span id=\"inner\">inner</span>");
        Path outer = write("fragments/outer.jsp", """
                <div id="outer">
                <p id="outerOwn">outer's own text</p>
                <%@ include file="inner.jsp" %>
                </div>
                """);
        Path jsp = write("page.jsp", "<html><body><%@ include file=\"fragments/outer.jsp\" %></body></html>");

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals(outer.toAbsolutePath().normalize(),
                page.sourceFileOf(page.document().getElementById("outerOwn")));
        assertEquals(inner.toAbsolutePath().normalize(),
                page.sourceFileOf(page.document().getElementById("inner")));
    }

    @Test
    void provenance_contentAfterAnIncludedFragment_attributedBackToTheIncludingFile() throws IOException {
        write("fragments/a.jsp", "<div id=\"a\">a</div>");
        Path jsp = write("page.jsp", """
                <html><body>
                <%@ include file="fragments/a.jsp" %>
                <p id="afterA">back in page.jsp</p>
                </body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals(page.entryFile(), page.sourceFileOf(page.document().getElementById("afterA")));
    }

    @Test
    void provenance_survivesHtml5RelocationOutOfHead() throws IOException {
        // The exact real-world shape this guards against: a fragment meant for the body
        // (a <div>) included inside <head> — invalid HTML5, but real browsers (and jsoup)
        // recover by relocating the element to <body>. Provenance must travel with it.
        Path menu = write("fragments/menu.jsp", "<div id=\"menu-navbar\">menu content</div>");
        Path jsp = write("page.jsp", """
                <html><head>
                <title>t</title>
                <%@ include file="fragments/menu.jsp" %>
                </head><body></body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        Element div = page.document().getElementById("menu-navbar");
        assertNotNull(div, "jsoup should have relocated the div into <body>");
        assertEquals("body", div.parent().tagName(), "Precondition: relocation actually happened");
        assertEquals(menu.toAbsolutePath().normalize(), page.sourceFileOf(div),
                "Provenance must survive HTML5 relocation, not just simple cases");
    }

    @Test
    void provenance_headAppropriateFragment_tagsSurviveNormally() throws IOException {
        // The header.jsp shape: multiple top-level sibling tags (title/meta/link), no wrapper —
        // every one of them must be individually attributable, not just the first.
        Path header = write("fragments/header.jsp", """
                <title>efap-ha</title>
                <meta charset="UTF-8"/>
                <link rel="stylesheet" href="commons.css"/>
                """);
        Path jsp = write("page.jsp", """
                <html><head>
                <%@ include file="fragments/header.jsp" %>
                </head><body></body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);
        Path headerReal = header.toAbsolutePath().normalize();

        assertEquals(headerReal, page.sourceFileOf(page.document().select("title").first()));
        assertEquals(headerReal, page.sourceFileOf(page.document().select("meta").first()));
        assertEquals(headerReal, page.sourceFileOf(page.document().select("link").first()));
    }

    @Test
    void provenance_nestedIncludeThatIsTheFragmentsOnlyContent_innermostWins() throws IOException {
        // A degenerate but real shape: a fragment whose entire body is itself another include.
        // The nested fragment's own attribution must not be overwritten by the outer one.
        Path inner = write("fragments/inner.jsp", "<span id=\"inner\">inner</span>");
        write("fragments/wrapper.jsp", "<%@ include file=\"inner.jsp\" %>");
        Path jsp = write("page.jsp", "<html><body><%@ include file=\"fragments/wrapper.jsp\" %></body></html>");

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals(inner.toAbsolutePath().normalize(),
                page.sourceFileOf(page.document().getElementById("inner")),
                "The innermost, most specific file must win, not the outer wrapper that merely passed it through");
    }

    @Test
    void provenance_twoSiblingIncludes_bothCorrectlyAttributed() throws IOException {
        Path first = write("fragments/first.jsp", "<div id=\"first\">first</div>");
        Path second = write("fragments/second.jsp", "<div id=\"second\">second</div>");
        Path jsp = write("page.jsp", """
                <html><body>
                <%@ include file="fragments/first.jsp" %>
                <%@ include file="fragments/second.jsp" %>
                <p id="afterBoth">after both</p>
                </body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals(first.toAbsolutePath().normalize(), page.sourceFileOf(page.document().getElementById("first")));
        assertEquals(second.toAbsolutePath().normalize(), page.sourceFileOf(page.document().getElementById("second")));
        assertEquals(page.entryFile(), page.sourceFileOf(page.document().getElementById("afterBoth")));
    }

    // -----------------------------------------------------------------------
    // Non-goals — detected and warned, never interpreted
    // -----------------------------------------------------------------------

    @Test
    void jspComments_stripped_neverInWarnings() throws IOException {
        Path jsp = write("page.jsp", "<%-- this is a JSP comment --%><html></html>");

        JspPage page = parser.parse(jsp, tempDir);

        assertFalse(page.document().html().contains("this is a JSP comment"));
        assertTrue(page.warnings().isEmpty());
    }

    @Test
    void scriptlet_strippedAndWarned_neverEvaluated() throws IOException {
        Path jsp = write("page.jsp", "<html><body><% int x = 1 + 1; %></body></html>");

        JspPage page = parser.parse(jsp, tempDir);

        assertFalse(page.document().html().contains("int x"));
        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("Scriptlet")));
    }

    @Test
    void expressionScriptlet_alsoStrippedAndWarned() throws IOException {
        Path jsp = write("page.jsp", "<html><body><%= someValue %></body></html>");

        JspPage page = parser.parse(jsp, tempDir);

        assertFalse(page.document().html().contains("someValue"));
        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("Scriptlet")));
    }

    @Test
    void dynamicInclude_detectedAndWarned_tagLeftInTree() throws IOException {
        Path jsp = write("page.jsp", "<html><body><jsp:include page=\"other.jsp\"/></body></html>");

        JspPage page = parser.parse(jsp, tempDir);

        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("jsp:include")));
        assertTrue(page.document().html().toLowerCase().contains("jsp:include"));
    }

    @Test
    void jstlTaglibUsed_warnedOnce() throws IOException {
        Path jsp = write("page.jsp", """
                <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                <html><body>
                <c:if test="${true}">shown</c:if>
                <c:forEach items="${list}" var="item">${item}</c:forEach>
                </body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        long jstlWarnings = page.warnings().stream().filter(w -> w.contains("JSTL")).count();
        assertEquals(1, jstlWarnings, "Expected exactly one JSTL warning per prefix, not one per tag: "
                + page.warnings());
    }

    @Test
    void jstlTaglibDeclaredButUnused_noWarning() throws IOException {
        Path jsp = write("page.jsp", """
                <%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
                <html><body>no jstl tags actually used here</body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertTrue(page.warnings().stream().noneMatch(w -> w.contains("JSTL")));
    }

    @Test
    void nonJstlTaglib_neverWarnedAboutAsJstl() throws IOException {
        // The target profile's actual taglibs: Spring Security and Spring's own message tag.
        // Neither is JSTL, and must never be flagged as such.
        Path jsp = write("page.jsp", """
                <%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
                <%@ taglib prefix="spring" uri="http://www.springframework.org/tags" %>
                <html><body>
                <sec:authorize access="hasPermission('','ACHAT')">shown</sec:authorize>
                <spring:message code="version" />
                </body></html>
                """);

        JspPage page = parser.parse(jsp, tempDir);

        assertTrue(page.warnings().stream().noneMatch(w -> w.contains("JSTL")));
        // The tags themselves are preserved, untouched.
        assertTrue(page.document().html().contains("sec:authorize"));
    }

    @Test
    void unsupportedDirective_warnedAndStripped() throws IOException {
        Path jsp = write("page.jsp", "<%@ tag import=\"x\" %><html></html>");

        JspPage page = parser.parse(jsp, tempDir);

        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("Unsupported JSP directive")));
        assertFalse(page.document().html().contains("<%@"));
    }

    // -----------------------------------------------------------------------
    // Context-path EL substitution
    // -----------------------------------------------------------------------

    @Test
    void contextPathExpression_substitutedWithPlaceholder() throws IOException {
        Path jsp = write("page.jsp",
                "<a href=\"${pageContext.request.contextPath}/view/achat\">link</a>");

        JspPage page = parser.parse(jsp, tempDir);

        String href = page.document().select("a").attr("href");
        assertEquals("{contextPath}/view/achat", href);
    }

    @Test
    void otherElExpressions_leftUntouched() throws IOException {
        Path jsp = write("page.jsp", "<span id=\"u\">${utilisateur} ${lastUserName}</span>");

        JspPage page = parser.parse(jsp, tempDir);

        assertEquals("${utilisateur} ${lastUserName}", page.document().getElementById("u").text());
    }

    // -----------------------------------------------------------------------
    // locateWebappRoot
    // -----------------------------------------------------------------------

    @Test
    void locateWebappRoot_findsAncestorContainingWebInf() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/achat/fap.jsp", "<html></html>");

        Path root = JspFileParser.locateWebappRoot(jsp);

        assertNotNull(root);
        assertEquals(tempDir.resolve("src/main/webapp").toAbsolutePath().normalize(), root);
    }

    @Test
    void locateWebappRoot_returnsNullWhenNoWebInfAncestorExists() throws IOException {
        Path jsp = write("random/page.jsp", "<html></html>");

        assertNull(JspFileParser.locateWebappRoot(jsp));
    }
}
