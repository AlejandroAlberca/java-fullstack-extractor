package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns a unique, filesystem-safe kebab-case slug to every page {@link RouteNode}
 * in the route tree, used as the filename for that page's document inside
 * {@code docs/frontend-pages/} in the frontend-pages ZIP export.
 *
 * When the same component class is routed from more than one place in the tree
 * (same name, different component instances), the slug is disambiguated with a
 * hint derived from the component's containing folder; if that still collides,
 * a numeric suffix is appended as a last resort.
 */
public final class PageSlugAssigner {

    private PageSlugAssigner() {}

    public static Map<RouteNode, String> assign(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName) {
        Map<String, Integer> nameCounts = new HashMap<>();
        countNames(routes, nameCounts);

        Map<RouteNode, String> result = new LinkedHashMap<>();
        Set<String> usedSlugs = new HashSet<>();
        assignRecursive(routes, componentsByName, nameCounts, result, usedSlugs);
        return result;
    }

    private static void countNames(List<RouteNode> routes, Map<String, Integer> counts) {
        for (RouteNode r : routes) {
            if (r.isPage()) counts.merge(r.getComponentName(), 1, Integer::sum);
            countNames(r.getChildren(), counts);
        }
    }

    private static void assignRecursive(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                        Map<String, Integer> nameCounts, Map<RouteNode, String> result,
                                        Set<String> usedSlugs) {
        for (RouteNode r : routes) {
            if (r.isPage()) {
                String base = kebab(r.getComponentName());
                String slug = base;

                boolean duplicateName = nameCounts.getOrDefault(r.getComponentName(), 0) > 1;
                if (duplicateName) {
                    ComponentInfo comp = componentsByName.get(r.getComponentName());
                    String hint = comp != null ? folderHint(comp.getFilePath()) : null;
                    if (hint != null && !hint.isBlank() && !hint.equals(base)) {
                        slug = base + "-" + hint;
                    }
                }

                String candidate = slug;
                int suffix = 2;
                while (usedSlugs.contains(candidate)) {
                    candidate = slug + "-" + suffix;
                    suffix++;
                }
                usedSlugs.add(candidate);
                result.put(r, candidate);
            }
            assignRecursive(r.getChildren(), componentsByName, nameCounts, result, usedSlugs);
        }
    }

    /** Derives a disambiguation hint from the component's immediate parent folder name. */
    private static String folderHint(String filePath) {
        if (filePath == null) return null;
        Path parent = Path.of(filePath).getParent();
        if (parent == null || parent.getFileName() == null) return null;
        return kebabSegment(parent.getFileName().toString());
    }

    /** "LockDetailComponent" → "lock-detail" */
    static String kebab(String componentName) {
        if (componentName == null) return "page";
        String base = componentName.replaceAll("(Component|Page|View)$", "");
        String withHyphens = base.replaceAll("([a-z0-9])([A-Z])", "$1-$2");
        return kebabSegment(withHyphens);
    }

    private static String kebabSegment(String s) {
        String cleaned = s.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "page" : cleaned;
    }
}
