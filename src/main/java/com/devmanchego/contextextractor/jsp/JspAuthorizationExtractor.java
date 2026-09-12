package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.java.security.PreAuthorizeExpressionParser;
import com.devmanchego.contextextractor.java.security.SecurityRule;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts UI-fragment-level authorisation from a composed JSP view's markup (Phase 01): every
 * {@code <sec:authorize access="...">} block anywhere on the page, not only the ones wrapping a
 * navigation link that {@code JspRouteReconstructor} already reads for the sitemap's per-URL
 * permission. A page routinely gates far more than its own nav entry — an admin-only button, an
 * internal-only column, an entire section — and each such block is a real fact about the
 * application's access control this class surfaces as its own {@link SecurityRule}, tagged
 * {@link SecurityRule.RuleStrategy#UI_FRAGMENT} precisely because it is <em>not</em> the same
 * claim an endpoint-level rule makes — see {@code SecurityMatrix.uiFragmentRules()}.
 *
 * <p>Nested {@code <sec:authorize>} blocks are each reported individually, at the permission
 * level that block itself declares — a reader wants to know both "this whole section requires
 * ACHAT" and "and within it, this part additionally requires INTERNE", not one merged fact that
 * loses which permission gates how much of the page.
 */
public final class JspAuthorizationExtractor {

    private static final Logger log = LoggerFactory.getLogger(JspAuthorizationExtractor.class);

    private final JspFileParser parser = new JspFileParser();

    /**
     * @param componentFilePath absolute path to the composed JSP view
     * @param pageUrl           the page's own route (used as {@link SecurityRule#urlPattern()}
     *                          so a reader can tell which page a fragment rule belongs to)
     * @return one rule per {@code <sec:authorize>} block found, in document order
     */
    public List<SecurityRule> extract(String componentFilePath, String pageUrl) {
        JspPage page = JspPages.parseOrNull(parser, componentFilePath, log);
        if (page == null) return List.of();

        List<SecurityRule> rules = new ArrayList<>();
        for (Element el : page.document().getAllElements()) {
            // Matched by literal tag name, not a CSS selector: HTML parsing (unlike XML) has no
            // real namespace concept, so jsoup keeps "sec:authorize" as one opaque tag name —
            // a namespace-syntax selector isn't guaranteed to match it.
            if (!"sec:authorize".equalsIgnoreCase(el.tagName())) continue;
            String access = el.attr("access");
            if (access.isBlank()) continue;

            PreAuthorizeExpressionParser.Result parsed = PreAuthorizeExpressionParser.parse(access);
            String source = componentFilePath + " (<sec:authorize access=\"" + access + "\">)";

            rules.add(new SecurityRule(
                    pageUrl,
                    null,
                    parsed.roles(),
                    SecurityRule.RuleStrategy.UI_FRAGMENT,
                    source,
                    parsed.fullyParsed() ? null : access));
        }
        return rules;
    }
}
