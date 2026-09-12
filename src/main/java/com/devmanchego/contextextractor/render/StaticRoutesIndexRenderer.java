package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.model.EndpointInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Renders the per-element index + detail documents for SPA / static routes — endpoints
 * served by {@code @Controller} (view-forwarding) classes rather than REST. Mirrors
 * {@code MarkdownRenderer}'s "5. SPA / STATIC ROUTES" section, split so an AI assistant
 * can load a single route instead of the whole document.
 */
public final class StaticRoutesIndexRenderer {

    /** Stable per-route slug, shared between the category index and its detail file name. */
    public String routeSlug(EndpointInfo route) {
        String raw = route.getHttpVerb() + "-" + route.getPathTemplate() + "-" + route.getControllerName();
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * Renders {@code index-spec-static-routes.md}: a listing of every SPA / static route
     * linking to its own detail document under {@code detailed_static_routes/<slug>.md}.
     */
    public String renderCategoryIndex(List<EndpointInfo> staticRoutes, Path rootPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SPA / Static Routes Index\n\n");
        sb.append("*These endpoints are served by `@Controller` (view-returning) classes — ")
          .append("typically SPA index.html forwards. They are intentionally excluded from ")
          .append("API flow matching and unmatched-endpoint warnings.*\n\n");

        if (staticRoutes.isEmpty()) {
            sb.append("*No SPA / static-route controllers detected.*\n");
            return sb.toString();
        }

        for (EndpointInfo route : staticRoutes) {
            sb.append("- [`").append(route.getHttpVerb()).append(" ").append(route.getPathTemplate())
              .append("`](./detailed_static_routes/").append(routeSlug(route)).append(".md)")
              .append(" — `").append(route.getControllerName()).append("`\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** Renders one static route as a standalone document for {@code detailed_static_routes/<slug>.md}. */
    public String renderRouteDetail(EndpointInfo route, Path rootPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# STATIC_ROUTE: ").append(route.getHttpVerb()).append(" ")
          .append(route.getPathTemplate()).append("\n\n");

        sb.append("**Controller:** `").append(route.getControllerClass()).append("`  \n");
        sb.append("**Method:** `").append(route.getMethodName()).append("`  \n");
        sb.append("**Source:** ").append(rel(route.getSourceFile(), rootPath)).append("  \n");
        sb.append("**Framework:** ").append(route.getFramework()).append("\n\n");
        sb.append("*Excluded from API flow matching and unmatched-endpoint warnings — ")
          .append("this is a view-forwarding route, not a REST endpoint.*\n");
        return sb.toString();
    }

    private String rel(String absolutePath, Path rootPath) {
        if (absolutePath == null || absolutePath.isBlank()) return "";
        if (rootPath == null) return absolutePath;
        try {
            Path p = Path.of(absolutePath).toAbsolutePath().normalize();
            return rootPath.relativize(p).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return absolutePath;
        }
    }
}
