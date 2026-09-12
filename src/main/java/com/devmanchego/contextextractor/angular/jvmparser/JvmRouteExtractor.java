package com.devmanchego.contextextractor.angular.jvmparser;

import com.devmanchego.contextextractor.angular.model.RouteNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Strategy B route-tree extractor: parses Angular {@code Routes} arrays with a
 * lightweight character-level scanner (no type resolution).
 *
 * Detection scope: files matching {@code *-routing.module.ts}, {@code *.routes.ts},
 * {@code app.routes.ts}, or any .ts file containing {@code RouterModule} /
 * {@code provideRouter}. Lazy {@code loadChildren} imports are linked by resolving
 * the literal import path.
 *
 * Known limitation: routes built dynamically (spreads, factory functions) are not
 * followed — matching the graceful-degradation contract of the ANTLR parser.
 */
public final class JvmRouteExtractor {

    private static final Logger log = LoggerFactory.getLogger(JvmRouteExtractor.class);

    private static final Pattern ROUTES_DECL = Pattern.compile(
            "(?::\\s*Routes\\s*=|RouterModule\\s*\\.\\s*for(?:Root|Child)\\s*\\(|provideRouter\\s*\\()\\s*\\[");
    private static final Pattern FOR_ROOT = Pattern.compile("RouterModule\\s*\\.\\s*forRoot|provideRouter\\s*\\(");
    private static final Pattern IMPORT_PATH = Pattern.compile("import\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]\\s*\\)");
    private static final Pattern THEN_COMPONENT = Pattern.compile("=>\\s*\\w+\\.(\\w+)\\s*\\)?\\s*$");

    private final Path projectRoot;

    public JvmRouteExtractor(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public List<RouteNode> extract() throws IOException {
        // file (forward-slash absolute) → routes declared in it
        Map<String, List<MutableRoute>> routesByFile = new LinkedHashMap<>();
        String rootFile = null;

        List<Path> candidates;
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            candidates = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".ts"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().endsWith(".spec.ts"))
                    .filter(p -> !p.toString().endsWith(".d.ts"))
                    .toList();
        }

        for (Path file : candidates) {
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.debug("Cannot read {}: {}", file, e.getMessage());
                continue;
            }
            if (!content.contains("RouterModule") && !content.contains("provideRouter")
                    && !content.contains(": Routes")) {
                continue;
            }

            List<MutableRoute> fileRoutes = new ArrayList<>();
            Matcher decl = ROUTES_DECL.matcher(content);
            while (decl.find()) {
                int arrayStart = content.indexOf('[', decl.start());
                if (arrayStart < 0) continue;
                fileRoutes.addAll(parseRouteArray(content, arrayStart, file));
            }
            if (fileRoutes.isEmpty()) continue;

            String key = file.toAbsolutePath().normalize().toString().replace('\\', '/');
            routesByFile.put(key, fileRoutes);
            if (rootFile == null && FOR_ROOT.matcher(content).find()) {
                rootFile = key;
            }
        }

        // Link lazy children by import path, tracking which files became nested subtrees.
        java.util.Set<String> linkedFiles = new java.util.HashSet<>();
        for (Map.Entry<String, List<MutableRoute>> e : routesByFile.entrySet()) {
            linkLazy(e.getValue(), routesByFile, linkedFiles);
        }

        List<MutableRoute> top;
        if (rootFile != null) {
            // The forRoot() file provides the root routes. Feature modules wired in via
            // RouterModule.forChild() are imported into their NgModules (eagerly or lazily)
            // rather than referenced from the root array, so their routes must be appended
            // as additional top-level routes — otherwise their pages are silently dropped.
            // Files already pulled in as lazy loadChildren are skipped to avoid duplication.
            top = new ArrayList<>(routesByFile.get(rootFile));
            for (Map.Entry<String, List<MutableRoute>> e : routesByFile.entrySet()) {
                if (e.getKey().equals(rootFile) || linkedFiles.contains(e.getKey())) continue;
                top.addAll(e.getValue());
            }
        } else {
            top = routesByFile.entrySet().stream()
                    .filter(e -> !linkedFiles.contains(e.getKey()))
                    .flatMap(e -> e.getValue().stream())
                    .toList();
        }
        List<RouteNode> result = top.stream().map(MutableRoute::freeze).toList();
        log.info("JvmRouteExtractor: {} route file(s), {} top-level route(s).",
                routesByFile.size(), result.size());
        return result;
    }

    // -----------------------------------------------------------------------
    // Character-level route array parsing
    // -----------------------------------------------------------------------

    /** Parses the array literal starting at {@code arrayStart} ('['). */
    private List<MutableRoute> parseRouteArray(String content, int arrayStart, Path file) {
        List<MutableRoute> routes = new ArrayList<>();
        int i = arrayStart + 1;
        int end = matchingBracket(content, arrayStart, '[', ']');
        if (end < 0) return routes;

        while (i < end) {
            int objStart = content.indexOf('{', i);
            if (objStart < 0 || objStart >= end) break;
            int objEnd = matchingBracket(content, objStart, '{', '}');
            if (objEnd < 0 || objEnd > end) break;
            routes.add(parseRouteObject(content, objStart, objEnd, file));
            i = objEnd + 1;
        }
        return routes;
    }

    private MutableRoute parseRouteObject(String content, int objStart, int objEnd, Path file) {
        MutableRoute route = new MutableRoute();
        int i = objStart + 1;

        while (i < objEnd) {
            int keyEnd = content.indexOf(':', i);
            if (keyEnd < 0 || keyEnd >= objEnd) break;
            String key = content.substring(i, keyEnd).trim()
                    .replaceAll("^[,\\s]+", "").replaceAll("['\"]", "");
            int valueStart = keyEnd + 1;
            int valueEnd = findValueEnd(content, valueStart, objEnd);
            String value = content.substring(valueStart, valueEnd).trim();

            switch (key) {
                case "path"       -> route.path = extractPathValue(value);
                case "redirectTo" -> route.redirectTo = stripQuotes(value);
                case "component"  -> route.componentName = value.replaceAll("[^\\w].*$", "");
                case "loadComponent" -> {
                    route.lazy = true;
                    Matcher m = THEN_COMPONENT.matcher(value);
                    if (m.find()) route.componentName = m.group(1);
                }
                case "loadChildren" -> {
                    route.lazy = true;
                    Matcher m = IMPORT_PATH.matcher(value);
                    if (m.find()) route.lazyImport = resolveImport(m.group(1), file);
                }
                case "children" -> {
                    int arr = content.indexOf('[', valueStart);
                    if (arr >= 0 && arr < objEnd) {
                        route.children.addAll(parseRouteArray(content, arr, file));
                    }
                }
                case "title" -> route.title = stripQuotes(value);
                case "data" -> {
                    Matcher t = Pattern.compile(
                            "(?:title|breadcrumb|label)\\s*:\\s*['\"`]([^'\"`]+)['\"`]").matcher(value);
                    if (t.find()) route.title = t.group(1);
                }
                default -> { /* guards, resolvers, etc. — ignored */ }
            }
            i = valueEnd + 1;
        }
        return route;
    }

    /**
     * Finds the end of a property value: the next top-level ',' or the object end,
     * skipping over nested brackets, braces, parens and string literals.
     */
    private int findValueEnd(String content, int start, int objEnd) {
        int depth = 0;
        for (int i = start; i < objEnd; i++) {
            char c = content.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(content, i);
                continue;
            }
            if (c == '[' || c == '{' || c == '(') depth++;
            else if (c == ']' || c == '}' || c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return objEnd;
    }

    /** Returns the index of the bracket matching the one at {@code open}. */
    private int matchingBracket(String content, int open, char openCh, char closeCh) {
        int depth = 0;
        for (int i = open; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(content, i);
                continue;
            }
            if (c == openCh) depth++;
            else if (c == closeCh && --depth == 0) return i;
        }
        return -1;
    }

    /** Returns the index of the closing quote of the string starting at {@code start}. */
    private static int skipString(String content, int start) {
        char quote = content.charAt(start);
        for (int i = start + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return i;
        }
        return content.length() - 1;
    }

    private String resolveImport(String importPath, Path fromFile) {
        Path resolved = fromFile.getParent().resolve(importPath).normalize();
        String s = resolved.toString().replace('\\', '/');
        return s.endsWith(".ts") ? s : s + ".ts";
    }

    /**
     * Extracts path value from various formats:
     * - Literal strings: 'path' or "path" or `path`
     * - Template strings: `${expr}/literal` → extracts literal parts
     * - Variables/functions: NavigationService.getPath() → returns as-is for logging
     */
    private static String extractPathValue(String value) {
        value = value.trim();

        // Case 1: Literal string (quoted)
        if ((value.startsWith("'") && value.endsWith("'")) ||
            (value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("`") && value.endsWith("`") && !value.contains("${"))) {
            return value.replaceAll("^['\"`]|['\"`]$", "");
        }

        // Case 2: Template string with interpolation: `${expr}/literal` or `prefix/${expr}/suffix`
        if (value.startsWith("`") && value.endsWith("`")) {
            String templateContent = value.substring(1, value.length() - 1);

            // Extract all literal parts (non-${...} portions)
            StringBuilder literalParts = new StringBuilder();
            int i = 0;
            while (i < templateContent.length()) {
                int exprStart = templateContent.indexOf("${", i);
                if (exprStart < 0) {
                    // No more expressions, append the rest
                    literalParts.append(templateContent.substring(i));
                    break;
                }

                // Append the literal part before the expression
                literalParts.append(templateContent, i, exprStart);

                // Skip the expression: find matching } while handling nested braces and strings
                int braceDepth = 1; // We're inside ${...}
                int j = exprStart + 2;
                boolean found = false;
                while (j < templateContent.length() && braceDepth > 0) {
                    char c = templateContent.charAt(j);
                    if (c == '\'' || c == '"' || c == '`') {
                        // Skip string literal to avoid counting braces inside strings
                        j = skipString(templateContent, j);
                    } else if (c == '{') {
                        braceDepth++;
                        j++;
                    } else if (c == '}') {
                        braceDepth--;
                        if (braceDepth == 0) {
                            i = j + 1;
                            found = true;
                        }
                        j++;
                    } else {
                        j++;
                    }
                }
                if (!found) {
                    // Expression wasn't closed properly, skip to end
                    i = templateContent.length();
                }
            }

            String literal = literalParts.toString().trim();
            if (!literal.isEmpty()) {
                return literal;
            }
            // If no literal parts, return the whole thing for logging
            return value;
        }

        // Case 3: Variable or function call (e.g., NavigationService.getAccommodationManagerURLPrefix())
        // Mark as dynamic so it can be logged for manual inspection and debugging
        if (value.matches("[\\w$.()]+") && !value.isEmpty()) {
            return "[DYNAMIC: " + value + "]";
        }
        return value;
    }

    private static String stripQuotes(String s) {
        return s.replaceAll("^['\"`]|['\"`]$", "");
    }

    /**
     * Resolves {@code loadChildren} lazy imports into inline children.
     *
     * @param linkedFiles collects the file keys that were consumed as lazy children,
     *                    so {@link #extract()} can avoid also emitting them as
     *                    top-level routes (which would duplicate the whole subtree).
     */
    private void linkLazy(List<MutableRoute> routes, Map<String, List<MutableRoute>> routesByFile,
                          java.util.Set<String> linkedFiles) {
        for (MutableRoute route : routes) {
            if (route.lazyImport != null) {
                List<MutableRoute> target = routesByFile.get(route.lazyImport);
                if (target != null && target != routes) {
                    route.children.addAll(target);
                    linkedFiles.add(route.lazyImport);
                }
            }
            if (!route.children.isEmpty()) {
                linkLazy(route.children, routesByFile, linkedFiles);
            }
        }
    }

    /** Mutable builder used during parsing; frozen to immutable RouteNode at the end. */
    private static final class MutableRoute {
        String path = "";
        String componentName;
        String title;
        String redirectTo;
        boolean lazy;
        String lazyImport;
        final List<MutableRoute> children = new ArrayList<>();

        RouteNode freeze() {
            return new RouteNode(path, componentName, title, redirectTo, lazy,
                    children.stream().map(MutableRoute::freeze).toList());
        }
    }
}
