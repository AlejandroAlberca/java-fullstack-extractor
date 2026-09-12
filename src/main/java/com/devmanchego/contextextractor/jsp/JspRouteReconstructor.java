package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.model.RouteOrigin;
import com.devmanchego.contextextractor.java.security.PreAuthorizeExpressionParser;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reconstructs the JSP + jQuery application's sitemap by merging independent, partial sources —
 * no single one is complete on its own:
 *
 * <ol>
 *   <li>Links ({@code <a href>}) — the navigation menu's labels, hierarchy and authorisation,
 *       plus every other page's own links.</li>
 *   <li>Every other internal URL reference — markup attributes, {@code <jsp:forward>} targets,
 *       and script string literals. A script literal only counts as a page when its first path
 *       segment is one the links use (in an application with no links at all: when it maps
 *       straight to a JSP view) — in a jQuery codebase AJAX endpoint literals vastly outnumber
 *       page URLs, and some share a JSP view's name.</li>
 *   <li>Client-side state-dispatch functions — a {@code switch} over workflow-state constants
 *       whose branches navigate to a page is a state transition graph, not just a route list.</li>
 *   <li>{@code web.xml} — error pages and the welcome file, never linked from the UI.</li>
 *   <li>A naming convention resolving each URL to its backing JSP file, with a directory-index
 *       fallback flagged as inferred — the one join not backed by a literal source.</li>
 * </ol>
 *
 * <p>Every route node carries a {@link RouteOrigin}: which sources produced it, the permissions
 * gating its menu entry, the states that dispatch to it, and whether its view mapping is exact.
 * Where two sources disagree, the more declarative one wins and the disagreement is surfaced as
 * a warning. A URL with no backing file is kept (it is a real address) and flagged unresolved.
 * JSP views that no source reaches are placed under a final {@value #UNREFERENCED_SECTION}
 * section rather than omitted, so every view is accounted for in the tree itself.
 *
 * <p>Produces the shared {@link RouteNode} tree and a matching minimal {@link ComponentInfo}
 * map (synthetic name → JSP file path), so the existing sitemap and page renderers can render
 * this framework's pages.
 */
public final class JspRouteReconstructor {

    private static final Logger log = LoggerFactory.getLogger(JspRouteReconstructor.class);

    /** Title of the synthetic top-level section listing JSP views no source reached. */
    public static final String UNREFERENCED_SECTION = "Unreferenced views";
    /** {@code ComponentInfo.filePath} of a route no JSP file could be mapped to. */
    public static final String UNRESOLVED_FILE = "(unresolved)";

    // Source kinds, as shown in each route's provenance.
    static final String SRC_LINK = "link";
    static final String SRC_MARKUP = "markup reference";
    static final String SRC_FORWARD = "jsp:forward";
    static final String SRC_SCRIPT = "script literal";
    static final String SRC_DISPATCH = "state dispatch";
    static final String SRC_ERROR_PAGE = "web.xml error-page";
    static final String SRC_WELCOME = "welcome file";

    // Any quoted absolute-path literal of at least one character past the slash (a bare '/' is
    // string concatenation, not a URL). Whether it names a page is decided by isPageUrl().
    // '?' is admitted because '/view/x?' + params is a real observed shape; normalizeUrl()
    // strips the query afterwards.
    private static final Pattern PATH_LITERAL = Pattern.compile("['\"](/[\\w.-][\\w/.?=&-]*)['\"]");
    // A quoted case label, a constant case label (Etat.SAISIE), or default.
    private static final Pattern CASE_OR_DEFAULT = Pattern.compile(
            "\\bcase\\s*(?:[\"']([\\w.]+)[\"']|([A-Za-z_$][\\w.$]*))\\s*:|(\\bdefault\\s*:)");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern ERROR_PAGE_BLOCK = Pattern.compile("<error-page>(.*?)</error-page>", Pattern.DOTALL);
    private static final Pattern ERROR_CODE = Pattern.compile("<error-code>\\s*(\\d+)\\s*</error-code>");
    private static final Pattern EXCEPTION_TYPE = Pattern.compile("<exception-type>\\s*([^<\\s]+)\\s*</exception-type>");
    private static final Pattern LOCATION = Pattern.compile("<location>\\s*([^<\\s]+)\\s*</location>");
    private static final Pattern WELCOME_FILE = Pattern.compile("<welcome-file>\\s*([^<\\s]+)\\s*</welcome-file>");
    /** The servlet specification's default welcome files, applied when web.xml declares none. */
    private static final List<String> DEFAULT_WELCOME_FILES = List.of("index.html", "index.htm", "index.jsp");

    private static final Set<String> STATIC_ASSET_EXTENSIONS = Set.of(
            ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
            ".woff", ".woff2", ".ttf", ".eot", ".map", ".pdf");
    private static final Set<String> EXCLUDED_JS_DIRS = Set.of("node_modules", "dist", "build", ".git", "target");

    // Which kind of source supplied a URL's label, breadcrumb and permissions — higher wins.
    private static final int RANK_WEB_XML = 1;
    private static final int RANK_FLAT_LINK = 2;
    private static final int RANK_MENU_LINK = 3;

    private final JspFileParser jspParser = new JspFileParser();

    /**
     * @param routes                the reconstructed route tree, ending with the
     *                              {@value #UNREFERENCED_SECTION} section when any view is unreached
     * @param componentsByName      synthetic component name → minimal {@link ComponentInfo}
     *                              (name and JSP file path; {@value #UNRESOLVED_FILE} when unmapped)
     * @param triggeringStatesByUrl workflow-state constants whose dispatch function routes here
     * @param permissionsByUrl      permission subjects gating this URL's link, empty when ungated
     * @param originsByUrl          the {@link RouteOrigin} attached to each URL's route node
     * @param unreferencedViews     JSP files reached by no source (also placed in the tree)
     * @param warnings              source conflicts, inferred or unresolved mappings, name
     *                              collisions, and unreferenced views
     */
    public record Result(
            List<RouteNode> routes,
            Map<String, ComponentInfo> componentsByName,
            Map<String, Set<String>> triggeringStatesByUrl,
            Map<String, Set<String>> permissionsByUrl,
            Map<String, RouteOrigin> originsByUrl,
            List<Path> unreferencedViews,
            List<String> warnings) {

        public Result {
            routes = List.copyOf(routes);
            componentsByName = Map.copyOf(componentsByName);
            triggeringStatesByUrl = Map.copyOf(triggeringStatesByUrl);
            permissionsByUrl = Map.copyOf(permissionsByUrl);
            originsByUrl = Map.copyOf(originsByUrl);
            unreferencedViews = List.copyOf(unreferencedViews);
            warnings = List.copyOf(warnings);
        }
    }

    /** One URL's accumulated evidence across every source, before it's placed in the tree. */
    private static final class Draft {
        final String url;
        String label;                        // null → the URL itself is shown
        List<String> breadcrumb = List.of();
        Set<String> permissions = Set.of();
        int labelRank;                       // RANK_* of the source that supplied the three above
        Path labelFrom;
        final Set<String> states = new LinkedHashSet<>();
        final Map<String, Set<String>> whereByKind = new LinkedHashMap<>();

        Draft(String url) { this.url = url; }
    }

    private record ResolvedView(Path file, String inferenceNote) {}

    /**
     * @param frontendProjectPath the frontend project root (JSP views under {@code webappRoot},
     *                            and the JavaScript layer, typically as sibling trees)
     * @param webappRoot          directory directly containing {@code WEB-INF} — see
     *                            {@link JspFileParser#locateWebappRoot(Path)}
     */
    public Result reconstruct(Path frontendProjectPath, Path webappRoot) {
        List<String> warnings = new ArrayList<>();
        Path webapp = webappRoot.toAbsolutePath().normalize();
        Path frontendRoot = frontendProjectPath.toAbsolutePath().normalize();

        // Every JSP is parsed exactly once, and shared by every source below.
        List<Path> allJspFiles = listJspFiles(webapp, warnings);
        Map<Path, JspPage> pages = parseAll(allJspFiles, webapp, warnings);
        Set<Path> everIncluded = new LinkedHashSet<>();
        pages.values().forEach(p -> everIncluded.addAll(p.includedFiles()));

        String webXml = readWebXml(webapp, warnings);
        List<String> declaredWelcomeFiles = declaredWelcomeFiles(webXml);
        List<String> welcomeFiles = declaredWelcomeFiles.isEmpty() ? DEFAULT_WELCOME_FILES : declaredWelcomeFiles;

        Map<String, Draft> drafts = new LinkedHashMap<>();

        // Source 1: links — labels, hierarchy, authorisation.
        harvestLinks(pages, webapp, drafts, warnings);
        Set<String> navigationPrefixes = drafts.keySet().stream()
                .map(JspRouteReconstructor::firstSegment).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Predicate<String> isPageUrl = url -> isPageUrl(url, navigationPrefixes, webapp);

        // Source 2: every other URL reference in markup and scripts.
        harvestMarkupReferences(pages, webapp, drafts, everIncluded);
        Map<Path, String> scripts = readScripts(frontendRoot, warnings);
        harvestScriptLiterals(scripts, frontendRoot, drafts, isPageUrl);

        // Source 3: client-side state-dispatch functions.
        harvestStateDispatch(scripts, frontendRoot, drafts, isPageUrl);

        // Source 4: web.xml — error pages and the welcome file.
        harvestWebXml(webXml, declaredWelcomeFiles, webapp, drafts, warnings);

        // Source 5: resolve each URL to its JSP file while assembling the tree.
        Map<String, ComponentInfo> componentsByName = new LinkedHashMap<>();
        Map<String, RouteOrigin> originsByUrl = new LinkedHashMap<>();
        List<RouteNode> routes = new ArrayList<>(buildTree(new ArrayList<>(drafts.values()), 0, webapp,
                welcomeFiles, componentsByName, originsByUrl, warnings));

        List<Path> unreferenced = findUnreferencedViews(allJspFiles, componentsByName, everIncluded);
        if (!unreferenced.isEmpty()) {
            routes.add(unreferencedSection(unreferenced, webapp, componentsByName, warnings));
        }

        Map<String, Set<String>> statesByUrl = new LinkedHashMap<>();
        Map<String, Set<String>> permissionsByUrl = new LinkedHashMap<>();
        for (Draft d : drafts.values()) {
            if (!d.states.isEmpty()) statesByUrl.put(d.url, d.states);
            if (!d.permissions.isEmpty()) permissionsByUrl.put(d.url, d.permissions);
        }

        log.info("JspRouteReconstructor: {} route(s) ({} via state dispatch), {} unreferenced view(s), {} warning(s).",
                drafts.size(), statesByUrl.size(), unreferenced.size(), warnings.size());

        return new Result(routes, componentsByName, statesByUrl, permissionsByUrl, originsByUrl, unreferenced, warnings);
    }

    // -----------------------------------------------------------------------
    // Source 1: links
    // -----------------------------------------------------------------------

    private void harvestLinks(Map<Path, JspPage> pages, Path webapp, Map<String, Draft> drafts, List<String> warnings) {
        pages.forEach((file, page) -> {
            for (Element a : page.document().select("a[href]")) {
                // Only this file's own anchors — an included fragment's anchors are harvested
                // once, when that fragment is itself visited.
                if (!file.equals(page.sourceFileOf(a))) continue;
                String url = toRouteUrl(a.attr("href"));
                if (url == null) continue;

                Draft d = drafts.computeIfAbsent(url, Draft::new);
                addSource(d, SRC_LINK, rel(file, webapp));
                String label = a.text().strip();
                if (label.isEmpty()) continue; // e.g. a logo link: a real reference, but no name

                List<String> crumb = sectionBreadcrumb(a);
                offerLabel(d, label, crumb, authorizationOf(a),
                        crumb.isEmpty() ? RANK_FLAT_LINK : RANK_MENU_LINK, file, webapp, warnings);
            }
        });
    }

    /**
     * Offers a label (with its breadcrumb and permissions) for a URL. A link nested under a
     * labelled menu section beats a flat mention — real navigation is structured that way, a
     * stray inline link elsewhere isn't — and any link beats web.xml, regardless of the order
     * they're found in. Two different labels at the same rank keep the first (stable, sorted
     * file order) and warn; two web.xml error-page labels for one location are combined.
     */
    private static void offerLabel(Draft d, String label, List<String> crumb, Set<String> permissions,
                                   int rank, Path from, Path webapp, List<String> warnings) {
        if (rank > d.labelRank) {
            d.label = label;
            d.breadcrumb = crumb;
            d.permissions = permissions;
            d.labelRank = rank;
            d.labelFrom = from;
            return;
        }
        if (label.equals(d.label)) return;
        if (rank == RANK_WEB_XML && d.labelRank == RANK_WEB_XML) {
            d.label = d.label + " / " + label;
        } else if (rank == RANK_WEB_XML) {
            warnings.add("web.xml declares '" + d.url + "' (" + label + "), which the navigation also links to as '"
                    + d.label + "' — keeping the link's entry.");
        } else if (rank == d.labelRank) {
            warnings.add("URL '" + d.url + "' is linked with two different labels — '" + d.label + "' ("
                    + rel(d.labelFrom, webapp) + ") and '" + label + "' (" + rel(from, webapp)
                    + ") — keeping the first one found.");
        }
        // rank < d.labelRank: a flat mention of a URL the menu already places — expected precedence.
    }

    /**
     * Walks up from an anchor through ancestor {@code <li>} elements, collecting each one's own
     * direct-child link text (outermost first) — the section labels a nested nav link sits under.
     */
    private static List<String> sectionBreadcrumb(Element anchor) {
        List<String> labels = new ArrayList<>();
        Element current = anchor.parent();
        while (current != null) {
            if ("li".equalsIgnoreCase(current.tagName())) {
                Element directLink = current.children().stream()
                        .filter(e -> "a".equalsIgnoreCase(e.tagName()))
                        .findFirst().orElse(null);
                if (directLink != null && directLink != anchor && !directLink.text().isBlank()) {
                    labels.add(0, directLink.text().strip());
                }
            }
            current = current.parent();
        }
        return labels;
    }

    /**
     * Every permission gating this anchor — the union of every enclosing
     * {@code <sec:authorize access="...">}. JSP nesting is AND semantics (all enclosing
     * conditions must hold to render), so a flat union is "every permission required".
     */
    private static Set<String> authorizationOf(Element anchor) {
        Set<String> permissions = new LinkedHashSet<>();
        Element current = anchor.parent();
        while (current != null) {
            if ("sec:authorize".equalsIgnoreCase(current.tagName())) {
                String access = current.attr("access");
                if (!access.isBlank()) permissions.addAll(PreAuthorizeExpressionParser.parse(access).roles());
            }
            current = current.parent();
        }
        return permissions;
    }

    // -----------------------------------------------------------------------
    // Source 2: markup references, forwards, dynamic includes, script literals
    // -----------------------------------------------------------------------

    private void harvestMarkupReferences(Map<Path, JspPage> pages, Path webapp, Map<String, Draft> drafts,
                                         Set<Path> everIncluded) {
        pages.forEach((file, page) -> {
            for (Element el : page.document().getAllElements()) {
                if (!file.equals(page.sourceFileOf(el))) continue;
                String tag = el.tagName().toLowerCase(Locale.ROOT);
                switch (tag) {
                    case "a" -> { } // Source 1 owns every <a href>
                    case "jsp:forward" -> {
                        String url = toRouteUrl(el.attr("page"));
                        if (url != null) addSource(drafts.computeIfAbsent(url, Draft::new), SRC_FORWARD, rel(file, webapp));
                    }
                    case "jsp:include" -> {
                        // Not composed (Phase 01 only splices static includes), but its target is
                        // still a fragment in use — never an unreferenced view.
                        Path target = resolveIncludeTarget(el.attr("page"), file, webapp);
                        if (target != null) everIncluded.add(target);
                    }
                    default -> {
                        for (String attr : List.of("href", "src", "action")) {
                            String url = toRouteUrl(el.attr(attr));
                            if (url != null) addSource(drafts.computeIfAbsent(url, Draft::new), SRC_MARKUP, rel(file, webapp));
                        }
                    }
                }
            }
        });
    }

    private static Path resolveIncludeTarget(String page, Path includingFile, Path webapp) {
        if (page == null || page.isBlank() || page.contains("${")) return null;
        String p = page.strip();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        Path target = (p.startsWith("/") ? webapp.resolve(p.substring(1)) : includingFile.getParent().resolve(p)).normalize();
        return Files.isRegularFile(target) ? target : null;
    }

    private void harvestScriptLiterals(Map<Path, String> scripts, Path frontendRoot, Map<String, Draft> drafts,
                                       Predicate<String> isPageUrl) {
        scripts.forEach((js, content) -> {
            Matcher m = PATH_LITERAL.matcher(content);
            while (m.find()) {
                String url = scriptPageUrl(m.group(1), isPageUrl);
                if (url != null) addSource(drafts.computeIfAbsent(url, Draft::new), SRC_SCRIPT, rel(js, frontendRoot));
            }
        });
    }

    /**
     * Whether a script literal names a page rather than an AJAX endpoint: its first path segment
     * is one the application's own links use (the page URL space, e.g. {@code /view}). Mapping to
     * a JSP view by name is not evidence on its own — a REST endpoint such as
     * {@code /reporting/historique/{id}} can share a view's name ({@code reporting/historique.jsp})
     * — so it is only consulted when the application has no links to define that space at all.
     */
    private boolean isPageUrl(String url, Set<String> navigationPrefixes, Path webapp) {
        if (!navigationPrefixes.isEmpty()) return navigationPrefixes.contains(firstSegment(url));
        ResolvedView view = resolveView(url, webapp, List.of());
        return view != null && view.inferenceNote() == null;
    }

    private static String scriptPageUrl(String literal, Predicate<String> isPageUrl) {
        String url = toRouteUrl(literal);
        return url != null && isPageUrl.test(url) ? url : null;
    }

    // -----------------------------------------------------------------------
    // Source 3: client-side state-dispatch functions
    // -----------------------------------------------------------------------

    /**
     * Finds {@code switch}-shaped dispatch functions — a run of {@code case STATE:} labels
     * (fall-through accumulated) whose shared body contains a page URL literal — and records the
     * states on that URL. A regex scan, not a JavaScript parse: enough for this shape.
     */
    private void harvestStateDispatch(Map<Path, String> scripts, Path frontendRoot, Map<String, Draft> drafts,
                                      Predicate<String> isPageUrl) {
        scripts.forEach((js, content) -> {
            String where = rel(js, frontendRoot);
            Matcher m = CASE_OR_DEFAULT.matcher(content);
            List<String> pending = new ArrayList<>();
            int cursor = 0;
            while (m.find()) {
                attributeSegment(content.substring(cursor, m.start()), pending, where, drafts, isPageUrl);
                String label = m.group(1) != null ? m.group(1) : m.group(2);
                if (label != null) pending.add(label);
                else pending.clear(); // `default:` carries no named state
                cursor = m.end();
            }
            attributeSegment(content.substring(cursor), pending, where, drafts, isPageUrl);
        });
    }

    private void attributeSegment(String segment, List<String> pending, String where, Map<String, Draft> drafts,
                                  Predicate<String> isPageUrl) {
        if (pending.isEmpty()) return;
        Matcher urlM = PATH_LITERAL.matcher(segment);
        while (urlM.find()) {
            String url = scriptPageUrl(urlM.group(1), isPageUrl);
            if (url == null) continue;
            Draft d = drafts.computeIfAbsent(url, Draft::new);
            d.states.addAll(pending);
            addSource(d, SRC_DISPATCH, where);
            pending.clear();
            return;
        }
        // No page destination in this batch's body — drop it rather than misattribute these
        // states to whatever URL the next batch happens to find.
        if (segment.contains("break") || segment.contains("return")) pending.clear();
    }

    // -----------------------------------------------------------------------
    // Source 4: web.xml
    // -----------------------------------------------------------------------

    private static String readWebXml(Path webapp, List<String> warnings) {
        Path webXml = webapp.resolve("WEB-INF/web.xml");
        if (!Files.isRegularFile(webXml)) return null;
        String content = readOrWarn(webXml, warnings);
        return content == null ? null : XML_COMMENT.matcher(content).replaceAll("");
    }

    private static List<String> declaredWelcomeFiles(String webXml) {
        List<String> files = new ArrayList<>();
        if (webXml == null) return files;
        Matcher m = WELCOME_FILE.matcher(webXml);
        while (m.find()) files.add(m.group(1).strip());
        return files;
    }

    private void harvestWebXml(String webXml, List<String> declaredWelcomeFiles, Path webapp,
                               Map<String, Draft> drafts, List<String> warnings) {
        Path webXmlPath = webapp.resolve("WEB-INF/web.xml");
        if (webXml != null) {
            // Block by block, children in any order: an error-page is keyed by an error code, an
            // exception type, or neither (the container-wide default error page).
            Matcher block = ERROR_PAGE_BLOCK.matcher(webXml);
            while (block.find()) {
                String body = block.group(1);
                Matcher location = LOCATION.matcher(body);
                if (!location.find()) continue;
                String url = toRouteUrl(location.group(1));
                if (url == null) continue;

                Matcher code = ERROR_CODE.matcher(body);
                Matcher exception = EXCEPTION_TYPE.matcher(body);
                String label = code.find() ? "Error " + code.group(1)
                        : exception.find() ? "Error: " + simpleName(exception.group(1))
                        : "Error (default)";
                Draft d = drafts.computeIfAbsent(url, Draft::new);
                addSource(d, SRC_ERROR_PAGE, label);
                offerLabel(d, label, List.of("Errors"), Set.of(), RANK_WEB_XML, webXmlPath, webapp, warnings);
            }
        }

        // The welcome file answers the application root URL "/", not a URL of its own name.
        if (!declaredWelcomeFiles.isEmpty()) {
            Draft d = drafts.computeIfAbsent("/", Draft::new);
            addSource(d, SRC_WELCOME, "WEB-INF/web.xml: " + String.join(", ", declaredWelcomeFiles));
            offerLabel(d, "Welcome page", List.of(), Set.of(), RANK_WEB_XML, webXmlPath, webapp, warnings);
        } else {
            for (String wf : DEFAULT_WELCOME_FILES) {
                if (!Files.isRegularFile(webapp.resolve(wf))) continue;
                Draft d = drafts.computeIfAbsent("/", Draft::new);
                addSource(d, SRC_WELCOME, "servlet default: " + wf);
                offerLabel(d, "Welcome page", List.of(), Set.of(), RANK_WEB_XML, webapp.resolve(wf), webapp, warnings);
                break;
            }
        }
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    // -----------------------------------------------------------------------
    // Source 5: URL -> JSP file resolution
    // -----------------------------------------------------------------------

    /**
     * {@code /view/achat/fap} → {@code WEB-INF/jsp/achat/fap.jsp} (last segment names the file,
     * the rest are directories; a leading {@code view} segment is stripped first). Falls back to
     * the last segment as both directory and file ({@code /view/recherche} → {@code
     * recherche/recherche.jsp}), flagged inferred. A URL that already names a {@code .jsp}
     * (a web.xml location, a forward) resolves against the webapp root, exactly. The root URL
     * {@code /} resolves to the first existing welcome file — at the webapp root exactly, or
     * under {@code WEB-INF/jsp} (served through a view resolver) as inferred.
     *
     * <p>Deliberately tries nothing further (e.g. plural/singular directory names): guessing
     * further would trade an honest "unresolved" for a confident wrong answer.
     */
    private static ResolvedView resolveView(String url, Path webapp, List<String> welcomeFiles) {
        Path jspRoot = webapp.resolve("WEB-INF/jsp");
        if ("/".equals(url)) {
            for (String wf : welcomeFiles) {
                if (Files.isRegularFile(webapp.resolve(wf))) return new ResolvedView(webapp.resolve(wf), null);
                if (Files.isRegularFile(jspRoot.resolve(wf))) {
                    return new ResolvedView(jspRoot.resolve(wf), "inferred — welcome file '" + wf
                            + "' found under WEB-INF/jsp (served through a view resolver, not directly)");
                }
            }
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jsp") || lower.endsWith(".jspx")) {
            Path direct = webapp.resolve(url.substring(1)).normalize();
            return Files.isRegularFile(direct) ? new ResolvedView(direct, null) : null;
        }

        List<String> segments = Arrays.stream(url.split("/")).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if ("view".equals(segments.get(0)) && segments.size() > 1) segments = segments.subList(1, segments.size());
        String last = segments.get(segments.size() - 1);
        String dirs = String.join("/", segments.subList(0, segments.size() - 1));

        Path direct = (dirs.isEmpty() ? jspRoot : jspRoot.resolve(dirs)).resolve(last + ".jsp");
        if (Files.isRegularFile(direct)) return new ResolvedView(direct, null);

        Path fallback = jspRoot.resolve(String.join("/", segments)).resolve(last + ".jsp");
        if (Files.isRegularFile(fallback)) {
            return new ResolvedView(fallback, "inferred — the directory-index fallback, not the direct naming convention");
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Tree assembly
    // -----------------------------------------------------------------------

    private List<RouteNode> buildTree(List<Draft> drafts, int depth, Path webapp, List<String> welcomeFiles,
                                      Map<String, ComponentInfo> componentsByName,
                                      Map<String, RouteOrigin> originsByUrl, List<String> warnings) {
        Map<String, List<Draft>> bySection = new LinkedHashMap<>();
        List<Draft> direct = new ArrayList<>();
        for (Draft d : drafts) {
            if (d.breadcrumb.size() > depth) {
                bySection.computeIfAbsent(d.breadcrumb.get(depth), k -> new ArrayList<>()).add(d);
            } else {
                direct.add(d);
            }
        }

        List<RouteNode> result = new ArrayList<>();
        bySection.forEach((sectionLabel, sectionDrafts) -> result.add(new RouteNode("", null, sectionLabel, null, false,
                buildTree(sectionDrafts, depth + 1, webapp, welcomeFiles, componentsByName, originsByUrl, warnings))));
        for (Draft d : direct) {
            result.add(buildLeaf(d, webapp, welcomeFiles, componentsByName, originsByUrl, warnings));
        }
        return result;
    }

    private RouteNode buildLeaf(Draft d, Path webapp, List<String> welcomeFiles,
                                Map<String, ComponentInfo> componentsByName,
                                Map<String, RouteOrigin> originsByUrl, List<String> warnings) {
        String componentName = uniqueComponentName(syntheticComponentName(d.url), d.url, componentsByName, warnings);
        ResolvedView resolved = resolveView(d.url, webapp, welcomeFiles);

        String filePath;
        String note;
        if (resolved != null) {
            filePath = resolved.file().toString();
            note = resolved.inferenceNote();
            if (note != null) {
                warnings.add("URL '" + d.url + "' resolved to " + rel(resolved.file(), webapp) + " — " + note
                        + " — treat this mapping as inferred.");
            }
        } else {
            filePath = UNRESOLVED_FILE;
            note = "unresolved — no JSP view matches this URL (typically a controller endpoint, redirect or logout handler)";
            warnings.add("URL '" + d.url + "' could not be resolved to a JSP file under WEB-INF/jsp "
                    + "(tried the direct convention and the directory-index fallback) — the route is "
                    + "kept, with no backing file.");
        }
        componentsByName.put(componentName, new ComponentInfo(componentName, filePath, null, List.of()));

        RouteOrigin origin = new RouteOrigin(formatSources(d), d.permissions, d.states, note);
        originsByUrl.put(d.url, origin);
        return new RouteNode(d.url, componentName, d.label != null ? d.label : d.url, null, false, List.of(), origin);
    }

    /** {@code /view/achat/fap} → {@code ViewAchatFapPage}; uniqueness is enforced by the caller. */
    private static String syntheticComponentName(String url) {
        String base = Arrays.stream(url.split("[/\\-_.]"))
                .filter(s -> !s.isEmpty())
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining());
        return (base.isEmpty() ? "Root" : base) + "Page";
    }

    /** Two URLs can collapse to one synthetic name ({@code /a-b} vs {@code /a/b}) — never share it. */
    private static String uniqueComponentName(String base, String owner, Map<String, ComponentInfo> componentsByName,
                                              List<String> warnings) {
        String name = base;
        int n = 2;
        while (componentsByName.containsKey(name)) {
            name = base.substring(0, base.length() - "Page".length()) + n++ + "Page";
        }
        if (!name.equals(base)) {
            warnings.add("'" + owner + "' maps to the same synthetic page name as another route ('" + base
                    + "') — named '" + name + "' instead.");
        }
        return name;
    }

    private static void addSource(Draft d, String kind, String where) {
        d.whereByKind.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(where);
    }

    /** {@code "link (WEB-INF/jsp/fragments/menu.jsp)"}, {@code "script literal (a.js, b.js +3 more)"}. */
    private static List<String> formatSources(Draft d) {
        List<String> out = new ArrayList<>();
        d.whereByKind.forEach((kind, where) -> {
            List<String> all = new ArrayList<>(where);
            String shown = String.join(", ", all.subList(0, Math.min(2, all.size())));
            if (all.size() > 2) shown += " +" + (all.size() - 2) + " more";
            out.add(kind + " (" + shown + ")");
        });
        return out;
    }

    // -----------------------------------------------------------------------
    // Reconciliation: every JSP view is placed in the tree, as a route or as unreferenced
    // -----------------------------------------------------------------------

    private static List<Path> findUnreferencedViews(List<Path> allJspFiles, Map<String, ComponentInfo> componentsByName,
                                                    Set<Path> everIncluded) {
        Set<Path> resolvedViewFiles = componentsByName.values().stream()
                .map(ComponentInfo::getFilePath)
                .filter(p -> !UNRESOLVED_FILE.equals(p))
                .map(Path::of)
                .collect(Collectors.toSet());
        return allJspFiles.stream()
                .filter(f -> !resolvedViewFiles.contains(f) && !everIncluded.contains(f))
                .collect(Collectors.toList());
    }

    private RouteNode unreferencedSection(List<Path> files, Path webapp, Map<String, ComponentInfo> componentsByName,
                                          List<String> warnings) {
        Path jspRoot = webapp.resolve("WEB-INF/jsp");
        RouteOrigin origin = new RouteOrigin(
                List.of("none — no link, URL literal, state dispatch, web.xml mapping, forward or include reaches this view"),
                Set.of(), Set.of(), null);
        List<RouteNode> leaves = new ArrayList<>();
        for (Path file : files) {
            String relPath = rel(file, webapp);
            String viewName = (file.startsWith(jspRoot) ? rel(file, jspRoot) : relPath).replaceFirst("\\.jspx?$", "");
            String name = uniqueComponentName(syntheticComponentName("/" + viewName), relPath, componentsByName, warnings);
            componentsByName.put(name, new ComponentInfo(name, file.toString(), null, List.of()));
            leaves.add(new RouteNode("(unreferenced) " + relPath, name, relPath, null, false, List.of(), origin));
            warnings.add("Unreferenced JSP view: " + relPath
                    + " — not reached by any harvested URL, state dispatch, web.xml mapping, forward, or include.");
        }
        return new RouteNode("", null, UNREFERENCED_SECTION, null, false, leaves);
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private static boolean isInternalUrl(String href) {
        if (href == null) return false;
        String h = href.strip();
        if (h.isEmpty() || h.equals("#")) return false;
        if (h.startsWith("javascript:") || h.startsWith("mailto:") || h.startsWith("tel:")) return false;
        if (h.startsWith("http://") || h.startsWith("https://") || h.startsWith("//")) return false;
        return h.startsWith("{contextPath}") || h.startsWith("/");
    }

    private static String normalizeUrl(String href) {
        String h = href.strip();
        if (h.startsWith("{contextPath}")) h = h.substring("{contextPath}".length());
        int q = h.indexOf('?');
        if (q >= 0) h = h.substring(0, q);
        int hash = h.indexOf('#');
        if (hash > 0) h = h.substring(0, hash);
        if (h.isEmpty()) h = "/";
        if (!h.startsWith("/")) h = "/" + h;
        if (h.length() > 1 && h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    /**
     * The normalised route URL of an internal, fully static, non-asset reference — or null.
     * A path still holding EL after context-path substitution ({@code /view/${type}/edit}) is
     * computed at runtime: no single route to name, so it's skipped rather than invented.
     */
    private static String toRouteUrl(String raw) {
        if (!isInternalUrl(raw)) return null;
        String url = normalizeUrl(raw);
        if (url.contains("${") || url.contains("{") || looksLikeStaticAsset(url)) return null;
        return url;
    }

    /** A JSP view URL is a virtual MVC path with no extension; a static asset always has one. */
    private static boolean looksLikeStaticAsset(String url) {
        int dot = url.lastIndexOf('.');
        int slash = url.lastIndexOf('/');
        if (dot <= slash) return false;
        return STATIC_ASSET_EXTENSIONS.contains(url.substring(dot).toLowerCase(Locale.ROOT));
    }

    private static String firstSegment(String url) {
        String s = url.startsWith("/") ? url.substring(1) : url;
        int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }

    private Map<Path, JspPage> parseAll(List<Path> files, Path webapp, List<String> warnings) {
        Map<Path, JspPage> pages = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                pages.put(file, jspParser.parse(file, webapp));
            } catch (Exception e) {
                warnings.add("Could not parse " + rel(file, webapp) + ": " + e.getMessage());
            }
        }
        return pages;
    }

    private static List<Path> listJspFiles(Path webappRoot, List<String> warnings) {
        if (!Files.isDirectory(webappRoot)) return List.of();
        try (Stream<Path> files = Files.walk(webappRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jsp"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            warnings.add("Could not walk " + webappRoot + " for JSP views: " + e.getMessage());
            return List.of();
        }
    }

    private static Map<Path, String> readScripts(Path frontendRoot, List<String> warnings) {
        Map<Path, String> scripts = new LinkedHashMap<>();
        if (!Files.isDirectory(frontendRoot)) return scripts;
        List<Path> jsFiles;
        try (Stream<Path> files = Files.walk(frontendRoot)) {
            jsFiles = files.filter(p -> {
                        for (Path segment : frontendRoot.relativize(p)) {
                            if (EXCLUDED_JS_DIRS.contains(segment.toString())) return false;
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".js"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            warnings.add("Could not walk " + frontendRoot + " for JavaScript files: " + e.getMessage());
            return scripts;
        }
        for (Path js : jsFiles) {
            String content = readOrWarn(js, warnings);
            if (content != null) scripts.put(js, content);
        }
        return scripts;
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
