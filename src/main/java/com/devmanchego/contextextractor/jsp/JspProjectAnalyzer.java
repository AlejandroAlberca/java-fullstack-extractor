package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.java.mvc.SpringViewNameResolver;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the frontend model of a JSP + jQuery project: locates the web application root, runs
 * {@link JspRouteReconstructor} (Phase 04 — the route tree and synthetic page components), folds
 * in {@link SpringViewNameResolver}'s backend-declared views when a backend module is available
 * ({@link SpringViewMerge}, Phase 07 — the exact URL-to-view join, superseding Phase 04's
 * naming-convention fallback), and then runs {@link JspBundleExtractor} (Phase 05 — each view's
 * webpack bundle entry, module closure, and reachable HTTP call set) against the now-corrected
 * file paths, packaging the result into the shared {@link AngularProject} model every renderer
 * consumes — and that the later JSP phases (fields, validations, UI-fragment rules) key off
 * through each page's JSP file path.
 */
public final class JspProjectAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JspProjectAnalyzer.class);

    private static final Set<String> EXCLUDED_DIRS = Set.of("node_modules", "dist", "build", ".git", "target");

    public AngularProject analyze(Path frontendProjectPath) {
        return analyze(frontendProjectPath, List.of());
    }

    /** @param backendCompilationUnits the backend module's parsed sources, for Phase 07; empty when unavailable. */
    public AngularProject analyze(Path frontendProjectPath, List<CompilationUnit> backendCompilationUnits) {
        Path webappRoot = findWebappRoot(frontendProjectPath);
        if (webappRoot == null) {
            log.warn("JSP + jQuery: no web application root (a directory containing WEB-INF) with JSP views "
                    + "found under {} — no pages reconstructed.", frontendProjectPath);
            return new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR, FrontendFramework.JSP_JQUERY,
                    List.of(), List.of(), List.of());
        }
        log.info("JSP + jQuery: web application root {}", webappRoot);

        JspRouteReconstructor.Result routeResult = new JspRouteReconstructor().reconstruct(frontendProjectPath, webappRoot);
        routeResult.warnings().forEach(w -> log.warn("JSP route reconstruction: {}", w));

        List<RouteNode> routes = routeResult.routes();
        Map<String, ComponentInfo> componentsByName = routeResult.componentsByName();

        if (!backendCompilationUnits.isEmpty()) {
            SpringViewNameResolver resolver = new SpringViewNameResolver();
            List<SpringViewNameResolver.ViewRoute> viewRoutes = resolver.extract(backendCompilationUnits);
            if (!viewRoutes.isEmpty()) {
                SpringViewNameResolver.ViewResolverConfig config = resolver.resolveConfig(backendCompilationUnits);
                SpringViewMerge.Result merged = SpringViewMerge.merge(routes, componentsByName, webappRoot,
                        viewRoutes, config);
                merged.warnings().forEach(w -> log.warn("Spring view resolution: {}", w));
                routes = merged.routes();
                componentsByName = merged.componentsByName();
            }
        }

        List<String> bundleWarnings = new ArrayList<>();
        JspBundleExtractor.Result bundleResult =
                new JspBundleExtractor().bindApiCalls(componentsByName, frontendProjectPath, webappRoot, bundleWarnings);
        bundleWarnings.forEach(w -> log.warn("JSP bundle binding: {}", w));

        return new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR, FrontendFramework.JSP_JQUERY,
                List.copyOf(bundleResult.componentsByName().values()), bundleResult.services(), List.of(), routes);
    }

    /** Web application root of the first JSP view found (sorted; build output excluded), or null. */
    static Path findWebappRoot(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return null;
        List<Path> jspFiles = new ArrayList<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    boolean excluded = !dir.equals(projectRoot) && dir.getFileName() != null
                            && EXCLUDED_DIRS.contains(dir.getFileName().toString());
                    return excluded ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsp")) jspFiles.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not walk {} for JSP views: {}", projectRoot, e.getMessage());
            return null;
        }
        return jspFiles.stream().sorted()
                .map(JspFileParser::locateWebappRoot)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }
}
