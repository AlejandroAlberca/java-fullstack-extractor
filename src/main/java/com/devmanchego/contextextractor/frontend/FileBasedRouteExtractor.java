package com.devmanchego.contextextractor.frontend;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File-based route extractor for Next.js and Nuxt frameworks.
 *
 * Maps directory structure to RouteNode tree (Variant B: also emits minimal ComponentInfo).
 *
 * Next.js support:
 * - App Router: app/page.tsx (and nested)
 * - Pages Router: pages/*.tsx (and nested)
 *
 * Nuxt support:
 * - pages/index.vue, pages/users/[id].vue, etc.
 *
 * Strategy: file path → route path (segments normalized: [id]→:id, [...slug]→:slug*),
 * component name derived from path (e.g., pages/users/[id].vue → UsersIdPage),
 * lazy=true for all routes (default code-splitting), no redirects.
 */
public final class FileBasedRouteExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileBasedRouteExtractor.class);

    private static final Pattern COMPONENT_PATTERN = Pattern.compile(
            "(?:export\\s+default\\s+(?:class|function)\\s+(\\w+)|export\\s+default\\s+\\{)");

    private final Path frontendRoot;
    private final FrontendFramework framework;

    public FileBasedRouteExtractor(Path frontendRoot, FrontendFramework framework) {
        this.frontendRoot = frontendRoot;
        this.framework = framework;
    }

    public List<RouteNode> extract() throws IOException {
        if (framework == FrontendFramework.NEXTJS) {
            return extractNextJs();
        } else if (framework == FrontendFramework.NUXT) {
            return extractNuxt();
        }
        return Collections.emptyList();
    }

    /**
     * Returns a map of component names to minimal ComponentInfo (for Variant B integration).
     * Component name is synthetic: derived from file path.
     */
    public Map<String, ComponentInfo> extractComponentInfoByName() throws IOException {
        Map<String, ComponentInfo> result = new HashMap<>();
        if (framework == FrontendFramework.NEXTJS) {
            collectComponentsFromNextJs(result);
        } else if (framework == FrontendFramework.NUXT) {
            collectComponentsFromNuxt(result);
        }
        return result;
    }

    /**
     * Next.js App Router only: builds the chain of {@code layout.tsx} files wrapping each
     * page, root-first. Keyed by the same synthetic component name {@link #extractComponentInfoByName}
     * assigns to the page, so callers can join the two maps directly.
     *
     * <p>Nuxt uses a different, name-based layout mechanism ({@code layouts/*.vue} selected
     * via {@code definePageMeta({ layout: 'name' })}) — out of scope here, since it cannot be
     * derived from directory structure alone.
     */
    public Map<String, List<String>> extractLayoutChains() throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (framework != FrontendFramework.NEXTJS) return result;
        Path appDir = frontendRoot.resolve("app");
        if (!Files.isDirectory(appDir)) return result;
        walkLayoutChains(appDir, "", new ArrayList<>(), result);
        return result;
    }

    private void walkLayoutChains(Path dir, String currentPath, List<String> inheritedLayouts,
                                  Map<String, List<String>> result) throws IOException {
        List<String> layouts = new ArrayList<>(inheritedLayouts);
        boolean hasLayout = Stream.of("layout.tsx", "layout.jsx", "layout.ts", "layout.js")
                .anyMatch(name -> Files.isRegularFile(dir.resolve(name)));
        if (hasLayout) {
            layouts.add(generateLayoutName(currentPath));
        }

        boolean hasPage = Stream.of("page.tsx", "page.jsx", "page.ts", "page.js")
                .anyMatch(name -> Files.isRegularFile(dir.resolve(name)));
        if (hasPage && !layouts.isEmpty()) {
            String routePath = currentPath.isEmpty() || currentPath.equals("/") ? "" : currentPath;
            result.put(generateComponentName(routePath), layouts);
        }

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith("_") && !name.startsWith(".") && Files.isDirectory(p);
            }).sorted().forEach(entries::add);
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (isSpecialFile(name)) continue;

            if (name.startsWith("(") && name.endsWith(")")) {
                walkLayoutChains(entry, currentPath, layouts, result);
                continue;
            }

            String segment = normalizeDynamicSegment(name);
            String nextPath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;
            walkLayoutChains(entry, nextPath, layouts, result);
        }
    }

    // ============ Next.js ============

    private List<RouteNode> extractNextJs() throws IOException {
        List<RouteNode> routes = new ArrayList<>();

        // Try app/ (App Router) first
        Path appDir = frontendRoot.resolve("app");
        if (Files.isDirectory(appDir)) {
            routes.addAll(scanAppRouter(appDir, ""));
        }

        // Also scan pages/ (Pages Router) if it exists
        Path pagesDir = frontendRoot.resolve("pages");
        if (Files.isDirectory(pagesDir)) {
            routes.addAll(scanPagesRouter(pagesDir, ""));
        }

        return routes;
    }

    private List<RouteNode> scanAppRouter(Path dir, String currentPath) throws IOException {
        List<RouteNode> routes = new ArrayList<>();

        // Check if current directory has a page.tsx/page.jsx (the route itself)
        boolean hasPage = Files.exists(dir.resolve("page.tsx")) ||
                          Files.exists(dir.resolve("page.jsx")) ||
                          Files.exists(dir.resolve("page.ts")) ||
                          Files.exists(dir.resolve("page.js"));

        if (hasPage) {
            RouteNode route = buildRouteNode(currentPath, dir, "page");
            routes.add(route);
        }

        // Scan subdirectories
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith("_") && !name.startsWith(".") && Files.isDirectory(p);
            }).sorted().forEach(entries::add);
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();

            // Skip special files
            if (isSpecialFile(name)) continue;

            // Route group: (marketing) → flatten (no segment)
            if (name.startsWith("(") && name.endsWith(")")) {
                routes.addAll(scanAppRouter(entry, currentPath));
                continue;
            }

            // Handle [id], [...slug] brackets
            String segment = normalizeDynamicSegment(name);
            String nextPath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;

            // Recurse for child routes
            routes.addAll(scanAppRouter(entry, nextPath));
        }

        return routes;
    }

    private List<RouteNode> scanPagesRouter(Path dir, String currentPath) throws IOException {
        List<RouteNode> routes = new ArrayList<>();
        List<Path> entries = new ArrayList<>();

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return (name.endsWith(".tsx") || name.endsWith(".jsx") ||
                                name.endsWith(".ts") || name.endsWith(".js")) &&
                               !name.startsWith("_") &&
                               !name.endsWith(".spec.ts");
                    })
                    .sorted()
                    .forEach(entries::add);
        }

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .forEach(entries::add);
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();

            if (Files.isDirectory(entry)) {
                String segment = normalizeDynamicSegment(name);
                String nextPath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;
                routes.addAll(scanPagesRouter(entry, nextPath));
            } else if (Files.isRegularFile(entry)) {
                String fileName = name.replaceAll("\\.(tsx?|jsx?)$", "");
                if ("index".equals(fileName)) {
                    // index.tsx → current path (or / if empty)
                    String routePath = currentPath.isEmpty() ? "/" : currentPath;
                    RouteNode route = buildRouteNode(routePath, entry, null);
                    routes.add(route);
                } else {
                    // users.tsx → /users
                    String segment = normalizeDynamicSegment(fileName);
                    String routePath = currentPath.isEmpty() ? "/" + segment : currentPath + "/" + segment;
                    RouteNode route = buildRouteNode(routePath, entry, null);
                    routes.add(route);
                }
            }
        }

        return routes;
    }

    // ============ Nuxt ============

    private List<RouteNode> extractNuxt() throws IOException {
        Path pagesDir = frontendRoot.resolve("pages");
        if (!Files.isDirectory(pagesDir)) {
            return Collections.emptyList();
        }
        return scanNuxtPages(pagesDir, "");
    }

    private List<RouteNode> scanNuxtPages(Path dir, String currentPath) throws IOException {
        List<RouteNode> routes = new ArrayList<>();
        List<Path> entries = new ArrayList<>();

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith("_") && !name.startsWith(".");
            }).sorted().forEach(entries::add);
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();

            if (Files.isDirectory(entry)) {
                String segment = normalizeDynamicSegment(name);
                String nextPath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;
                routes.addAll(scanNuxtPages(entry, nextPath));
            } else if (Files.isRegularFile(entry)) {
                if (!name.endsWith(".vue")) continue;

                String fileName = name.replaceAll("\\.vue$", "");
                if ("index".equals(fileName)) {
                    String routePath = currentPath.isEmpty() ? "/" : currentPath;
                    RouteNode route = buildRouteNode(routePath, entry, null);
                    routes.add(route);
                } else {
                    String segment = normalizeDynamicSegment(fileName);
                    String routePath = currentPath.isEmpty() ? "/" + segment : currentPath + "/" + segment;
                    RouteNode route = buildRouteNode(routePath, entry, null);
                    routes.add(route);
                }
            }
        }

        return routes;
    }

    // ============ Utilities ============

    private String normalizeDynamicSegment(String name) {
        // [id] → :id
        if (name.startsWith("[") && name.endsWith("]")) {
            String inner = name.substring(1, name.length() - 1);
            // [...slug] → slug*
            if (inner.startsWith("...")) {
                return ":" + inner.substring(3) + "*";
            }
            return ":" + inner;
        }
        return name;
    }

    private boolean isSpecialFile(String name) {
        // layout, template, loading, error, not-found, route.ts (API), etc.
        return name.equals("layout") || name.equals("template") ||
               name.equals("loading") || name.equals("error") ||
               name.equals("not-found") || name.equals("route") ||
               name.endsWith(".d.ts");
    }

    private RouteNode buildRouteNode(String routePath, Path filePath, String suffix) {
        String path = routePath.isEmpty() || routePath.equals("/") ? "" : routePath;
        String componentName = generateComponentName(routePath);
        // All file-based routes are lazy by default (code-splitting)
        return new RouteNode(path, componentName, null, null, true, Collections.emptyList());
    }

    private String generateComponentName(String routePath) {
        // Transform /users/[id] → UsersIdPage
        if (routePath.isEmpty() || routePath.equals("/")) {
            return "HomePage";
        }
        return generateSegmentName(routePath, "Page");
    }

    /** Same segment-based naming as {@link #generateComponentName}, suffixed "Layout" instead of "Page". */
    private String generateLayoutName(String routePath) {
        if (routePath.isEmpty() || routePath.equals("/")) {
            return "RootLayout";
        }
        return generateSegmentName(routePath, "Layout");
    }

    private String generateSegmentName(String routePath, String suffix) {
        String[] parts = routePath.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            // :id → Id, :slug* → Slug
            String segment = part.replaceAll("^:", "").replaceAll("\\*$", "");
            sb.append(capitalize(segment));
        }
        sb.append(suffix);
        return sb.toString();
    }

    private String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void collectComponentsFromNextJs(Map<String, ComponentInfo> result) throws IOException {
        // App Router
        Path appDir = frontendRoot.resolve("app");
        if (Files.isDirectory(appDir)) {
            scanAppRouterForComponents(appDir, "", result);
        }

        // Pages Router
        Path pagesDir = frontendRoot.resolve("pages");
        if (Files.isDirectory(pagesDir)) {
            scanPagesRouterForComponents(pagesDir, "", result);
        }
    }

    private void scanAppRouterForComponents(Path dir, String currentPath, Map<String, ComponentInfo> result) throws IOException {
        // Check if current directory has a page.tsx/page.jsx (the route itself)
        Path pagePath = Stream.of("page.tsx", "page.jsx", "page.ts", "page.js")
                .map(dir::resolve)
                .filter(Files::exists)
                .findFirst()
                .orElse(null);

        if (pagePath != null) {
            String routePath = currentPath.isEmpty() || currentPath.equals("/") ? "" : currentPath;
            String componentName = generateComponentName(routePath);
            // Absolute path, matching the convention used by ReactJvmExtractor/VueJvmExtractor/
            // AntlrAngularParser — FrontendImportGraphExtractor resolves imports relative to this.
            String absPath = pagePath.toAbsolutePath().toString();
            ComponentInfo comp = new ComponentInfo(componentName, absPath, "", Collections.emptyList());
            result.put(componentName, comp);
        }

        // Scan subdirectories
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith("_") && !name.startsWith(".") && Files.isDirectory(p);
            }).sorted().forEach(entries::add);
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (isSpecialFile(name)) continue;

            String segment = normalizeDynamicSegment(name);
            String nextPath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;

            scanAppRouterForComponents(entry, nextPath, result);
        }
    }

    private void scanPagesRouterForComponents(Path dir, String currentPath, Map<String, ComponentInfo> result) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith("_") && !name.startsWith(".");
            }).sorted().forEach(entries::add);
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();

            if (Files.isRegularFile(entry)) {
                if (!(name.endsWith(".tsx") || name.endsWith(".jsx"))) continue;

                String fileName = name.replaceAll("\\.(tsx?|jsx?)$", "");
                String routePath;
                if ("index".equals(fileName)) {
                    routePath = currentPath.isEmpty() || currentPath.equals("/") ? "" : currentPath;
                } else {
                    String segment = normalizeDynamicSegment(fileName);
                    routePath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;
                }

                String componentName = generateComponentName(routePath);
                String absPath = entry.toAbsolutePath().toString();
                ComponentInfo comp = new ComponentInfo(componentName, absPath, "", Collections.emptyList());
                result.put(componentName, comp);
            } else if (Files.isDirectory(entry)) {
                String segment = normalizeDynamicSegment(name);
                String nextPath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;
                scanPagesRouterForComponents(entry, nextPath, result);
            }
        }
    }

    private void collectComponentsFromNuxt(Map<String, ComponentInfo> result) throws IOException {
        Path pagesDir = frontendRoot.resolve("pages");
        if (Files.isDirectory(pagesDir)) {
            scanNuxtPagesForComponents(pagesDir, "", result);
        }
    }

    private void scanNuxtPagesForComponents(Path dir, String currentPath, Map<String, ComponentInfo> result) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith("_") && !name.startsWith(".");
            }).sorted().forEach(entries::add);
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();

            if (Files.isDirectory(entry)) {
                String segment = normalizeDynamicSegment(name);
                String nextPath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;
                scanNuxtPagesForComponents(entry, nextPath, result);
            } else if (Files.isRegularFile(entry) && name.endsWith(".vue")) {
                String fileName = name.replaceAll("\\.vue$", "");
                String routePath;
                if ("index".equals(fileName)) {
                    routePath = currentPath.isEmpty() || currentPath.equals("/") ? "" : currentPath;
                } else {
                    String segment = normalizeDynamicSegment(fileName);
                    routePath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;
                }

                String componentName = generateComponentName(routePath);
                String absPath = entry.toAbsolutePath().toString();
                ComponentInfo comp = new ComponentInfo(componentName, absPath, "", Collections.emptyList());
                result.put(componentName, comp);
            }
        }
    }
}
