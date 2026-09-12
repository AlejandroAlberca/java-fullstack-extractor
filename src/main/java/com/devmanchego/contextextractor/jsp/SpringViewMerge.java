package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.model.RouteOrigin;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.devmanchego.contextextractor.java.mvc.SpringViewNameResolver.ViewResolverConfig;
import com.devmanchego.contextextractor.java.mvc.SpringViewNameResolver.ViewRoute;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 07: folds {@link ViewRoute}s (backend-declared, exact) into Phase 04's route tree
 * (frontend-observed, convention-resolved) — the merge the specification's Section&nbsp;6
 * describes as replacing "the one fragile join" once the backend module is available.
 *
 * <p>Two directions, both keyed on matching a Spring path template against a concrete URL
 * segment-for-segment ({@code {name}} matches exactly one segment):
 *
 * <ol>
 *   <li><b>Supersede.</b> Every ordinary route the frontend already placed is checked against
 *       every resolved {@link ViewRoute}. A match gives the exact file — literal, not a naming
 *       guess — so it always wins: it replaces an unresolved or fallback-inferred mapping outright,
 *       and is reported as superseding when it disagrees with one the convention had already
 *       resolved to a (different) real file.</li>
 *   <li><b>Promote.</b> Every JSP view Phase 04 could place only under "unreferenced" is checked,
 *       in reverse, against every resolved {@link ViewRoute}'s view-name template. A match proves
 *       the view <em>is</em> reachable — through a controller, never through the UI — and the
 *       leaf moves out of "unreferenced" into its own section with the controller-derived URL as
 *       its real path.</li>
 * </ol>
 */
public final class SpringViewMerge {

    public static final String CONTROLLER_DECLARED_SECTION = "Controller-declared views (not linked from the UI)";

    public record Result(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName, List<String> warnings) {}

    private SpringViewMerge() {}

    public static Result merge(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName, Path webappRoot,
                               List<ViewRoute> viewRoutes, ViewResolverConfig config) {
        List<String> warnings = new ArrayList<>();
        List<ViewRoute> resolved = new ArrayList<>();
        for (ViewRoute vr : viewRoutes) {
            if (vr.resolved()) resolved.add(vr);
            for (String bad : vr.unresolvedReturns()) {
                warnings.add(vr.controllerClass() + "#" + vr.methodName() + "(): a return value ('" + bad
                        + "') is not a statically resolvable view name — that branch is not reflected here.");
            }
        }
        if (config.assumedDefault() && !resolved.isEmpty()) {
            warnings.add("No InternalResourceViewResolver configuration found — assuming the conventional "
                    + config.prefix() + " / " + config.suffix() + " (the same one the naming-convention fallback uses).");
        }

        Map<String, ComponentInfo> components = new LinkedHashMap<>(componentsByName);
        Set<String> existingUrls = collectExistingUrls(routes);
        Set<String> promotedPaths = new LinkedHashSet<>();
        List<RouteNode> promoted = new ArrayList<>();
        List<RouteNode> updated = supersedeAndPromote(routes, webappRoot, resolved, config, components, warnings,
                promotedPaths, promoted, existingUrls);

        List<RouteNode> result = new ArrayList<>();
        for (RouteNode r : updated) {
            if (CONTROLLER_DECLARED_SECTION.equals(r.getTitle())) continue; // never present yet, but safe if re-run
            if (JspRouteReconstructor.UNREFERENCED_SECTION.equals(r.getTitle())) {
                List<RouteNode> remaining = r.getChildren().stream()
                        .filter(c -> !promotedPaths.contains(c.getPath())).collect(Collectors.toList());
                if (!remaining.isEmpty()) result.add(r.withChildren(remaining));
            } else {
                result.add(r);
            }
        }
        if (!promoted.isEmpty()) {
            result.add(new RouteNode("", null, CONTROLLER_DECLARED_SECTION, null, false, promoted));
        }
        return new Result(result, components, warnings);
    }

    /** Every URL already placed as an ordinary (non-"unreferenced") page, before any merging. */
    private static Set<String> collectExistingUrls(List<RouteNode> nodes) {
        Set<String> urls = new LinkedHashSet<>();
        collectExistingUrls(nodes, urls);
        return urls;
    }

    private static void collectExistingUrls(List<RouteNode> nodes, Set<String> urls) {
        for (RouteNode n : nodes) {
            if (!n.getChildren().isEmpty()) {
                collectExistingUrls(n.getChildren(), urls);
            } else if (n.isPage() && !n.getPath().startsWith("(unreferenced) ")) {
                urls.add(n.getPath());
            }
        }
    }

    /** Recurses the tree, superseding ordinary leaves in place; promoted unreferenced-view leaves are collected separately. */
    private static List<RouteNode> supersedeAndPromote(List<RouteNode> nodes, Path webappRoot, List<ViewRoute> resolved,
                                                        ViewResolverConfig config, Map<String, ComponentInfo> components,
                                                        List<String> warnings, Set<String> promotedPaths,
                                                        List<RouteNode> promoted, Set<String> existingUrls) {
        List<RouteNode> out = new ArrayList<>();
        for (RouteNode n : nodes) {
            if (!n.getChildren().isEmpty()) {
                // A section (a menu group, or "Unreferenced views" itself) — recurse; a promoted
                // leaf is dropped from the returned children list, per the loop body below.
                List<RouteNode> children = supersedeAndPromote(n.getChildren(), webappRoot, resolved, config,
                        components, warnings, promotedPaths, promoted, existingUrls);
                out.add(n.withChildren(children));
                continue;
            }
            if (!n.isPage()) { out.add(n); continue; }

            if (n.getPath().startsWith("(unreferenced) ")) {
                PromotionMatch match = findPromotion(n, webappRoot, resolved, config, components);
                if (match != null) {
                    promotedPaths.add(n.getPath());
                    if (existingUrls.contains(match.url())) {
                        // Already reachable another way (e.g. web.xml also names this error code) —
                        // dropping the "unreferenced" label is correct; a second leaf at the same
                        // URL, pointing at the same file, would only be noise.
                        warnings.add("View " + n.getTitle() + " is reachable at '" + match.url()
                                + "', which another source already placed in the tree — not duplicated.");
                    } else {
                        promoted.add(promotedNode(n, match));
                        warnings.add("View " + n.getTitle() + " is unreferenced from the UI, but " + match.source()
                                + " serves it at '" + match.url() + "'.");
                    }
                    continue; // dropped from its original position either way
                }
                out.add(n);
                continue;
            }

            out.add(trySupersede(n, webappRoot, resolved, config, components, warnings));
        }
        return out;
    }

    private static RouteNode promotedNode(RouteNode unreferencedLeaf, PromotionMatch match) {
        RouteOrigin origin = new RouteOrigin(List.of(match.source()), Set.of(), Set.of(), null);
        return new RouteNode(match.url(), unreferencedLeaf.getComponentName(), unreferencedLeaf.getTitle(), null,
                false, List.of(), origin);
    }

    // -----------------------------------------------------------------------
    // Supersede — an already-placed leaf's file, confirmed or corrected
    // -----------------------------------------------------------------------

    private static RouteNode trySupersede(RouteNode leaf, Path webappRoot, List<ViewRoute> resolved,
                                          ViewResolverConfig config, Map<String, ComponentInfo> components,
                                          List<String> warnings) {
        for (ViewRoute vr : resolved) {
            Map<String, String> captures = matchUrl(vr.urlTemplate(), leaf.getPath());
            if (captures == null) continue;
            for (String viewNameTemplate : vr.viewNameTemplates()) {
                String viewName = substitute(viewNameTemplate, captures);
                if (viewName == null) continue;
                Path resolvedFile = resolveFile(webappRoot, config, viewName);
                if (!Files.isRegularFile(resolvedFile)) continue;

                ComponentInfo comp = components.get(leaf.getComponentName());
                String oldFile = comp == null ? null : comp.getFilePath();
                String source = "Spring controller " + vr.controllerClass() + "#" + vr.methodName() + "()";
                boolean changed = !resolvedFile.toString().equals(oldFile);
                if (comp != null) {
                    components.put(leaf.getComponentName(), new ComponentInfo(comp.getClassName(),
                            resolvedFile.toString(), comp.getSelector(), comp.getInjectedServices(), comp.getUiTabs()));
                }
                if (changed) {
                    if (oldFile == null || JspRouteReconstructor.UNRESOLVED_FILE.equals(oldFile)) {
                        warnings.add("URL '" + leaf.getPath() + "' resolves to " + rel(resolvedFile, webappRoot)
                                + " per " + source + " — the naming convention had not resolved it at all.");
                    } else {
                        warnings.add("URL '" + leaf.getPath() + "' resolves to " + rel(resolvedFile, webappRoot)
                                + " per " + source + " — superseding the naming-convention mapping to "
                                + rel(Path.of(oldFile), webappRoot) + ".");
                    }
                }
                RouteOrigin old = leaf.getOrigin();
                List<String> sources = old == null ? List.of(source) : concat(old.sources(), source);
                RouteOrigin updated = new RouteOrigin(sources, old == null ? Set.of() : old.requiredPermissions(),
                        old == null ? Set.of() : old.triggeringStates(), null); // exact now — no inferred/unresolved caveat
                return leaf.withOrigin(updated);
            }
        }
        return leaf;
    }

    // -----------------------------------------------------------------------
    // Promote — an unreferenced JSP view proven reachable through a controller
    // -----------------------------------------------------------------------

    private record PromotionMatch(String url, String source) {}

    private static PromotionMatch findPromotion(RouteNode unreferencedLeaf, Path webappRoot, List<ViewRoute> resolved,
                                                ViewResolverConfig config, Map<String, ComponentInfo> components) {
        ComponentInfo comp = components.get(unreferencedLeaf.getComponentName());
        if (comp == null) return null;
        String viewName = viewNameOf(comp.getFilePath(), webappRoot, config);
        if (viewName == null) return null; // the file isn't under this resolver's own prefix — nothing to reverse-map

        for (ViewRoute vr : resolved) {
            if (vr.verb() != HttpVerb.GET) continue; // a navigable page is reached by GET
            for (String viewNameTemplate : vr.viewNameTemplates()) {
                Map<String, String> captures = matchViewName(viewNameTemplate, viewName);
                if (captures == null) continue;
                String url = substitute(vr.urlTemplate(), captures);
                if (url == null) continue;
                return new PromotionMatch(url, "Spring controller " + vr.controllerClass() + "#" + vr.methodName() + "()");
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Template matching — {name} matches exactly one '/'-separated segment
    // -----------------------------------------------------------------------

    /** {@code null} when {@code concreteUrl} doesn't match {@code template}; else each {@code {name}}'s captured value. */
    static Map<String, String> matchUrl(String template, String concreteUrl) {
        return matchSegments(template.split("/", -1), concreteUrl.split("/", -1));
    }

    /** Same shape, applied to a view name (a virtual path with no leading slash) instead of a URL. */
    static Map<String, String> matchViewName(String template, String viewName) {
        return matchSegments(template.split("/", -1), viewName.split("/", -1));
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{(\\w+)}$");

    private static Map<String, String> matchSegments(String[] templateSegs, String[] concreteSegs) {
        if (templateSegs.length != concreteSegs.length) return null;
        Map<String, String> captures = new LinkedHashMap<>();
        for (int i = 0; i < templateSegs.length; i++) {
            Matcher m = PLACEHOLDER.matcher(templateSegs[i]);
            if (m.matches()) {
                if (concreteSegs[i].isEmpty()) return null;
                captures.put(m.group(1), concreteSegs[i]);
            } else if (!templateSegs[i].equals(concreteSegs[i])) {
                return null;
            }
        }
        return captures;
    }

    /** {@code null} when a {@code {name}} in {@code template} has no matching capture. */
    private static String substitute(String template, Map<String, String> captures) {
        Matcher m = Pattern.compile("\\{(\\w+)}").matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = captures.get(m.group(1));
            if (value == null) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Path resolveFile(Path webappRoot, ViewResolverConfig config, String viewName) {
        return webappRoot.resolve(prefixDir(config) + viewName + config.suffix()).normalize();
    }

    private static String prefixDir(ViewResolverConfig config) {
        return config.prefix().startsWith("/") ? config.prefix().substring(1) : config.prefix();
    }

    /** The inverse of {@link #resolveFile}: a JSP file's view name, or null when it isn't under this prefix/suffix. */
    private static String viewNameOf(String filePath, Path webappRoot, ViewResolverConfig config) {
        Path prefixed = webappRoot.resolve(prefixDir(config)).normalize();
        Path file = Path.of(filePath).normalize();
        if (!file.startsWith(prefixed)) return null;
        String rel = prefixed.relativize(file).toString().replace('\\', '/');
        if (!rel.endsWith(config.suffix())) return null;
        return rel.substring(0, rel.length() - config.suffix().length());
    }

    private static List<String> concat(List<String> existing, String extra) {
        if (existing.contains(extra)) return existing;
        List<String> out = new ArrayList<>(existing);
        out.add(extra);
        return out;
    }

    private static String rel(Path p, Path root) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString().replace('\\', '/');
        }
    }
}
