package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.HttpCallInfo;
import com.devmanchego.contextextractor.angular.model.ServiceInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase 05: chains each JSP view to its webpack bundle entry, walks that entry's module import
 * closure, and extracts the jQuery/fetch HTTP calls reachable from it — binding each page's API
 * dependencies the same way {@code ReactJvmExtractor}/{@code VueJvmExtractor} bind a component's:
 * a synthetic {@link ServiceInfo} per bound view, referenced by name from that view's
 * {@link ComponentInfo#getInjectedServices()}. Existing matching ({@code EndpointMatcher}) and
 * rendering ({@code FrontendPagesRenderer#extractApiDependencies}) already key off exactly that
 * shape, so no renderer changes are needed to make a page document list its API dependencies.
 *
 * <p><b>View → bundle entry.</b> A webpack {@code entry: { name: './src/.../file.js' }} map,
 * combined with {@code output.filename} (default {@code "[name].js"} when the config doesn't say),
 * gives every entry's built basename. A view's own {@code <script src>} tags — not ones inherited
 * from an included header/footer, which commonly reference a shared, unrelated bundle (a global
 * menu, common vendor libs) — are matched against those basenames. Exactly one match binds the
 * view; zero or more than one leaves it unbound, reported as a warning either way (see this
 * phase's acceptance criteria).
 *
 * <p><b>Module closure.</b> ES module {@code import ... from '...'} / side-effect {@code import
 * '...'} and CommonJS {@code require('...')} specifiers are resolved relative to each file, with a
 * visited-path set that both de-duplicates the closure and terminates on circular imports. A bare
 * specifier (no leading {@code .} or {@code /}) is an npm package — external, not traversed.
 *
 * <p><b>Call set.</b> Within the closure, call sites are extracted by {@link JQueryAjaxCallExtractor}
 * (Phase 03). Only the sites it resolves become HTTP calls here; the rest are listed, with the
 * reason, in each page document ({@link JspAjaxCallReport}) — never silently dropped.
 */
public final class JspBundleExtractor {

    private static final Logger log = LoggerFactory.getLogger(JspBundleExtractor.class);

    private static final Set<String> EXCLUDED_DIRS = Set.of("node_modules", "dist", "build", ".git", "target");
    private static final List<String> JS_RESOLUTION_SUFFIXES = List.of("", ".js", ".jsx", ".ts", "/index.js");
    /** Belt-and-braces bound on one entry's closure, in case of a pathological import graph. */
    private static final int MAX_CLOSURE_FILES = 300;

    private static final Pattern WEBPACK_CONFIG_NAME = Pattern.compile(
            "(?i)^webpack.*\\.(js|cjs|mjs)$|^.*\\.webpack\\.js$");
    private static final Pattern ENTRY_KEYWORD = Pattern.compile("\\bentry\\s*:\\s*\\{");
    private static final Pattern OUTPUT_KEYWORD = Pattern.compile("\\boutput\\s*:\\s*\\{");
    private static final Pattern ENTRY_PAIR = Pattern.compile("[\"']?([\\w.\\-/]+)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']");
    private static final Pattern FILENAME_PATTERN = Pattern.compile("filename\\s*:\\s*[\"']([^\"']+)[\"']");
    private static final String DEFAULT_FILENAME_PATTERN = "[name].js";

    private static final Pattern IMPORT_SPEC = Pattern.compile(
            "import\\s+(?:[^'\"]*?from\\s+)?[\"']([^\"']+)[\"']|require\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\$\\{[^}]*}");
    /**
     * Identifiers this profile's footer fragment assigns the servlet context path to
     * ({@code var basepath = "${pageContext.request.contextPath}";}) — a deployment-time
     * constant, not a call parameter, so it contributes nothing rather than a spurious
     * {@code {param}} prefix on every single URL.
     */
    private static final Set<String> CONTEXT_PATH_IDENTIFIERS = Set.of("basepath", "contextPath");
    // Named function starts, each ending at its parameter list's '(' — see functionSpans().
    private static final Pattern FUNCTION_START = Pattern.compile(
            "\\bfunction\\s+([\\w$]+)\\s*\\("                                                    // function name(
            + "|([\\w$]+)\\s*:\\s*(?:async\\s+)?function\\s*\\("                                 // name: function(
            + "|(?:const|let|var)\\s+([\\w$]+)\\s*=\\s*(?:async\\s+)?function\\s*\\("            // const name = function(
            + "|(?:this|[\\w$]+)\\.([\\w$]+)\\s*=\\s*(?:async\\s+)?function\\s*\\("              // obj.name = function(
            + "|(?:const|let|var)\\s+([\\w$]+)\\s*=\\s*(?:async\\s*)?\\((?=[^()]*\\)\\s*=>)"     // const name = (...) =>
            + "|([\\w$]+)\\s*:\\s*(?:async\\s*)?\\((?=[^()]*\\)\\s*=>)"                          // name: (...) =>
            + "|(?m:^)[ \\t]*(?:async\\s+)?([\\w$]+)\\s*\\((?=[^()]*\\)\\s*\\{)");                // name(...) {  (method shorthand)
    // Anonymous function starts: function(...) / (...) => / x =>
    private static final Pattern ANON_FUNCTION_START = Pattern.compile(
            "\\bfunction\\s*\\*?\\s*[\\w$]*\\s*\\(|\\([^()]*\\)\\s*=>|(?<![\\w$.])[\\w$]+\\s*=>");
    /** jQuery/promise callback keys: anonymous by nature — see functionSpans(). */
    private static final Set<String> CALLBACK_KEYS = Set.of(
            "success", "error", "complete", "beforeSend", "done", "fail", "always", "then", "catch", "dataFilter", "xhr");
    private static final Set<String> NOT_FUNCTION_NAMES = Set.of(
            "if", "for", "while", "switch", "catch", "function", "return", "else", "with", "do", "typeof", "new");

    public record Result(Map<String, ComponentInfo> componentsByName, List<ServiceInfo> services) {}

    private final JspFileParser jspParser = new JspFileParser();

    /**
     * @param componentsByName the components {@link JspRouteReconstructor} produced (synthetic
     *                         name → JSP file path); returned with {@code injectedServices}
     *                         populated for every view that bound to a non-empty call set
     * @param frontendRoot     the frontend project root (webpack config and JS sources)
     * @param webappRoot       directory directly containing {@code WEB-INF}
     */
    public Result bindApiCalls(Map<String, ComponentInfo> componentsByName, Path frontendRoot, Path webappRoot,
                               List<String> warnings) {
        Path root = frontendRoot.toAbsolutePath().normalize();
        Map<String, Path> registry = discoverBundleEntries(root, warnings);
        if (registry.isEmpty()) {
            warnings.add("No webpack bundle entries found under " + root
                    + " — no view can be bound to its API call set.");
            return new Result(componentsByName, List.of());
        }

        Map<Path, List<HttpCallInfo>> closureCallsCache = new LinkedHashMap<>();
        Map<String, ComponentInfo> updated = new LinkedHashMap<>(componentsByName);
        List<ServiceInfo> services = new ArrayList<>();

        for (Map.Entry<String, ComponentInfo> e : componentsByName.entrySet()) {
            ComponentInfo comp = e.getValue();
            Path jspFile = safeExistingFile(comp.getFilePath());
            if (jspFile == null) continue; // an unresolved route has no view to bind

            JspPage page;
            try {
                page = jspParser.parse(jspFile, webappRoot);
            } catch (Exception ex) {
                warnings.add("Could not parse " + rel(jspFile, webappRoot)
                        + " while binding its bundle entry: " + ex.getMessage());
                continue;
            }

            List<Path> matches = matchingEntries(jspFile, page, registry);
            if (matches.isEmpty()) {
                warnings.add("View " + rel(jspFile, webappRoot) + " has no <script> tag matching a "
                        + "known webpack bundle entry — its API dependencies are unbound.");
                continue;
            }
            if (matches.size() > 1) {
                warnings.add("View " + rel(jspFile, webappRoot) + " references " + matches.size()
                        + " different webpack bundle entries — ambiguous, leaving its API dependencies unbound.");
                continue;
            }

            Path entryFile = matches.get(0);
            List<HttpCallInfo> calls = closureCallsCache.computeIfAbsent(entryFile,
                    entry -> extractHttpCallsFromClosure(entry, root, warnings));
            if (calls.isEmpty()) continue;

            String serviceClassName = comp.getClassName() + "Bundle";
            services.add(new ServiceInfo(serviceClassName, entryFile.toString(), calls));
            updated.put(e.getKey(), new ComponentInfo(comp.getClassName(), comp.getFilePath(), comp.getSelector(),
                    List.of(serviceClassName), comp.getUiTabs()));
        }

        log.info("JspBundleExtractor: {} bundle entr{}, {} view(s) bound to a non-empty call set.",
                registry.values().stream().distinct().count(), registry.values().stream().distinct().count() == 1 ? "y" : "ies",
                services.size());
        return new Result(updated, services);
    }

    // -----------------------------------------------------------------------
    // View -> bundle entry
    // -----------------------------------------------------------------------

    /**
     * Every entry the view's own {@code <script src>} tags name — never ones inherited from an
     * included fragment, since a shared header/footer commonly links a bundle of its own (a global
     * menu, common vendor code) that has nothing to do with this view's page-specific one.
     */
    static List<Path> matchingEntries(Path viewFile, JspPage page, Map<String, Path> registry) {
        List<Path> matches = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Element script : page.document().select("script[src]")) {
            if (!viewFile.equals(page.sourceFileOf(script))) continue;
            Path entry = registry.get(basenameOf(script.attr("src")));
            if (entry != null && seen.add(entry)) matches.add(entry);
        }
        return matches;
    }

    private static String basenameOf(String src) {
        String s = src.strip();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    /** Built basename (e.g. {@code "fap.js"}) → the entry's source file, across every webpack config found. */
    static Map<String, Path> discoverBundleEntries(Path root, List<String> warnings) {
        Map<String, Path> registry = new LinkedHashMap<>();
        for (Path config : listWebpackConfigs(root, warnings)) {
            String content = readOrWarn(config, warnings);
            if (content == null) continue;
            String filenamePattern = extractFilenamePattern(content);

            Matcher entryKw = ENTRY_KEYWORD.matcher(content);
            if (!entryKw.find()) continue;
            String entryBlock = extractBalanced(content, entryKw.end() - 1, '{', '}');
            if (entryBlock == null) continue;

            Matcher pair = ENTRY_PAIR.matcher(entryBlock);
            while (pair.find()) {
                String entryName = pair.group(1);
                String sourceSpec = pair.group(2);
                Path source = resolveRelative(config.getParent(), sourceSpec);
                if (source == null) continue;
                String basename = filenamePattern.replace("[name]", entryName);
                int slash = basename.lastIndexOf('/');
                if (slash >= 0) basename = basename.substring(slash + 1);
                registry.putIfAbsent(basename, source);
            }
        }
        return registry;
    }

    private static String extractFilenamePattern(String webpackConfigContent) {
        Matcher outputKw = OUTPUT_KEYWORD.matcher(webpackConfigContent);
        if (outputKw.find()) {
            String outputBlock = extractBalanced(webpackConfigContent, outputKw.end() - 1, '{', '}');
            if (outputBlock != null) {
                Matcher fn = FILENAME_PATTERN.matcher(outputBlock);
                if (fn.find()) return fn.group(1);
            }
        }
        return DEFAULT_FILENAME_PATTERN;
    }

    private static List<Path> listWebpackConfigs(Path root, List<String> warnings) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> {
                        for (Path segment : root.relativize(p)) {
                            if (EXCLUDED_DIRS.contains(segment.toString())) return false;
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
                    .filter(p -> WEBPACK_CONFIG_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            warnings.add("Could not walk " + root + " for a webpack configuration: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * If a {@code //} or {@code /*} comment starts at {@code i} (outside a string — the caller's
     * concern), the index of its last character; otherwise -1. Without this, a comment's apostrophe
     * or parenthesis unbalances every character-level scan that crosses it.
     */
    static int commentEnd(String text, int i) {
        if (text.charAt(i) != '/' || i + 1 >= text.length()) return -1;
        char next = text.charAt(i + 1);
        if (next == '/') {
            int newline = text.indexOf('\n', i);
            return newline < 0 ? text.length() - 1 : newline - 1;
        }
        if (next == '*') {
            int end = text.indexOf("*/", i + 2);
            return end < 0 ? text.length() - 1 : end + 1;
        }
        return -1;
    }

    /** A view's bundle binding: its entry module and that entry's closure — or why there is none. */
    record BoundView(Path frontendRoot, Path entry, List<Path> closure, String unboundReason) {
        boolean bound() { return unboundReason == null; }
    }

    private static final Map<Path, Map<String, Path>> REGISTRIES = new ConcurrentHashMap<>();

    /**
     * Binds one view to its bundle, exactly as {@link #bindApiCalls} does — shared by the later
     * phases that read a view's JavaScript (identifier correlation, the per-page call-site report).
     */
    static BoundView bindView(Path jsp, Path webappRoot, JspPage page) {
        Path frontendRoot = locateFrontendRoot(webappRoot);
        if (frontendRoot == null) {
            return new BoundView(null, null, List.of(), "no webpack configuration found above the web application root");
        }
        Map<String, Path> registry = REGISTRIES.computeIfAbsent(frontendRoot,
                root -> discoverBundleEntries(root, new ArrayList<>()));
        List<Path> entries = matchingEntries(jsp, page, registry);
        if (entries.isEmpty()) {
            return new BoundView(frontendRoot, null, List.of(), "the view has no page-specific webpack bundle");
        }
        if (entries.size() > 1) {
            return new BoundView(frontendRoot, null, List.of(),
                    "the view references " + entries.size() + " webpack bundles — ambiguous");
        }
        return new BoundView(frontendRoot, entries.get(0), closureOf(entries.get(0), new ArrayList<>()), null);
    }

    /** The nearest ancestor of {@code webappRoot} (itself included) holding a webpack configuration file, or null. */
    static Path locateFrontendRoot(Path webappRoot) {
        Path dir = webappRoot == null ? null : webappRoot.toAbsolutePath().normalize();
        for (int depth = 0; dir != null && depth < 8; depth++, dir = dir.getParent()) {
            try (Stream<Path> children = Files.list(dir)) {
                boolean hasConfig = children.anyMatch(p -> Files.isRegularFile(p)
                        && WEBPACK_CONFIG_NAME.matcher(p.getFileName().toString()).matches());
                if (hasConfig) return dir;
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Module closure + HTTP call extraction
    // -----------------------------------------------------------------------

    private List<HttpCallInfo> extractHttpCallsFromClosure(Path entryFile, Path frontendRoot, List<String> warnings) {
        List<HttpCallInfo> calls = new ArrayList<>();
        for (Path file : closureOf(entryFile, warnings)) {
            String content = readOrWarn(file, warnings);
            if (content != null) calls.addAll(extractHttpCalls(content));
        }
        return calls;
    }

    /** The entry file and every application module it transitively imports, in discovery order. */
    static List<Path> closureOf(Path entryFile, List<String> warnings) {
        Set<Path> visited = new LinkedHashSet<>();
        Deque<Path> queue = new ArrayDeque<>();
        queue.add(entryFile);

        while (!queue.isEmpty() && visited.size() < MAX_CLOSURE_FILES) {
            Path file = queue.poll();
            if (!visited.add(file)) continue; // already visited — this is what terminates a cycle
            String content = readOrWarn(file, warnings);
            if (content == null) continue;

            Matcher m = IMPORT_SPEC.matcher(content);
            while (m.find()) {
                String spec = m.group(1) != null ? m.group(1) : m.group(2);
                if (spec.startsWith(".") || spec.startsWith("/")) {
                    Path resolved = resolveRelative(file.getParent(), spec);
                    if (resolved != null && !visited.contains(resolved)) queue.add(resolved);
                }
                // A bare specifier (no leading '.' or '/') names an npm package — external,
                // not part of this application's own module graph.
            }
        }
        return new ArrayList<>(visited);
    }

    private static List<HttpCallInfo> extractHttpCalls(String content) {
        return locateHttpCalls(content).stream().map(LocatedCall::call).collect(Collectors.toList());
    }

    /** One HTTP call site: the call itself, where its callee starts, and its argument span. */
    record LocatedCall(HttpCallInfo call, int start, int argsStart, int argsEnd) {
        boolean argsContain(int offset) { return offset >= argsStart && offset < argsEnd; }
        String descriptor() { return call.getHttpVerb() + " " + call.getUrlTemplate(); }
    }

    /**
     * Every jQuery/fetch HTTP call site in {@code content}, located: Phase 06 needs to know which
     * function owns a call, and which statements sit inside its argument object (a success
     * callback populating a control reveals that control's data source).
     */
    static List<LocatedCall> locateHttpCalls(String content) {
        List<LocatedCall> calls = new ArrayList<>();
        for (JQueryAjaxCallExtractor.CallSite site : new JQueryAjaxCallExtractor().extract(content)) {
            if (!site.extracted()) continue; // reported in the page document (JspAjaxCallReport), never dropped
            for (String template : site.urlTemplates()) {
                calls.add(new LocatedCall(new HttpCallInfo(site.function(), site.verb(), template, "any", null),
                        site.start(), site.argsStart(), site.argsEnd()));
            }
        }
        return calls;
    }

    /**
     * A function body: {@code bodyStart} just past its '{', {@code bodyEnd} at its matching '}'.
     * {@code name} is null for an anonymous function (an inline handler, a callback).
     */
    record FunctionSpan(String name, int bodyStart, int bodyEnd) {
        boolean contains(int offset) { return offset >= bodyStart && offset < bodyEnd; }
    }

    /**
     * Every brace-bodied function in {@code content}, named and anonymous. Named ones come from
     * the declaration shapes hand-written jQuery code actually uses (declarations, object-literal
     * members, method shorthand, assigned function/arrow expressions). A jQuery/promise callback
     * key ({@code success: function}) is deliberately kept anonymous: code inside it belongs to
     * the function whose request it handles, not to a function called "success".
     */
    static List<FunctionSpan> functionSpans(String content) {
        Map<Integer, FunctionSpan> byBodyStart = new TreeMap<>();
        Matcher named = FUNCTION_START.matcher(content);
        while (named.find()) {
            String name = null;
            for (int g = 1; g <= named.groupCount() && name == null; g++) name = named.group(g);
            if (name == null || NOT_FUNCTION_NAMES.contains(name)) continue;
            if (CALLBACK_KEYS.contains(name)) name = null;
            FunctionSpan span = spanFromParams(content, named.end() - 1, name);
            if (span != null) byBodyStart.putIfAbsent(span.bodyStart(), span);
        }
        Matcher anon = ANON_FUNCTION_START.matcher(content);
        while (anon.find()) {
            FunctionSpan span = anon.group().endsWith("(")
                    ? spanFromParams(content, anon.end() - 1, null)
                    : spanFromBody(content, anon.end(), null);
            if (span != null) byBodyStart.putIfAbsent(span.bodyStart(), span);
        }
        return new ArrayList<>(byBodyStart.values());
    }

    private static FunctionSpan spanFromParams(String content, int openParen, String name) {
        String params = extractBalanced(content, openParen, '(', ')');
        if (params == null) return null;
        int i = skipWhitespace(content, openParen + params.length() + 2);
        if (content.startsWith("=>", i)) i += 2;
        return spanFromBody(content, i, name);
    }

    private static FunctionSpan spanFromBody(String content, int from, String name) {
        int i = skipWhitespace(content, from);
        if (i >= content.length() || content.charAt(i) != '{') return null; // expression-bodied arrow
        String body = extractBalanced(content, i, '{', '}');
        return body == null ? null : new FunctionSpan(name, i + 1, i + 1 + body.length());
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** The innermost function (named or anonymous) whose body contains {@code offset}; null at top level. */
    static FunctionSpan innermost(List<FunctionSpan> spans, int offset) {
        FunctionSpan best = null;
        for (FunctionSpan s : spans) {
            if (s.contains(offset) && (best == null || s.bodyStart() > best.bodyStart())) best = s;
        }
        return best;
    }

    /** The innermost <em>named</em> function whose body contains {@code offset}; null if none. */
    static FunctionSpan innermostNamed(List<FunctionSpan> spans, int offset) {
        FunctionSpan best = null;
        for (FunctionSpan s : spans) {
            if (s.name() != null && s.contains(offset) && (best == null || s.bodyStart() > best.bodyStart())) best = s;
        }
        return best;
    }

    /**
     * A URL built by {@code +}-concatenating string literals, template literals, and expressions
     * (variables, member access, calls). Literal segments are kept verbatim; every non-literal
     * segment becomes {@code {param}}. A URL with no literal segment at all (entirely dynamic, a
     * bare variable) carries no path information worth recording, and is dropped ({@code null}).
     */
    static String normalizeUrlExpr(String expr) {
        if (expr == null || expr.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        boolean anyLiteral = false;
        for (String raw : expr.split("\\+")) {
            String p = raw.strip();
            if (p.isEmpty()) continue;
            if (isQuoted(p, '\'') || isQuoted(p, '"')) {
                sb.append(p, 1, p.length() - 1);
                anyLiteral = true;
            } else if (isQuoted(p, '`')) {
                String inner = TEMPLATE_VAR.matcher(p.substring(1, p.length() - 1)).replaceAll("{param}");
                sb.append(inner);
                if (inner.contains("/")) anyLiteral = true;
            } else if (CONTEXT_PATH_IDENTIFIERS.contains(p)) {
                // Contributes nothing — see CONTEXT_PATH_IDENTIFIERS.
            } else {
                sb.append("{param}");
            }
        }
        if (!anyLiteral) return null;
        String result = sb.toString().replaceAll("/{2,}", "/");
        if (!result.startsWith("/")) result = "/" + result;
        return result;
    }

    private static boolean isQuoted(String s, char quote) {
        return s.length() >= 2 && s.charAt(0) == quote && s.charAt(s.length() - 1) == quote;
    }

    // -----------------------------------------------------------------------
    // Balanced-delimiter scanning
    // -----------------------------------------------------------------------

    /**
     * The text strictly between the delimiter at {@code openIdx} and its matching close,
     * tracking string/template literals (so a delimiter inside a quote is never counted) and all
     * three bracket kinds (so {@code {..}}/{@code [..]} nested inside the scanned {@code (..)}
     * don't prematurely close it, and vice versa). {@code null} if unbalanced before EOF.
     */
    static String extractBalanced(String text, int openIdx, char open, char close) {
        int depth = 0;
        char quote = 0;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') { quote = c; continue; }
            int comment = commentEnd(text, i);
            if (comment >= 0) { i = comment; continue; } // an apostrophe in "// l'étape" is not a string
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0 && c == close) return text.substring(openIdx + 1, i);
                if (depth == 0) return null; // mismatched delimiter — malformed input
            }
        }
        return null;
    }

    /**
     * The expression starting at {@code start}, up to the first top-level comma or the end of the
     * enclosing construct (an unmatched closing bracket) — i.e. one object-literal value, or one
     * call argument. Nested calls/arrays/objects and quoted commas are not top-level.
     */
    static String extractValueExpr(String text, int start) {
        int depth = 0;
        char quote = 0;
        int i = start;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') { quote = c; continue; }
            int comment = commentEnd(text, i);
            if (comment >= 0) { i = comment; continue; }
            if (c == '(' || c == '[' || c == '{') { depth++; continue; }
            if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break;
                depth--;
                continue;
            }
            if (c == ',' && depth == 0) break;
        }
        return text.substring(start, i).strip();
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /** Resolves a relative/absolute module specifier to an existing file, trying JS's usual suffixes. */
    private static Path resolveRelative(Path baseDir, String spec) {
        Path candidate = spec.startsWith("/") ? baseDir.resolve(spec.substring(1)) : baseDir.resolve(spec);
        candidate = candidate.normalize();
        for (String suffix : JS_RESOLUTION_SUFFIXES) {
            Path withSuffix = Path.of(candidate + suffix);
            if (Files.isRegularFile(withSuffix)) return withSuffix.toAbsolutePath().normalize();
        }
        return null;
    }

    private static Path safeExistingFile(String filePath) {
        if (filePath == null || JspRouteReconstructor.UNRESOLVED_FILE.equals(filePath)) return null;
        try {
            Path p = Path.of(filePath);
            return Files.isRegularFile(p) ? p.toAbsolutePath().normalize() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readOrWarn(Path file, List<String> warnings) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String rel(Path p, Path root) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString().replace('\\', '/');
        }
    }
}
