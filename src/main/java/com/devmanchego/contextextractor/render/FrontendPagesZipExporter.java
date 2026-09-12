package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.model.ServiceInfo;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import com.devmanchego.contextextractor.matching.MatchedFlow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates {@code spec-frontend-pages.zip}, containing:
 * <ul>
 *   <li>{@code docs/frontend-pages/<slug>.md} — one document per frontend page
 *       (same content as each page's section in spec-frontend-pages.md).</li>
 *   <li>{@code index-spec-sitemap.md} — same tree as spec-sitemap.md, with each
 *       page linking to its document in {@code docs/frontend-pages/}.</li>
 *   <li>{@code index-spec-frontend-pages.md} — architecture index: sitemap pages
 *       first, then auxiliary (non-routed) components and services.</li>
 * </ul>
 *
 * All links are relative to the ZIP root and resolve correctly once extracted —
 * this matters more than the ZIP being browsable in-place.
 */
public final class FrontendPagesZipExporter {

    private static final String ZIP_PAGES_DIR_PREFIX = "docs/frontend-pages";
    private static final String DIRECTORY_PAGES_DIR_PREFIX = "frontend-pages";

    public static Path zipOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-frontend-pages.zip");
    }

    public int export(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                      List<ServiceInfo> services, EndpointMatcher.MatchResult matchResult,
                      Path outputZip) throws IOException {
        return export(routes, componentsByName, services, matchResult, outputZip, false,
                FrontendFramework.ANGULAR, AngularI18nCatalog.empty());
    }

    public int export(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                      List<ServiceInfo> services, EndpointMatcher.MatchResult matchResult,
                      Path outputZip, boolean unroutedFallback, FrontendFramework framework) throws IOException {
        return export(routes, componentsByName, services, matchResult, outputZip, unroutedFallback,
                framework, AngularI18nCatalog.empty());
    }

    /**
     * @param unroutedFallback when true, {@code routes} was synthesized from components
     *        (see {@link UnroutedPagesFallback}); the sitemap and index emit an
     *        explanatory note.
     * @param framework        the detected frontend framework, used to select
     *                         framework-specific extractors
     * @param i18nCatalog      i18n catalog used to resolve form-field translation keys in the
     *                         per-page documents; pass {@link AngularI18nCatalog#empty()} to disable.
     */
    public int export(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                      List<ServiceInfo> services, EndpointMatcher.MatchResult matchResult,
                      Path outputZip, boolean unroutedFallback, FrontendFramework framework,
                      AngularI18nCatalog i18nCatalog) throws IOException {
        return export(routes, componentsByName, services, matchResult, outputZip, unroutedFallback,
                framework, i18nCatalog, Map.of());
    }

    /**
     * @param layoutsByPage component name → ordered layout chain (root-first); Next.js App
     *                      Router only. Pass {@code Map.of()} for other frameworks.
     */
    public int export(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                      List<ServiceInfo> services, EndpointMatcher.MatchResult matchResult,
                      Path outputZip, boolean unroutedFallback, FrontendFramework framework,
                      AngularI18nCatalog i18nCatalog, Map<String, List<String>> layoutsByPage) throws IOException {

        Map<RouteNode, String> slugs = PageSlugAssigner.assign(routes, componentsByName);
        FrontendPagesRenderer pagesRenderer = new FrontendPagesRenderer(i18nCatalog);
        pagesRenderer.useEmbeddedComponentsFrom(routes, componentsByName, framework);
        Map<String, List<MatchedFlow>> flowsByService = pagesRenderer.buildFlowsByService(matchResult);
        Map<String, String> httpDescriptors = pagesRenderer.buildHttpMethodDescriptors(services);

        Path parent = outputZip.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputZip), StandardCharsets.UTF_8)) {
            writePageDocs((relPath, content) -> writeEntry(zos, relPath, content),
                    routes, componentsByName, slugs, pagesRenderer, flowsByService, httpDescriptors, framework, "",
                    ZIP_PAGES_DIR_PREFIX, null, layoutsByPage);

            String sitemapDoc = new SitemapRenderer().renderWithLinks(routes, componentsByName, slugs,
                    unroutedFallback, ZIP_PAGES_DIR_PREFIX);
            writeEntry(zos, "index-spec-sitemap.md", sitemapDoc);

            String indexDoc = renderArchitectureIndex(routes, componentsByName, services, slugs, unroutedFallback,
                    ZIP_PAGES_DIR_PREFIX);
            writeEntry(zos, "index-spec-frontend-pages.md", indexDoc);
        }

        return slugs.size();
    }

    /**
     * Writes the same content as {@link #export} directly as a plain directory tree
     * (no ZIP), rooted at {@code targetDir}:
     * <pre>
     *   targetDir/index-spec-frontend-pages.md
     *   targetDir/index-spec-sitemap.md
     *   targetDir/frontend-pages/&lt;slug&gt;.md
     * </pre>
     *
     * @return number of individual page files written
     */
    public int exportToDirectory(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                 List<ServiceInfo> services, EndpointMatcher.MatchResult matchResult,
                                 Path targetDir, boolean unroutedFallback, FrontendFramework framework,
                                 AngularI18nCatalog i18nCatalog) throws IOException {
        return exportToDirectory(routes, componentsByName, services, matchResult, targetDir,
                unroutedFallback, framework, i18nCatalog, null);
    }

    /**
     * Same as the other {@code exportToDirectory}, but each page document gets an appended
     * {@code ## Related} block from {@code relatedBlockFn} (given the page's route and slug;
     * may return an empty string). Pass {@code null} to skip cross-reference sections.
     */
    public int exportToDirectory(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                 List<ServiceInfo> services, EndpointMatcher.MatchResult matchResult,
                                 Path targetDir, boolean unroutedFallback, FrontendFramework framework,
                                 AngularI18nCatalog i18nCatalog,
                                 java.util.function.BiFunction<RouteNode, String, String> relatedBlockFn)
            throws IOException {
        return exportToDirectory(routes, componentsByName, services, matchResult, targetDir,
                unroutedFallback, framework, i18nCatalog, relatedBlockFn, Map.of());
    }

    /**
     * Same as the other {@code exportToDirectory}, additionally annotating each page with its
     * layout chain (component name → ordered layout names, root-first; Next.js App Router only).
     */
    public int exportToDirectory(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                 List<ServiceInfo> services, EndpointMatcher.MatchResult matchResult,
                                 Path targetDir, boolean unroutedFallback, FrontendFramework framework,
                                 AngularI18nCatalog i18nCatalog,
                                 java.util.function.BiFunction<RouteNode, String, String> relatedBlockFn,
                                 Map<String, List<String>> layoutsByPage)
            throws IOException {

        Map<RouteNode, String> slugs = PageSlugAssigner.assign(routes, componentsByName);
        FrontendPagesRenderer pagesRenderer = new FrontendPagesRenderer(i18nCatalog);
        pagesRenderer.useEmbeddedComponentsFrom(routes, componentsByName, framework);
        Map<String, List<MatchedFlow>> flowsByService = pagesRenderer.buildFlowsByService(matchResult);
        Map<String, String> httpDescriptors = pagesRenderer.buildHttpMethodDescriptors(services);

        Files.createDirectories(targetDir);

        writePageDocs((relPath, content) -> writeFile(targetDir, relPath, content),
                routes, componentsByName, slugs, pagesRenderer, flowsByService, httpDescriptors, framework, "",
                DIRECTORY_PAGES_DIR_PREFIX, relatedBlockFn, layoutsByPage);

        String sitemapDoc = new SitemapRenderer().renderWithLinks(routes, componentsByName, slugs,
                unroutedFallback, DIRECTORY_PAGES_DIR_PREFIX);
        writeFile(targetDir, "index-spec-sitemap.md", sitemapDoc);

        String indexDoc = renderArchitectureIndex(routes, componentsByName, services, slugs, unroutedFallback,
                DIRECTORY_PAGES_DIR_PREFIX);
        writeFile(targetDir, "index-spec-frontend-pages.md", indexDoc);

        return slugs.size();
    }

    // -----------------------------------------------------------------------
    // <pagesDirPrefix>/<slug>.md — one file per page
    // -----------------------------------------------------------------------

    @FunctionalInterface
    private interface EntryWriter {
        void write(String relativePath, String content) throws IOException;
    }

    private void writePageDocs(EntryWriter writer, List<RouteNode> routes,
                              Map<String, ComponentInfo> componentsByName,
                              Map<RouteNode, String> slugs, FrontendPagesRenderer pagesRenderer,
                              Map<String, List<MatchedFlow>> flowsByService,
                              Map<String, String> httpDescriptors,
                              FrontendFramework framework,
                              String parentPath, String pagesDirPrefix,
                              java.util.function.BiFunction<RouteNode, String, String> relatedBlockFn) throws IOException {
        writePageDocs(writer, routes, componentsByName, slugs, pagesRenderer, flowsByService, httpDescriptors,
                framework, parentPath, pagesDirPrefix, relatedBlockFn, Map.of());
    }

    private void writePageDocs(EntryWriter writer, List<RouteNode> routes,
                              Map<String, ComponentInfo> componentsByName,
                              Map<RouteNode, String> slugs, FrontendPagesRenderer pagesRenderer,
                              Map<String, List<MatchedFlow>> flowsByService,
                              Map<String, String> httpDescriptors,
                              FrontendFramework framework,
                              String parentPath, String pagesDirPrefix,
                              java.util.function.BiFunction<RouteNode, String, String> relatedBlockFn,
                              Map<String, List<String>> layoutsByPage) throws IOException {
        for (RouteNode route : routes) {
            String fullPath = joinPath(parentPath, route.getPath());
            if (route.isPage()) {
                String slug = slugs.get(route);
                ComponentInfo comp = componentsByName.get(route.getComponentName());
                List<String> layoutChain = layoutsByPage.getOrDefault(route.getComponentName(), List.of());

                StringBuilder sb = new StringBuilder();
                pagesRenderer.renderPage(sb, fullPath, route.getComponentName(), comp, route,
                        flowsByService, httpDescriptors, framework, layoutChain);

                if (relatedBlockFn != null) {
                    String related = relatedBlockFn.apply(route, slug);
                    if (related != null && !related.isBlank()) sb.append("\n").append(related);
                }

                writer.write(pagesDirPrefix + "/" + slug + ".md", sb.toString());
            }
            writePageDocs(writer, route.getChildren(), componentsByName, slugs, pagesRenderer,
                    flowsByService, httpDescriptors, framework, fullPath, pagesDirPrefix, relatedBlockFn, layoutsByPage);
        }
    }

    /**
     * Ordered page references (route, component, slug, path within the pages directory) using
     * the same slugs as {@link #exportToDirectory}, so callers can register page nodes for
     * cross-referencing and resolve each page's detail-document path consistently.
     */
    public List<PageRef> pageRefs(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName) {
        Map<RouteNode, String> slugs = PageSlugAssigner.assign(routes, componentsByName);
        List<PageRef> refs = new java.util.ArrayList<>();
        collectPageRefs(routes, componentsByName, slugs, refs);
        return refs;
    }

    private void collectPageRefs(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                 Map<RouteNode, String> slugs, List<PageRef> acc) {
        for (RouteNode route : routes) {
            if (route.isPage()) {
                String slug = slugs.get(route);
                ComponentInfo comp = componentsByName.get(route.getComponentName());
                String label = route.getComponentName() != null ? route.getComponentName() : slug;
                acc.add(new PageRef(route, comp, slug, DIRECTORY_PAGES_DIR_PREFIX + "/" + slug + ".md", label));
            }
            collectPageRefs(route.getChildren(), componentsByName, slugs, acc);
        }
    }

    /** One page's identity for cross-referencing: {@code relPathWithinDir} is under the pages dir. */
    public record PageRef(RouteNode route, ComponentInfo component, String slug,
                          String relPathWithinDir, String label) {}

    // -----------------------------------------------------------------------
    // index-spec-frontend-pages.md
    // -----------------------------------------------------------------------

    private String renderArchitectureIndex(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                          List<ServiceInfo> services, Map<RouteNode, String> slugs,
                                          boolean unroutedFallback, String pagesDirPrefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Front-End Architecture Index\n\n");
        sb.append("This index maps the application's functional sections to their respective detailed documentation.\n\n");

        if (unroutedFallback) {
            sb.append(UnroutedPagesFallback.NOTE).append("\n\n");
        }

        sb.append("## 1. Functional Site Map\n");
        sb.append("*The primary navigation and user-facing structure.*\n\n");
        renderFunctionalTree(sb, routes, slugs, 0, pagesDirPrefix);
        sb.append("\n");

        Set<String> pageComponentNames = new HashSet<>();
        collectPageComponentNames(routes, pageComponentNames);

        List<ComponentInfo> auxiliary = componentsByName.values().stream()
                .filter(c -> !pageComponentNames.contains(c.getClassName()))
                .sorted(Comparator.comparing(ComponentInfo::getClassName))
                .toList();

        sb.append("## 2. Auxiliary & Unlisted Components\n");
        sb.append("*Components not reachable through the route tree — shared utilities, ")
          .append("modals, or system-level components.*\n\n");
        if (auxiliary.isEmpty()) {
            sb.append("*None found.*\n\n");
        } else {
            for (ComponentInfo c : auxiliary) {
                sb.append("- **").append(humanize(c.getClassName())).append(":** `")
                  .append(c.getClassName()).append("`\n");
            }
            sb.append("\n");
        }

        sb.append("## 3. Global Architecture\n");
        sb.append("*Core services used across the application.*\n\n");
        if (services.isEmpty()) {
            sb.append("*None found.*\n\n");
        } else {
            services.stream()
                    .map(ServiceInfo::getClassName)
                    .distinct()
                    .sorted()
                    .forEach(name -> sb.append("- `").append(name).append("`\n"));
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("*Note: Links point to individual page files in `").append(pagesDirPrefix).append("/` ")
          .append("for detailed dependency and interaction mapping.*\n");
        return sb.toString();
    }

    private void renderFunctionalTree(StringBuilder sb, List<RouteNode> routes,
                                     Map<RouteNode, String> slugs, int depth, String pagesDirPrefix) {
        String indent = "    ".repeat(depth);
        for (RouteNode route : routes) {
            if (!route.isPage()) {
                renderFunctionalTree(sb, route.getChildren(), slugs, depth, pagesDirPrefix);
                continue;
            }
            String label = route.getTitle() != null ? route.getTitle() : humanize(route.getComponentName());
            String slug = slugs.get(route);
            sb.append(indent).append("- **").append(label).append(":** [")
              .append(route.getComponentName()).append("](./").append(pagesDirPrefix).append("/")
              .append(slug).append(".md)\n");
            renderFunctionalTree(sb, route.getChildren(), slugs, depth + 1, pagesDirPrefix);
        }
    }

    private void collectPageComponentNames(List<RouteNode> routes, Set<String> acc) {
        for (RouteNode r : routes) {
            if (r.isPage()) acc.add(r.getComponentName());
            collectPageComponentNames(r.getChildren(), acc);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void writeEntry(ZipOutputStream zos, String entryName, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void writeFile(Path targetDir, String relativePath, String content) throws IOException {
        Path file = targetDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String joinPath(String parent, String segment) {
        if (segment == null || segment.isEmpty()) return parent;
        if (parent.isEmpty()) return segment;
        return parent + "/" + segment;
    }

    /** "LockDetailComponent" → "Lock Detail" */
    private static String humanize(String componentName) {
        if (componentName == null) return "";
        String base = componentName.replaceAll("(Component|Page|View)$", "");
        return base.replaceAll("([a-z])([A-Z])", "$1 $2");
    }
}
