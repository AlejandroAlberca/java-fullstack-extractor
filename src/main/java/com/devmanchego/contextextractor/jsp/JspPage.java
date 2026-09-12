package com.devmanchego.contextextractor.jsp;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A single JSP view, fully composed by {@link JspFileParser}: every static include resolved
 * and spliced in place, JSP comments removed, scriptlets stripped (never evaluated — scriptlets
 * and JSTL are explicitly out of scope, see {@link JspFileParser}), and the servlet
 * context-path EL expression ({@code ${pageContext.request.contextPath}}) normalised to the
 * {@code {contextPath}} placeholder used throughout this codebase's URL handling.
 *
 * @param entryFile               the JSP file this page was parsed from (absolute, normalized)
 * @param document                the composed markup, as a jsoup {@link Document}
 * @param warnings                out-of-scope constructs encountered (scriptlets, JSTL usage,
 *                                dynamic includes), and any unresolved or cyclic static include
 * @param pageDirectiveAttributes attributes captured from the entry file's own
 *                                {@code <%@ page %>} directive(s) — e.g. {@code pageEncoding},
 *                                {@code contentType}, accumulated {@code import}
 * @param includedFiles           every file successfully spliced in via a static include, in
 *                                inclusion order — excludes files that hit a cycle or failed
 *                                to resolve (those are reported in {@link #warnings()} instead)
 */
public record JspPage(
        Path entryFile,
        Document document,
        List<String> warnings,
        Map<String, String> pageDirectiveAttributes,
        List<Path> includedFiles) {

    public JspPage {
        warnings = List.copyOf(warnings);
        pageDirectiveAttributes = Map.copyOf(pageDirectiveAttributes);
        includedFiles = List.copyOf(includedFiles);
    }

    /**
     * The source file {@code node} (anywhere in {@link #document()}) was actually composed
     * from — the entry file itself, or one of {@link #includedFiles()}. See {@link JspProvenance}.
     */
    public Path sourceFileOf(Node node) {
        return JspProvenance.sourceFileOf(node, entryFile);
    }
}
