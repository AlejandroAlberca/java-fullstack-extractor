package com.devmanchego.contextextractor.frontend;

import com.devmanchego.contextextractor.angular.jvmparser.AntlrAngularParser;
import com.devmanchego.contextextractor.angular.jvmparser.JvmRouteExtractor;
import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.nodebridge.NodeBridge;
import com.devmanchego.contextextractor.angular.resolve.RouteTitleResolver;
import com.devmanchego.contextextractor.angular.template.TemplateLabelExtractor;
import com.devmanchego.contextextractor.config.NodePathResolver;
import com.devmanchego.contextextractor.jsp.JspProjectAnalyzer;
import com.github.javaparser.ast.CompilationUnit;
import com.devmanchego.contextextractor.react.ReactJvmExtractor;
import com.devmanchego.contextextractor.vue.VueJvmExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Unified entry point for frontend analysis.
 *
 * Detects (or accepts an override of) the frontend framework, then runs
 * Strategy A (Node.js ts-morph) with a fallback to Strategy B (JVM extractor).
 *
 * Always returns an {@link AngularProject} — the shared intermediate model.
 */
public final class FrontendAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FrontendAnalyzer.class);

    private final Path frontendProjectPath;
    private final Optional<FrontendFramework> frameworkOverride;
    private final List<CompilationUnit> backendCompilationUnits;

    public FrontendAnalyzer(Path frontendProjectPath, Optional<FrontendFramework> frameworkOverride) {
        this(frontendProjectPath, frameworkOverride, List.of());
    }

    /**
     * @param backendCompilationUnits the backend module's parsed sources, when available — consumed
     *                                only by {@link FrontendFramework#JSP_JQUERY} (Phase 07: exact
     *                                Spring MVC view resolution); every other framework ignores it.
     */
    public FrontendAnalyzer(Path frontendProjectPath, Optional<FrontendFramework> frameworkOverride,
                            List<CompilationUnit> backendCompilationUnits) {
        this.frontendProjectPath = frontendProjectPath;
        this.frameworkOverride   = frameworkOverride;
        this.backendCompilationUnits = backendCompilationUnits;
    }

    public AngularProject analyze() {
        FrontendFramework framework = frameworkOverride.orElseGet(
                () -> FrontendDetector.detect(frontendProjectPath));
        log.info("Frontend framework: {}", framework);

        Optional<String> nodePath = new NodePathResolver().resolve();

        AngularProject result = null;

        // Strategy A — Node.js ts-morph. Deliberately skipped for JSP + jQuery: ts-morph is a
        // TypeScript-project analyser, and this framework has no TypeScript project for it to
        // load — attempting it would only spend the subprocess-spawn and timeout cost on a
        // failure Strategy B was always going to handle anyway.
        if (nodePath.isPresent() && framework != FrontendFramework.JSP_JQUERY) {
            log.info("Trying Strategy A (Node.js ts-morph) with: {}", nodePath.get());
            try {
                result = new NodeBridge(nodePath.get()).analyze(frontendProjectPath).withFramework(framework);
                log.info("Strategy A succeeded. Detected frontend framework: {} (Strategy A — Node.js ts-morph)", framework);
            } catch (IOException e) {
                log.warn("Strategy A failed ({}). Falling back to Strategy B.", e.getMessage());
                // NodeBridge may interrupt the thread when killing the child process.
                // Clear the flag now — otherwise every NIO read in Strategy B throws
                // ClosedByInterruptException.
                Thread.interrupted();
            }
        } else if (framework == FrontendFramework.JSP_JQUERY) {
            log.info("Strategy A does not apply to {} — using Strategy B (JVM parser) directly.", framework);
        } else {
            log.info("Node.js not available. Using Strategy B (JVM parser) for {}.", framework);
        }

        // Strategy B — JVM extractor (framework-specific)
        if (result == null) {
            result = runStrategyB(framework);
        }

        // Route-tree fallback: when the chosen strategy produced no routes for an
        // Angular app, run the JVM route extractor (text-level, no type resolution).
        if (framework == FrontendFramework.ANGULAR && result.getRoutes().isEmpty()) {
            try {
                List<RouteNode> routes = new JvmRouteExtractor(frontendProjectPath).extract();
                if (!routes.isEmpty()) {
                    result = result.withRoutes(routes);
                }
            } catch (IOException e) {
                log.warn("Route extraction failed: {} — sections output will be empty.", e.getMessage());
            }
        }

        // File-based route extraction for Next.js / Nuxt: derives route tree from directory
        // structure. Runs regardless of Strategy A/B, emitting both RouteNode tree and
        // minimal ComponentInfo for route pages (Variant B).
        if ((framework == FrontendFramework.NEXTJS || framework == FrontendFramework.NUXT)
                && result.getRoutes().isEmpty()) {
            try {
                FileBasedRouteExtractor extractor = new FileBasedRouteExtractor(frontendProjectPath, framework);
                List<RouteNode> routes = extractor.extract();
                if (!routes.isEmpty()) {
                    result = result.withRoutes(routes);
                    // Variant B: merge minimal ComponentInfo for each route page
                    try {
                        Map<String, ComponentInfo> routeComponents = extractor.extractComponentInfoByName();
                        if (!routeComponents.isEmpty()) {
                            List<ComponentInfo> merged = new ArrayList<>(result.getComponents());
                            for (ComponentInfo comp : routeComponents.values()) {
                                // Avoid duplicates by name
                                if (merged.stream().noneMatch(c -> c.getClassName().equals(comp.getClassName()))) {
                                    merged.add(comp);
                                }
                            }
                            result = result.withComponents(merged);
                        }
                    } catch (IOException e) {
                        log.warn("ComponentInfo extraction failed: {}", e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("File-based route extraction failed for {}: {}", framework, e.getMessage());
            }
        }

        // Level 3b: Layout chain (Next.js App Router only). Each page is annotated with the
        // layout.tsx files that wrap it, root-first — derived from the same directory walk
        // as the route tree, so it runs independently of whether routes were already present.
        if (framework == FrontendFramework.NEXTJS) {
            try {
                Map<String, List<String>> layoutsByPage =
                        new FileBasedRouteExtractor(frontendProjectPath, framework).extractLayoutChains();
                if (!layoutsByPage.isEmpty()) {
                    result = result.withLayoutsByPage(layoutsByPage);
                }
            } catch (IOException e) {
                log.warn("Layout chain extraction failed: {}", e.getMessage());
            }
        }

        // Level 3a: Static redirects from next.config.{js,ts,mjs} redirects() / nuxt.config
        // routeRules. Appended as top-level RouteNodes with redirectTo populated — RouteNode
        // already models this (used by Angular's redirectTo today), so no model change needed.
        if (framework == FrontendFramework.NEXTJS || framework == FrontendFramework.NUXT) {
            try {
                List<RouteNode> redirects = new ConfigRedirectExtractor(frontendProjectPath, framework).extract();
                if (!redirects.isEmpty()) {
                    List<RouteNode> merged = new ArrayList<>(result.getRoutes());
                    merged.addAll(redirects);
                    result = result.withRoutes(merged);
                }
            } catch (IOException e) {
                log.warn("Config redirect extraction failed for {}: {}", framework, e.getMessage());
            }
        }

        // Level 2: Wire imports from page components to services (Next.js / Nuxt / React / Vue).
        // Populates ComponentInfo.injectedServices so that the Page→Flow edge and
        // API Dependencies in FrontendPagesRenderer work automatically.
        if (!result.getComponents().isEmpty() && !result.getServices().isEmpty()) {
            try {
                List<ComponentInfo> wired = new FrontendImportGraphExtractor(frontendProjectPath, result.getServices())
                        .wireServices(result.getComponents(), result.getServices());
                result = result.withComponents(wired);
                log.info("Level 2: Wired {} component(s) to {} service(s).",
                        wired.size(), result.getServices().size());
            } catch (Exception e) {
                log.warn("Level 2 import wiring failed: {} — pages will render without API dependencies.", e.getMessage());
            }
        }

        // Menu-label / UI-tab extraction: scans component templates (inline or via
        // templateUrl) for routerLink targets and mat-tab/p-tabPanel/ngb-tab labels.
        // Runs regardless of which strategy produced the components, since neither
        // ts-morph nor the ANTLR parser understands Angular template syntax.
        if (framework == FrontendFramework.ANGULAR && !result.getComponents().isEmpty()) {
            TemplateLabelExtractor.Result scan = new TemplateLabelExtractor().scan(result.getComponents());
            result = result.withComponents(scan.components());
            if (!scan.menuLabels().isEmpty() && !result.getRoutes().isEmpty()) {
                result = result.withRoutes(RouteTitleResolver.applyLabels(result.getRoutes(), scan.menuLabels()));
            }
        }

        // Record how many frontend source files exist on disk, independent of what any
        // strategy above managed to understand. This is what lets a fully empty result be
        // told apart from "no frontend project here" — see AngularProject.isUninterpreted().
        int fileCount = countSourceFiles(frontendProjectPath);
        result = result.withScannedSourceFileCount(fileCount);
        if (result.isUninterpreted()) {
            log.warn("Frontend analysis produced an empty model (no components, services, "
                    + "routes, or data models) despite {} frontend source file(s) found under {}. "
                    + "This parser did not recognise the project's structure — treat every "
                    + "document section derived from it as NOT ANALYSED, not as confirmation "
                    + "that the frontend makes no HTTP calls.",
                    fileCount, frontendProjectPath);
        }
        return result;
    }

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".ts", ".tsx", ".js", ".jsx", ".vue");
    private static final Set<String> EXCLUDED_DIR_NAMES =
            Set.of("node_modules", "dist", "build", ".git", ".angular", ".next", ".nuxt");

    /**
     * Counts frontend source files under {@code root}, excluding build output and dependency
     * directories. Deliberately independent of every extraction strategy above — it answers
     * "was there a non-trivial frontend project here at all", not "what did we understand of it".
     */
    private static int countSourceFiles(Path root) {
        if (!Files.isDirectory(root)) return 0;
        try (var stream = Files.walk(root)) {
            return (int) stream
                    .filter(p -> {
                        for (Path segment : root.relativize(p)) {
                            if (EXCLUDED_DIR_NAMES.contains(segment.toString())) return false;
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
                    .filter(p -> SOURCE_EXTENSIONS.stream().anyMatch(ext -> p.toString().endsWith(ext)))
                    .count();
        } catch (IOException e) {
            log.debug("Could not walk frontend project tree at {} to count source files: {}",
                    root, e.getMessage());
            return 0;
        }
    }

    private AngularProject runStrategyB(FrontendFramework framework) {
        try {
            AngularProject result = switch (framework) {
                case ANGULAR -> new AntlrAngularParser(frontendProjectPath).parse();
                case REACT, NEXTJS -> new ReactJvmExtractor(frontendProjectPath).extract();
                case VUE2, VUE3, NUXT -> new VueJvmExtractor(frontendProjectPath).extract();
                // Route/sitemap reconstruction: the page model every later JSP phase keys off.
                case JSP_JQUERY -> new JspProjectAnalyzer().analyze(frontendProjectPath, backendCompilationUnits);
                case UNKNOWN -> bestEffortUnknown();
            };
            log.info("Detected frontend framework: {} (Strategy B — JVM parser)", framework);
            return result.withFramework(framework);
        } catch (IOException e) {
            log.error("Strategy B failed for {}: {}. Returning empty model.", framework, e.getMessage());
            return emptyProject();
        }
    }

    /**
     * For UNKNOWN framework, try Angular ANTLR first (most structured), then React as fallback.
     * Merges non-empty results, preferring the one with more data.
     */
    private AngularProject bestEffortUnknown() throws IOException {
        try {
            AngularProject angular = new AntlrAngularParser(frontendProjectPath).parse();
            if (!angular.getServices().isEmpty() || !angular.getComponents().isEmpty()) return angular;
        } catch (IOException ignored) {}
        return new ReactJvmExtractor(frontendProjectPath).extract();
    }

    private static AngularProject emptyProject() {
        return new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), List.of(), List.of());
    }
}
