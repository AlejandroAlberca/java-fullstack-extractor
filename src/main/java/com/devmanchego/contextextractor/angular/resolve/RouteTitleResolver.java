package com.devmanchego.contextextractor.angular.resolve;

import com.devmanchego.contextextractor.angular.model.RouteNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fills in missing {@link RouteNode} titles using the menu-label map produced by
 * {@link com.devmanchego.contextextractor.angular.template.TemplateLabelExtractor}.
 *
 * Routes whose title is already set (from {@code data.title}/{@code data.breadcrumb}
 * or the Angular 14+ route-level {@code title} property) are left untouched.
 */
public final class RouteTitleResolver {

    private RouteTitleResolver() {}

    public static List<RouteNode> applyLabels(List<RouteNode> routes, Map<String, String> menuLabels) {
        return applyLabels(routes, menuLabels, "");
    }

    private static List<RouteNode> applyLabels(List<RouteNode> routes, Map<String, String> menuLabels,
                                               String parentPath) {
        List<RouteNode> result = new ArrayList<>();
        for (RouteNode route : routes) {
            String fullPath = normalize(join(parentPath, route.getPath()));

            RouteNode updated = route;
            if (updated.getTitle() == null) {
                String label = menuLabels.get(fullPath);
                if (label != null) updated = updated.withTitle(label);
            }

            List<RouteNode> newChildren = applyLabels(updated.getChildren(), menuLabels, fullPath);
            updated = updated.withChildren(newChildren);

            result.add(updated);
        }
        return result;
    }

    private static String join(String parent, String segment) {
        if (segment == null || segment.isEmpty()) return parent;
        return parent.isEmpty() ? segment : parent + "/" + segment;
    }

    private static String normalize(String path) {
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
