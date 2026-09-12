package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import com.devmanchego.contextextractor.matching.MatchedFlow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders the APPLICATION SECTIONS document: the functional structure of the
 * frontend derived from its route tree, with each page cross-referenced to the
 * API flows it triggers (page → injected services → matched flows).
 */
public final class SectionsRenderer {

    /** Returns the sections file path derived from the main output file: {@code *-sections.md}. */
    public static Path sectionsOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-sections.md");
    }

    public String render(AngularProject project, EndpointMatcher.MatchResult matchResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("# APPLICATION SECTIONS\n\n");
        sb.append("*Functional structure of the ")
          .append(project.getFramework().displayName())
          .append(" application derived from its routing configuration. ")
          .append("Each page lists the API flows it can trigger.*\n\n");

        if (project.getRoutes().isEmpty()) {
            if (project.isUninterpreted()) {
                sb.append("⚠️ **Not analysed.** Found ").append(project.getScannedSourceFileCount())
                  .append(" frontend source file(s) under the project root, but extracted no ")
                  .append("components, services, routes, or data models from them — this parser ")
                  .append("did not recognise the project's structure. This is *not* confirmation ")
                  .append("that the application has no sections or pages.\n");
            } else {
                sb.append("*No routing configuration found — the route tree could not be extracted.*\n");
            }
            return sb.toString();
        }

        // component class name → flows triggered through its injected services
        Map<String, ComponentInfo> componentsByName = project.getComponents().stream()
                .collect(Collectors.toMap(ComponentInfo::getClassName, c -> c, (a, b) -> a));

        sb.append("## Sections\n\n");
        int i = 1;
        for (RouteNode route : project.getRoutes()) {
            renderRoute(sb, route, "", String.valueOf(i), 2, componentsByName, matchResult);
            if (isSection(route)) i++;
        }

        renderReusableComponents(sb, project);
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Route tree rendering
    // -----------------------------------------------------------------------

    private void renderRoute(StringBuilder sb, RouteNode route, String parentPath,
                             String numbering, int level,
                             Map<String, ComponentInfo> componentsByName,
                             EndpointMatcher.MatchResult matchResult) {
        String fullPath = joinPath(parentPath, route.getPath());

        if (route.getRedirectTo() != null) {
            sb.append(indent(level - 2)).append("- ↪ `").append(fullPath.isEmpty() ? "/" : fullPath)
              .append("` redirects to `").append(route.getRedirectTo()).append("`\n");
            return;
        }
        if ("**".equals(route.getPath())) {
            return; // wildcard fallback — not a functional section
        }

        String heading = "#".repeat(Math.min(level, 6));
        String name = route.getTitle() != null ? route.getTitle()
                : route.getComponentName() != null ? humanize(route.getComponentName())
                : fullPath.isEmpty() ? "(root)" : fullPath;

        sb.append(heading).append(" ").append(numbering).append(". ").append(name);
        // A titled, pathless group (a JSP menu section) has no URL of its own — printing "/"
        // for it would read as the application root.
        boolean titledGroup = route.getComponentName() == null && route.getTitle() != null && fullPath.isEmpty();
        if (!titledGroup) sb.append(" — `").append(fullPath.isEmpty() ? "/" : fullPath).append("`");
        if (route.isLazy()) sb.append(" *(lazy)*");
        sb.append("\n\n");

        if (route.getComponentName() != null) {
            sb.append("- **Page component:** `").append(route.getComponentName()).append("`\n");
            ComponentInfo component = componentsByName.get(route.getComponentName());
            if (component != null && !component.getUiTabs().isEmpty()) {
                sb.append("- **UI tabs (not routed):** ")
                  .append(String.join(", ", component.getUiTabs())).append("\n");
            }
            renderPageFlows(sb, route.getComponentName(), componentsByName, matchResult);
        }
        sb.append("\n");

        int i = 1;
        for (RouteNode child : route.getChildren()) {
            renderRoute(sb, child, fullPath, numbering + "." + i, level + 1,
                    componentsByName, matchResult);
            if (isSection(child)) i++;
        }
    }

    /** True for routes rendered as numbered sections (not redirects or wildcards). */
    private static boolean isSection(RouteNode route) {
        return route.getRedirectTo() == null && !"**".equals(route.getPath());
    }

    /** page component → injected services → matched flows of those services. */
    private void renderPageFlows(StringBuilder sb, String componentName,
                                 Map<String, ComponentInfo> componentsByName,
                                 EndpointMatcher.MatchResult matchResult) {
        ComponentInfo component = componentsByName.get(componentName);
        if (component == null) return;

        Set<String> injected = new HashSet<>(component.getInjectedServices());
        if (injected.isEmpty()) return;

        List<MatchedFlow> flows = matchResult.flows().stream()
                .filter(f -> injected.contains(f.getAngularService().getClassName()))
                .toList();
        if (flows.isEmpty()) return;

        sb.append("- **API flows:**\n");
        for (MatchedFlow f : flows) {
            sb.append("  - `").append(f.getAngularCall().getHttpVerb())
              .append(" ").append(f.getJavaEndpoint().getPathTemplate())
              .append("` via `").append(f.getAngularService().getClassName())
              .append("#").append(f.getAngularCall().getMethodName()).append("`\n");
        }
    }

    // -----------------------------------------------------------------------
    // Reusable (non-page) components
    // -----------------------------------------------------------------------

    private void renderReusableComponents(StringBuilder sb, AngularProject project) {
        Set<String> pageComponents = new HashSet<>();
        collectPageComponents(project.getRoutes(), pageComponents);

        List<ComponentInfo> reusable = new ArrayList<>();
        for (ComponentInfo c : project.getComponents()) {
            if (!pageComponents.contains(c.getClassName())) reusable.add(c);
        }
        if (reusable.isEmpty()) return;

        sb.append("## Reusable components (not pages)\n\n");
        for (ComponentInfo c : reusable) {
            sb.append("- `").append(c.getClassName()).append("`");
            if (c.getSelector() != null && !c.getSelector().isBlank()) {
                sb.append(" — `<").append(c.getSelector()).append(">`");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private void collectPageComponents(List<RouteNode> routes, Set<String> acc) {
        for (RouteNode r : routes) {
            if (r.getComponentName() != null) acc.add(r.getComponentName());
            collectPageComponents(r.getChildren(), acc);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String joinPath(String parent, String segment) {
        if (segment == null || segment.isEmpty()) return parent;
        if (segment.startsWith("/")) return segment; // already absolute (reconstructed JSP routes)
        return parent + "/" + segment;
    }

    private static String indent(int level) {
        return "  ".repeat(Math.max(0, level));
    }

    /** "LockDetailComponent" → "Lock Detail" */
    private static String humanize(String componentName) {
        String base = componentName.replaceAll("(Component|Page|View)$", "");
        return base.replaceAll("([a-z])([A-Z])", "$1 $2");
    }
}
