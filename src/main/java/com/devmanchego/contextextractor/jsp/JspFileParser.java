package com.devmanchego.contextextractor.jsp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses a JSP view for the JSP + jQuery frontend class: page directives, recursive resolution
 * of static includes ({@code <%@ include file="..." %>}), and composition into one jsoup
 * {@link Document} — the shared markup model every later phase (form-field extraction, sitemap
 * reconstruction, identifier correlation) builds on.
 *
 * <p><b>Deliberately out of scope</b> — never interpreted, only detected and warned about:
 * <ul>
 *   <li>Scriptlets and expression scriptlets ({@code <% ... %>}, {@code <%= ... %>}) — stripped
 *       (they contain arbitrary Java that would break markup parsing) with a warning per
 *       occurrence, replaced by a generic marker comment, never evaluated.</li>
 *   <li>JSTL tag libraries ({@code c:if}, {@code c:forEach}, {@code fmt:*}, ...) — detected by
 *       matching a file's {@code <%@ taglib %>} URIs against the standard JSTL namespaces; the
 *       tags themselves are left in the tree untouched (jsoup parses them as ordinary, if
 *       custom-namespaced, elements) with one warning per prefix found in use.</li>
 *   <li>Dynamic includes ({@code <jsp:include>}) — detected and warned about once per page;
 *       never resolved (that would require the same runtime request context a JSP engine has
 *       and this tool deliberately doesn't simulate).</li>
 *   <li>Layout frameworks (Tiles, SiteMesh) and custom tag files ({@code .tag}) — not
 *       recognised at all; any markup they'd have produced simply isn't there, which is exactly
 *       the honest degradation this class's non-goals call for rather than guessing.</li>
 * </ul>
 *
 * <p>See {@code java-fullstack-extractor}'s JSP frontend support specification, Phase 01, for
 * the full non-goals list and rationale.
 */
public final class JspFileParser {

    private static final Logger log = LoggerFactory.getLogger(JspFileParser.class);

    // <%-- ... --%> — JSP comments, never rendered by a real JSP engine either.
    private static final Pattern JSP_COMMENT = Pattern.compile("<%--.*?--%>", Pattern.DOTALL);

    // <%@ name attr="val" attr2="val2" %> — any directive, dispatched on name below.
    private static final Pattern DIRECTIVE = Pattern.compile(
            "<%@\\s*(\\w+)((?:\\s+[\\w:-]+\\s*=\\s*\"[^\"]*\")*)\\s*%>");

    private static final Pattern ATTR = Pattern.compile("([\\w:-]+)\\s*=\\s*\"([^\"]*)\"");

    // Any remaining <% ... %> once directives and comments are already gone is a scriptlet or
    // expression scriptlet — there is nothing else that syntax could still be at this point.
    private static final Pattern SCRIPTLET = Pattern.compile("<%(.*?)%>", Pattern.DOTALL);

    private static final Pattern DYNAMIC_INCLUDE = Pattern.compile(
            "<jsp:include\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTEXT_PATH_EL = Pattern.compile(
            "\\$\\{\\s*pageContext\\.request\\.contextPath\\s*}");

    private static final Set<String> JSTL_URIS = Set.of(
            "http://java.sun.com/jsp/jstl/core", "http://java.sun.com/jstl/core",
            "http://java.sun.com/jsp/jstl/fmt", "http://java.sun.com/jstl/fmt",
            "http://java.sun.com/jsp/jstl/sql", "http://java.sun.com/jstl/sql",
            "http://java.sun.com/jsp/jstl/xml", "http://java.sun.com/jstl/xml",
            "http://java.sun.com/jsp/jstl/functions", "http://java.sun.com/jstl/functions",
            "jakarta.tags.core", "jakarta.tags.fmt", "jakarta.tags.sql",
            "jakarta.tags.xml", "jakarta.tags.functions");

    /**
     * @param entryFile  the JSP view to parse
     * @param webappRoot the directory an absolute {@code <%@ include file="/..." %>} path is
     *                   resolved against — normally the directory directly containing
     *                   {@code WEB-INF} (see {@link #locateWebappRoot(Path)})
     */
    public JspPage parse(Path entryFile, Path webappRoot) {
        Path entryReal = entryFile.toAbsolutePath().normalize();
        Path webappRootReal = webappRoot.toAbsolutePath().normalize();
        ParseState state = new ParseState(webappRootReal);

        String composed;
        try {
            composed = composeFile(entryReal, List.of(entryReal), state);
        } catch (IOException e) {
            state.warnings.add("Could not read " + rel(entryReal, webappRootReal) + ": " + e.getMessage());
            composed = "";
        }

        warnOnDynamicIncludes(composed, state);
        warnOnJstlUsage(composed, state);

        Document document = Jsoup.parse(composed);
        log.debug("JspFileParser: composed {} from {} include(s), {} warning(s): {}",
                rel(entryReal, webappRootReal), state.includedFiles.size(), state.warnings.size(), entryReal);
        return new JspPage(entryReal, document, state.warnings, state.pageDirectiveAttributes, state.includedFiles);
    }

    /**
     * Walks up from a JSP file looking for the ancestor directory whose direct child is named
     * {@code WEB-INF} — the conventional web application root, and what an absolute
     * {@code <%@ include file="/..." %>} path is resolved against. Returns {@code null} when no
     * such ancestor exists (e.g. a synthetic test fixture with no {@code WEB-INF} at all —
     * callers in that position should supply the root explicitly instead).
     */
    public static Path locateWebappRoot(Path anyJspFile) {
        Path dir = anyJspFile.toAbsolutePath().normalize();
        if (Files.isRegularFile(dir)) dir = dir.getParent();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("WEB-INF"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Per-file composition
    // -----------------------------------------------------------------------

    /** Mutable state threaded through one {@link #parse} call's recursive composition. */
    private static final class ParseState {
        final Path webappRoot;
        final List<String> warnings = new ArrayList<>();
        final List<Path> includedFiles = new ArrayList<>();
        final Map<String, String> pageDirectiveAttributes = new LinkedHashMap<>();
        final Map<String, String> taglibs = new LinkedHashMap<>(); // prefix -> uri, whole page

        ParseState(Path webappRoot) { this.webappRoot = webappRoot; }
    }

    private String composeFile(Path file, List<Path> chain, ParseState state) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        content = JSP_COMMENT.matcher(content).replaceAll("");
        content = processDirectivesAndIncludes(content, file, chain, state);
        content = stripScriptletsAndWarn(content, file, state);
        content = CONTEXT_PATH_EL.matcher(content).replaceAll("{contextPath}");
        return content;
    }

    private String processDirectivesAndIncludes(String content, Path file, List<Path> chain, ParseState state) {
        Matcher m = DIRECTIVE.matcher(content);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(content, last, m.start());
            last = m.end();
            String name = m.group(1).toLowerCase(Locale.ROOT);
            Map<String, String> attrs = parseAttributes(m.group(2));
            switch (name) {
                case "page" -> mergePageDirective(attrs, state);
                case "taglib" -> {
                    String prefix = attrs.get("prefix");
                    String uri = attrs.get("uri");
                    if (prefix != null && uri != null) state.taglibs.put(prefix, uri);
                }
                case "include" -> out.append(resolveInclude(attrs.get("file"), file, chain, state));
                default -> state.warnings.add("Unsupported JSP directive '<%@ " + name
                        + " %>' in " + rel(file, state.webappRoot) + " — left unprocessed.");
            }
        }
        out.append(content, last, content.length());
        return out.toString();
    }

    private void mergePageDirective(Map<String, String> attrs, ParseState state) {
        attrs.forEach((key, value) -> {
            if ("import".equals(key) && state.pageDirectiveAttributes.containsKey("import")) {
                state.pageDirectiveAttributes.merge("import", value, (a, b) -> a + "," + b);
            } else {
                state.pageDirectiveAttributes.put(key, value);
            }
        });
    }

    private String resolveInclude(String fileAttr, Path currentFile, List<Path> chain, ParseState state) {
        if (fileAttr == null || fileAttr.isBlank()) {
            state.warnings.add("'<%@ include %>' in " + rel(currentFile, state.webappRoot)
                    + " has no file attribute — skipped.");
            return "";
        }

        Path target = fileAttr.startsWith("/")
                ? state.webappRoot.resolve(fileAttr.substring(1)).normalize()
                : currentFile.getParent().resolve(fileAttr).normalize();

        if (!Files.isRegularFile(target)) {
            state.warnings.add("'<%@ include file=\"" + fileAttr + "\" %>' in "
                    + rel(currentFile, state.webappRoot) + " could not be resolved to an existing file "
                    + "(looked for " + rel(target, state.webappRoot) + ").");
            return "<!--jsp-include-unresolved: " + fileAttr + "-->";
        }

        Path targetReal = target.toAbsolutePath().normalize();
        if (chain.contains(targetReal)) {
            String cycle = chain.stream().map(p -> rel(p, state.webappRoot)).collect(Collectors.joining(" -> "))
                    + " -> " + rel(targetReal, state.webappRoot);
            state.warnings.add("Cyclic '<%@ include %>' detected: " + cycle + " — not re-included.");
            return "<!--jsp-include-cycle: " + rel(targetReal, state.webappRoot) + "-->";
        }

        List<Path> childChain = new ArrayList<>(chain);
        childChain.add(targetReal);

        String fragment;
        try {
            fragment = composeFile(targetReal, childChain, state);
        } catch (IOException e) {
            state.warnings.add("Could not read included file " + rel(targetReal, state.webappRoot)
                    + " (included from " + rel(currentFile, state.webappRoot) + "): " + e.getMessage());
            return "<!--jsp-include-unresolved: " + fileAttr + "-->";
        }

        state.includedFiles.add(targetReal);
        return tagTopLevelElementsWithSource(fragment, targetReal);
    }

    /**
     * Marks every top-level element of a resolved fragment with its source file, so provenance
     * survives whatever the final whole-page parse does to it — see {@link JspProvenance}'s
     * class documentation for why a sibling comment marker doesn't survive this and an
     * attribute does. Parses the fragment in isolation via {@code parseBodyFragment}, which —
     * unlike a full-document parse — never relocates content (there is no head/body context to
     * relocate <em>from</em>), so it reliably finds the fragment's true top-level nodes whether
     * they look like head content (a header fragment's {@code <title>}/{@code <meta>}/
     * {@code <link>}) or body content, without trying to guess which.
     *
     * <p>A node already carrying the attribute — the top-level element of a fragment nested
     * inside this one, tagged during its own recursive resolution — is left alone: the
     * innermost, most specific attribution wins, not the outermost file that happened to
     * include it last.
     */
    private String tagTopLevelElementsWithSource(String fragmentHtml, Path sourceFile) {
        Document fragmentDoc = Jsoup.parseBodyFragment(fragmentHtml);
        StringBuilder out = new StringBuilder();
        for (org.jsoup.nodes.Node child : fragmentDoc.body().childNodes()) {
            if (child instanceof org.jsoup.nodes.Element el && !el.hasAttr(JspProvenance.SOURCE_ATTR)) {
                el.attr(JspProvenance.SOURCE_ATTR, sourceFile.toString());
            }
            out.append(child.outerHtml());
        }
        return out.toString();
    }

    private String stripScriptletsAndWarn(String content, Path file, ParseState state) {
        Matcher m = SCRIPTLET.matcher(content);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(content, last, m.start());
            last = m.end();
            String snippet = m.group(1).strip().replaceAll("\\s+", " ");
            if (snippet.length() > 60) snippet = snippet.substring(0, 60) + "...";
            state.warnings.add("Scriptlet in " + rel(file, state.webappRoot)
                    + " is not evaluated (out of scope): " + snippet);
            out.append("<!--jsp-scriptlet-removed-->");
        }
        out.append(content, last, content.length());
        return out.toString();
    }

    private void warnOnDynamicIncludes(String composed, ParseState state) {
        if (DYNAMIC_INCLUDE.matcher(composed).find()) {
            state.warnings.add("This page uses '<jsp:include>' (dynamic include) — not resolved; "
                    + "the tag is left in the composed markup, unevaluated.");
        }
    }

    private void warnOnJstlUsage(String composed, ParseState state) {
        state.taglibs.forEach((prefix, uri) -> {
            if (!JSTL_URIS.contains(uri)) return;
            if (composed.contains("<" + prefix + ":")) {
                state.warnings.add("This page uses the JSTL taglib '" + prefix + "' (" + uri
                        + ") — JSTL tags are not evaluated; only their static markup shape is preserved.");
            }
        });
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private static Map<String, String> parseAttributes(String attrBlob) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (attrBlob == null) return attrs;
        Matcher m = ATTR.matcher(attrBlob);
        while (m.find()) {
            attrs.put(m.group(1), m.group(2));
        }
        return attrs;
    }

    private static String rel(Path p, Path webappRoot) {
        try {
            return webappRoot.relativize(p).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString().replace('\\', '/');
        }
    }
}
