package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts menu labels and UI-tab labels from Angular component templates.
 *
 * Neither Strategy A (ts-morph) nor Strategy B (ANTLR) parse HTML templates —
 * ts-morph only understands TypeScript AST, and Angular templates are a
 * separate template language. This scanner works directly on template text
 * (inline or resolved via {@code templateUrl}) with regex, independent of
 * which strategy produced the {@link ComponentInfo} list.
 *
 * Two signals are extracted:
 * <ul>
 *   <li><b>Menu labels</b> — {@code routerLink} attributes paired with the nearest
 *       following text node, used to fill in functional names for routes whose
 *       {@code data.title} was not set.</li>
 *   <li><b>UI tabs</b> — {@code <mat-tab label="...">}, {@code <p-tabPanel header="...">},
 *       {@code <ngb-tab title="...">}: tabs implemented as UI widgets rather than
 *       child routes, so they never appear in the route tree.</li>
 * </ul>
 *
 * Known limitation: routerLink targets built from array literals with dynamic
 * segments (e.g. {@code ['/locks', lock.name]}) only match static path prefixes;
 * templates using structural directives to build the label text are not resolved.
 */
public final class TemplateLabelExtractor {

    private static final Pattern ROUTER_LINK = Pattern.compile("(?:\\[routerLink]|routerLink)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TEXT_AFTER = Pattern.compile(">\\s*([^<>{}\\n]{2,80})\\s*<");
    private static final Pattern TAB_LABEL = Pattern.compile(
            "<(?:mat-tab|p-tabPanel|ngb-tab)\\b[^>]*?\\b(?:label|header|title)\\s*=\\s*\"([^\"]+)\"");

    private static final int LOOKAHEAD_CHARS = 300;

    public record Result(List<ComponentInfo> components, Map<String, String> menuLabels) {}

    public Result scan(List<ComponentInfo> components) {
        List<ComponentInfo> enriched = new ArrayList<>();
        Map<String, String> menuLabels = new LinkedHashMap<>();

        for (ComponentInfo c : components) {
            String templateText = TemplateResolver.resolveTemplateText(c.getFilePath());
            if (templateText == null) {
                enriched.add(c);
                continue;
            }

            List<String> tabs = extractTabs(templateText);
            extractMenuLinks(templateText, menuLabels);

            enriched.add(tabs.isEmpty() ? c
                    : new ComponentInfo(c.getClassName(), c.getFilePath(), c.getSelector(),
                            c.getInjectedServices(), tabs));
        }
        return new Result(enriched, menuLabels);
    }

    // -----------------------------------------------------------------------
    // Extraction
    // -----------------------------------------------------------------------

    private List<String> extractTabs(String templateText) {
        List<String> tabs = new ArrayList<>();
        Matcher m = TAB_LABEL.matcher(templateText);
        while (m.find()) tabs.add(m.group(1));
        return tabs;
    }

    private void extractMenuLinks(String templateText, Map<String, String> menuLabels) {
        Matcher linkMatch = ROUTER_LINK.matcher(templateText);
        while (linkMatch.find()) {
            String normalized = normalizePath(linkMatch.group(1));
            if (normalized.isEmpty() || menuLabels.containsKey(normalized)) continue;

            int regionEnd = Math.min(templateText.length(), linkMatch.end() + LOOKAHEAD_CHARS);
            Matcher textMatch = TEXT_AFTER.matcher(templateText);
            textMatch.region(linkMatch.end(), regionEnd);
            if (textMatch.find()) {
                String label = textMatch.group(1).trim();
                if (!label.isEmpty()) {
                    menuLabels.put(normalized, label);
                }
            }
        }
    }

    /**
     * Normalizes a routerLink target to the same "segment/segment" form used by
     * {@link com.devmanchego.contextextractor.angular.model.RouteNode} path accumulation.
     * Array-literal targets keep only literal string segments — dynamic expressions
     * (e.g. {@code lock.name}) are dropped since they cannot be matched statically.
     */
    private String normalizePath(String raw) {
        String s = raw.trim();
        if (s.startsWith("[")) {
            String inner = s.replaceAll("[\\[\\]]", "");
            StringBuilder sb = new StringBuilder();
            for (String part : inner.split(",")) {
                String seg = part.trim().replaceAll("^['\"]|['\"]$", "");
                if (seg.isEmpty() || !seg.matches("^['\"]?[\\w-]+['\"]?$") && !part.trim().matches("^['\"].*['\"]$")) {
                    continue; // skip dynamic expressions (identifiers without quotes)
                }
                if (sb.length() > 0) sb.append('/');
                sb.append(seg);
            }
            s = sb.toString();
        } else {
            s = s.replaceAll("^['\"]|['\"]$", "");
        }
        return s.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
