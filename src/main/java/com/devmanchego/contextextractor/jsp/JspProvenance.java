package com.devmanchego.contextextractor.jsp;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.nio.file.Path;

/**
 * Resolves which source file a node in a composed {@link JspPage#document()} came from.
 *
 * <p>Each top-level element of a spliced-in fragment carries a {@value #SOURCE_ATTR} attribute
 * naming its source file; {@link #sourceFileOf} walks up from any node to the nearest ancestor
 * (or the node itself) carrying it, defaulting to the page's own entry file when none is found.
 *
 * <p>This is attribute-based, not a wrapper element or a sibling comment marker, for a verified
 * reason: jsoup's HTML5 tree builder relocates content that isn't legal where it was written —
 * most visibly, a block element spliced into {@code <head>} (which happens in practice: a
 * {@code <%@ include %>} of a fragment meant for the body, placed inside {@code <head>} by the
 * including page) gets moved to {@code <body>} during the final parse. Confirmed empirically: a
 * sibling HTML comment immediately before such an element is <em>not</em> relocated with it —
 * only the element itself moves, silently separating a marker from the content it bounded. An
 * attribute on the element itself has no such problem: it is part of the element, so it moves
 * with it regardless of where the tree builder decides the element belongs.
 */
public final class JspProvenance {

    static final String SOURCE_ATTR = "data-jsp-source";

    private JspProvenance() {}

    /**
     * @param node      any node in a {@link JspPage#document()}
     * @param entryFile the page's own entry file — returned when {@code node} sits outside
     *                  every spliced-in fragment (or is a bare text/comment node at a
     *                  fragment's own top level, which carries no attribute to tag)
     * @return the source file {@code node} was composed from
     */
    public static Path sourceFileOf(Node node, Path entryFile) {
        Node current = node;
        while (current != null) {
            if (current instanceof Element el && el.hasAttr(SOURCE_ATTR)) {
                return Path.of(el.attr(SOURCE_ATTR));
            }
            current = current.parent();
        }
        return entryFile;
    }
}
