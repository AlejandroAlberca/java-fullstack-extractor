package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.render.CrossReferenceGraph.Category;
import com.devmanchego.contextextractor.render.CrossReferenceGraph.RelatedLink;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the standardized {@code ## Related} section appended to a per-element detail
 * document, from the {@link RelatedLink}s resolved by {@link CrossReferenceGraph}.
 *
 * <p>Links are split into <em>Links out</em> (outgoing) and <em>Referenced by</em>
 * (incoming backlinks), grouped by target category, and truncated per group so a heavily
 * referenced element (e.g. a central table) does not accumulate dozens of lines.
 * Non-navigable targets render as plain text with {@code (not indexed)}; heuristic edges
 * are marked {@code (inferred)}.
 */
public final class RelatedLinksRenderer {

    private static final int MAX_PER_GROUP = 8;

    /**
     * @param links          resolved related links for this element
     * @param originRelToBase this document's own path, relative to the base output dir
     *                        (e.g. {@code indexed_specs/detailed_tables/employees.md}),
     *                        used to relativize each target
     * @return the {@code ## Related} section, or an empty string when there are no links
     */
    public String render(List<RelatedLink> links, String originRelToBase) {
        if (links == null || links.isEmpty()) return "";

        Path originDir = Path.of(originRelToBase).getParent();

        StringBuilder sb = new StringBuilder();
        sb.append("## Related\n\n");

        List<RelatedLink> outgoing = links.stream().filter(RelatedLink::outgoing).toList();
        List<RelatedLink> incoming = links.stream().filter(l -> !l.outgoing()).toList();

        appendGroup(sb, "Links out", outgoing, originDir);
        appendGroup(sb, "Referenced by", incoming, originDir);

        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String heading, List<RelatedLink> group, Path originDir) {
        if (group.isEmpty()) return;
        sb.append("**").append(heading).append(":**\n");

        Map<Category, List<RelatedLink>> byCategory = new LinkedHashMap<>();
        for (RelatedLink link : group) {
            byCategory.computeIfAbsent(link.target().category(), k -> new java.util.ArrayList<>()).add(link);
        }

        for (Map.Entry<Category, List<RelatedLink>> entry : byCategory.entrySet()) {
            List<RelatedLink> categoryLinks = entry.getValue();
            int shown = Math.min(categoryLinks.size(), MAX_PER_GROUP);
            for (int i = 0; i < shown; i++) {
                RelatedLink link = categoryLinks.get(i);
                sb.append("- ").append(entry.getKey().label()).append(": ")
                  .append(renderTarget(link, originDir))
                  .append(" — ").append(link.relation());
                if (link.inferred()) sb.append(" *(inferred)*");
                sb.append("\n");
            }
            if (categoryLinks.size() > shown) {
                sb.append("- ").append(entry.getKey().label()).append(": (+")
                  .append(categoryLinks.size() - shown).append(" more)\n");
            }
        }
        sb.append("\n");
    }

    private String renderTarget(RelatedLink link, Path originDir) {
        CrossReferenceGraph.Node target = link.target();
        if (!target.navigable()) {
            return "`" + target.label() + "` *(not indexed)*";
        }
        String rel = originDir.relativize(Path.of(target.pathRelToBase())).toString().replace('\\', '/');
        String anchor = target.anchor() != null ? "#" + target.anchor() : "";
        return "[`" + target.label() + "`](" + rel + anchor + ")";
    }
}
