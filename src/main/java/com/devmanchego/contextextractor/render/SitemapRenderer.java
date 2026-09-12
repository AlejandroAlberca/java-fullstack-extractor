package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.model.RouteOrigin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders spec-sitemap.md: a hierarchical tree view of the application's routes,
 * components, and UI tabs. Duplicated component names are disambiguated with
 * their relative TypeScript file path.
 */
public final class SitemapRenderer {

    public static Path sitemapOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-sitemap.md");
    }

    private static final String DEFAULT_PAGES_DIR_PREFIX = "docs/frontend-pages";

    public String render(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName) {
        return renderInternal(routes, componentsByName, null, false, DEFAULT_PAGES_DIR_PREFIX);
    }

    /**
     * @param unroutedFallback when true, {@code routes} was synthesized from components
     *        (see {@link UnroutedPagesFallback}); an explanatory note is emitted.
     */
    public String render(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                         boolean unroutedFallback) {
        return renderInternal(routes, componentsByName, null, unroutedFallback, DEFAULT_PAGES_DIR_PREFIX);
    }

    /**
     * Same tree as {@link #render}, but each page's component reference is a Markdown
     * link to its document inside {@code docs/frontend-pages/} — used by
     * {@link FrontendPagesZipExporter} for {@code index-spec-sitemap.md}, where links
     * must resolve correctly once the ZIP has been extracted (relative paths from the
     * ZIP root).
     */
    public String renderWithLinks(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                  Map<RouteNode, String> slugs) {
        return renderInternal(routes, componentsByName, slugs, false, DEFAULT_PAGES_DIR_PREFIX);
    }

    /** Link variant with the unrouted-fallback note (used by the ZIP index). */
    public String renderWithLinks(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                  Map<RouteNode, String> slugs, boolean unroutedFallback) {
        return renderInternal(routes, componentsByName, slugs, unroutedFallback, DEFAULT_PAGES_DIR_PREFIX);
    }

    /**
     * Link variant with a caller-supplied page-docs directory prefix — used when writing
     * the plain (unzipped) {@code indexed_specs/} tree, where pages live under
     * {@code frontend-pages/} instead of {@code docs/frontend-pages/}.
     */
    public String renderWithLinks(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                  Map<RouteNode, String> slugs, boolean unroutedFallback,
                                  String pagesDirPrefix) {
        return renderInternal(routes, componentsByName, slugs, unroutedFallback, pagesDirPrefix);
    }

    private String renderInternal(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                  Map<RouteNode, String> slugs, boolean unroutedFallback,
                                  String pagesDirPrefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Site Map\n\n");

        if (routes.isEmpty()) {
            sb.append("*No pages found.*\n");
            return sb.toString();
        }

        if (unroutedFallback) {
            sb.append(UnroutedPagesFallback.NOTE).append("\n\n");
        }

        // Detect duplicate component names to disambiguate later
        Map<String, Integer> componentCounts = countComponents(routes, componentsByName);

        renderRoutes(sb, routes, componentsByName, componentCounts, slugs, 0, pagesDirPrefix);
        renderStateTransitions(sb, routes);

        return sb.toString();
    }

    private void renderRoutes(StringBuilder sb, List<RouteNode> routes,
                             Map<String, ComponentInfo> componentsByName,
                             Map<String, Integer> componentCounts,
                             Map<RouteNode, String> slugs,
                             int depth, String pagesDirPrefix) {
        for (RouteNode route : routes) {
            if (route.getRedirectTo() != null) {
                renderRedirect(sb, route, depth);
                continue; // Redirects have no children to descend into
            }
            if (!route.isPage()) {
                renderGroup(sb, route, componentsByName, componentCounts, slugs, depth, pagesDirPrefix);
                continue;
            }

            renderRoute(sb, route, componentsByName, componentCounts, slugs, depth, pagesDirPrefix);

            // Render child routes
            renderRoutes(sb, route.getChildren(), componentsByName, componentCounts, slugs, depth + 1, pagesDirPrefix);
        }
    }

    /**
     * A node with no page of its own: an Angular grouping/guarded route, or a JSP menu section.
     * Wildcards never render; anything else must still render its children (skipping the node
     * used to drop every page beneath it). A titled or pathed group becomes a heading line its
     * children nest under; an anonymous one is transparent.
     */
    private void renderGroup(StringBuilder sb, RouteNode group,
                             Map<String, ComponentInfo> componentsByName,
                             Map<String, Integer> componentCounts,
                             Map<RouteNode, String> slugs,
                             int depth, String pagesDirPrefix) {
        if ("**".equals(group.getPath()) || group.getChildren().isEmpty()) return;
        String heading = group.getTitle() != null ? "**" + group.getTitle() + "**"
                : !group.getPath().isEmpty() ? "[" + group.getPath() + "]" : null;
        int childDepth = depth;
        if (heading != null) {
            sb.append("  ".repeat(depth)).append("- ").append(heading).append("\n");
            childDepth = depth + 1;
        }
        renderRoutes(sb, group.getChildren(), componentsByName, componentCounts, slugs, childDepth, pagesDirPrefix);
    }

    private void renderRedirect(StringBuilder sb, RouteNode route, int depth) {
        String indent = "  ".repeat(depth);
        String path = route.getPath().isEmpty() ? "/" : route.getPath();
        sb.append(indent).append("- [").append(path).append("] ↪ redirects to `")
                .append(route.getRedirectTo()).append("`\n");
    }

    private void renderRoute(StringBuilder sb, RouteNode route,
                            Map<String, ComponentInfo> componentsByName,
                            Map<String, Integer> componentCounts,
                            Map<RouteNode, String> slugs,
                            int depth, String pagesDirPrefix) {
        String indent = "  ".repeat(depth);
        String path = route.getPath().isEmpty() ? "/" : route.getPath();
        String label = route.getTitle() != null ? route.getTitle() : humanize(route.getComponentName());
        String componentRef = formatComponentReference(route.getComponentName(), componentsByName, componentCounts);

        if (slugs != null && route.getComponentName() != null) {
            String slug = slugs.get(route);
            if (slug != null) componentRef = "[" + componentRef + "](./" + pagesDirPrefix + "/" + slug + ".md)";
        }

        sb.append(indent).append("- [").append(path).append("] ").append(label);
        if (!componentRef.isEmpty()) {
            sb.append(": ").append(componentRef);
        }
        sb.append("\n");
        renderOrigin(sb, route.getOrigin(), depth + 1);

        // Render UI tabs (if any)
        ComponentInfo comp = componentsByName.get(route.getComponentName());
        if (comp != null && !comp.getUiTabs().isEmpty()) {
            String tabIndent = "  ".repeat(depth + 1);
            for (String tab : comp.getUiTabs()) {
                sb.append(tabIndent).append("- [tab] ").append(tab).append("\n");
            }
        }
    }

    /** Provenance of a reconstructed (JSP) route — absent for routes read from a declaration. */
    private void renderOrigin(StringBuilder sb, RouteOrigin origin, int depth) {
        if (origin == null) return;
        String indent = "  ".repeat(depth);
        if (!origin.requiredPermissions().isEmpty()) {
            sb.append(indent).append("- requires: ").append(String.join(", ", origin.requiredPermissions()))
              .append(" *(navigation gate, UI-fragment level)*\n");
        }
        if (!origin.triggeringStates().isEmpty()) {
            sb.append(indent).append("- reached from states: `")
              .append(String.join("`, `", origin.triggeringStates())).append("`\n");
        }
        if (origin.viewMappingNote() != null) {
            sb.append(indent).append("- view: ").append(origin.viewMappingNote()).append("\n");
        }
        if (!origin.sources().isEmpty()) {
            sb.append(indent).append("- source: ").append(String.join("; ", origin.sources())).append("\n");
        }
    }

    /**
     * The client-side state transition graph (JSP state dispatch): which workflow states send the
     * user to which page. Emitted only when some route carries triggering states.
     */
    private void renderStateTransitions(StringBuilder sb, List<RouteNode> routes) {
        List<RouteNode> targets = new ArrayList<>();
        collectDispatchTargets(routes, targets);
        if (targets.isEmpty()) return;
        sb.append("\n## State Transitions (client-side dispatch)\n\n")
          .append("Workflow states whose client-side dispatch function navigates to each page.\n\n")
          .append("| Destination | Page | Triggering states |\n")
          .append("|-------------|------|-------------------|\n");
        for (RouteNode r : targets) {
            String label = r.getTitle() != null ? r.getTitle() : humanize(r.getComponentName());
            sb.append("| `").append(r.getPath()).append("` | ").append(label.replace("|", "\\|"))
              .append(" | `").append(String.join("`, `", r.getOrigin().triggeringStates())).append("` |\n");
        }
    }

    private void collectDispatchTargets(List<RouteNode> routes, List<RouteNode> acc) {
        for (RouteNode r : routes) {
            if (r.getOrigin() != null && !r.getOrigin().triggeringStates().isEmpty()) acc.add(r);
            collectDispatchTargets(r.getChildren(), acc);
        }
    }

    private String formatComponentReference(String componentName,
                                           Map<String, ComponentInfo> componentsByName,
                                           Map<String, Integer> componentCounts) {
        if (componentName == null) return "";

        // Check if there are duplicates
        int count = componentCounts.getOrDefault(componentName, 0);
        if (count > 1) {
            // Add relative path to disambiguate
            ComponentInfo comp = componentsByName.get(componentName);
            if (comp != null && comp.getFilePath() != null) {
                String relativePath = extractRelativePath(comp.getFilePath());
                return componentName + " (" + relativePath + ")";
            }
        }

        return componentName;
    }

    /**
     * Extracts relative path from an absolute file path, e.g.:
     * /path/to/src/app/locks/lock-detail.component.ts → app/locks/lock-detail.component.ts
     */
    private String extractRelativePath(String absolutePath) {
        if (absolutePath == null) return "";
        Path p = Path.of(absolutePath);
        // Find 'src' in the path and take everything after it
        String[] parts = p.getNameCount() > 0
                ? p.subpath(0, p.getNameCount()).toString().split("[/\\\\]")
                : new String[0];

        StringBuilder sb = new StringBuilder();
        boolean foundSrc = false;
        for (String part : parts) {
            if ("src".equals(part)) {
                foundSrc = true;
                continue;
            }
            if (foundSrc) {
                if (sb.length() > 0) sb.append("/");
                sb.append(part);
            }
        }

        if (sb.length() == 0) {
            // Fallback: just return the filename
            return p.getFileName().toString();
        }
        return sb.toString();
    }

    private Map<String, Integer> countComponents(List<RouteNode> routes,
                                                 Map<String, ComponentInfo> componentsByName) {
        Map<String, Integer> counts = new HashMap<>();
        collectComponentCounts(routes, counts);
        return counts;
    }

    private void collectComponentCounts(List<RouteNode> routes, Map<String, Integer> counts) {
        for (RouteNode route : routes) {
            if (route.getComponentName() != null) {
                counts.merge(route.getComponentName(), 1, Integer::sum);
            }
            collectComponentCounts(route.getChildren(), counts);
        }
    }

    /** "LockDetailComponent" → "Lock Detail" */
    private static String humanize(String componentName) {
        if (componentName == null) return "";
        String base = componentName.replaceAll("(Component|Page|View)$", "");
        return base.replaceAll("([a-z])([A-Z])", "$1 $2");
    }
}
