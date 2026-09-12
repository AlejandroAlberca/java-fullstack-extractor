package com.devmanchego.contextextractor.angular.model;

import java.util.List;

/**
 * A node in the Angular route tree — the functional-section structure of the app.
 *
 * Top-level nodes correspond to application sections (menu options); child nodes
 * correspond to subsections or tabs implemented as child routes.
 */
public final class RouteNode {

    private final String path;           // route segment, e.g. "locks/:name" ("" for default)
    private final String componentName;  // class name of the routed component, or null
    private final String title;          // functional name from data.title/breadcrumb, or null
    private final String redirectTo;     // target of a redirect route, or null
    private final boolean lazy;          // true when loaded via loadChildren/loadComponent
    private final List<RouteNode> children;
    private final RouteOrigin origin;    // provenance of a reconstructed route, or null

    public RouteNode(String path, String componentName, String title,
                     String redirectTo, boolean lazy, List<RouteNode> children) {
        this(path, componentName, title, redirectTo, lazy, children, null);
    }

    public RouteNode(String path, String componentName, String title,
                     String redirectTo, boolean lazy, List<RouteNode> children, RouteOrigin origin) {
        this.path = path == null ? "" : path;
        this.componentName = componentName;
        this.title = title;
        this.redirectTo = redirectTo;
        this.lazy = lazy;
        this.children = List.copyOf(children);
        this.origin = origin;
    }

    public String getPath()              { return path; }
    public String getComponentName()     { return componentName; }
    public String getTitle()             { return title; }
    public String getRedirectTo()        { return redirectTo; }
    public boolean isLazy()              { return lazy; }
    public List<RouteNode> getChildren() { return children; }
    /** Provenance of a reconstructed route (see {@link RouteOrigin}); null for declared routes. */
    public RouteOrigin getOrigin()       { return origin; }

    /** True for routes that render a page (not redirects or wildcard fallbacks). */
    public boolean isPage() {
        return componentName != null && redirectTo == null && !"**".equals(path);
    }

    /** Returns a copy of this node with a different title, preserving everything else. */
    public RouteNode withTitle(String newTitle) {
        return new RouteNode(path, componentName, newTitle, redirectTo, lazy, children, origin);
    }

    /** Returns a copy of this node with a different children list, preserving everything else. */
    public RouteNode withChildren(List<RouteNode> newChildren) {
        return new RouteNode(path, componentName, title, redirectTo, lazy, newChildren, origin);
    }

    /** Returns a copy of this node with a different path and provenance, preserving everything else. */
    public RouteNode withPathAndOrigin(String newPath, RouteOrigin newOrigin) {
        return new RouteNode(newPath, componentName, title, redirectTo, lazy, children, newOrigin);
    }

    /** Returns a copy of this node with different provenance, preserving everything else. */
    public RouteNode withOrigin(RouteOrigin newOrigin) {
        return new RouteNode(path, componentName, title, redirectTo, lazy, children, newOrigin);
    }
}
