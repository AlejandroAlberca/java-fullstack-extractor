package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Best-effort fallback used when an Angular project defines no route tree
 * (no {@code Routes} array, {@code RouterModule.forRoot/forChild}, or
 * {@code provideRouter([...])}). Without routing, the sitemap and frontend-pages
 * documents would otherwise be empty even though the app has real components.
 *
 * <p>This synthesizes a flat list of {@link RouteNode}s — one per component — each
 * marked with the sentinel path {@link #UNROUTED_PATH} so renderers can display an
 * {@code [unrouted]} marker and an explanatory note. The degraded nature of this
 * listing (components are not confirmed to be navigable pages) is surfaced to the
 * reader, never presented as a genuine route tree.
 */
public final class UnroutedPagesFallback {

    /** Sentinel path segment marking a synthesized, non-routed page. */
    public static final String UNROUTED_PATH = "unrouted";

    /** Note emitted at the top of documents rendered from a synthesized fallback tree. */
    public static final String NOTE = "*No route tree found — listing components as unrouted pages.*";

    private UnroutedPagesFallback() {
    }

    /**
     * Builds a flat, alphabetically ordered list of unrouted page nodes from the given
     * components. Each node carries no title (the renderer humanizes the component name)
     * and no children.
     */
    public static List<RouteNode> synthesize(Collection<ComponentInfo> components) {
        return components.stream()
                .filter(c -> c.getClassName() != null && !c.getClassName().isEmpty())
                .sorted(Comparator.comparing(ComponentInfo::getClassName))
                .map(c -> new RouteNode(UNROUTED_PATH, c.getClassName(), null, null, false, List.of()))
                .toList();
    }
}
