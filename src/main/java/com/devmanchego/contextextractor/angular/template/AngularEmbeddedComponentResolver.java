package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves which <b>non-routed</b> components a routed page embeds in its template, transitively.
 *
 * <p>A reusable component such as {@code <app-address-form>} has no route of its own, so nothing
 * in the route tree points at it and its form fields would otherwise never appear in the
 * generated documentation — even though a user filling in that page plainly sees them.
 *
 * <p><b>Attribution rules</b> (the load-bearing decisions here, since mis-attribution is hard to
 * spot in generated docs):
 * <ul>
 *   <li><b>Transitive.</b> A page embedding {@code A}, which embeds {@code B}, is credited with
 *       both — the user really does see {@code B}'s fields on that page. Traversal is
 *       breadth-first so the returned order follows nesting depth.</li>
 *   <li><b>Routed components are never traversed.</b> A component with its own page entry is
 *       excluded: pulling its content into another page would duplicate a whole page and blur
 *       the routing structure that the "Child Components (Direct)" bullet already documents.</li>
 *   <li><b>Cycle-safe.</b> Mutually-embedding components and self-recursive ones (a tree node
 *       rendering itself is a legitimate pattern) terminate via a shared visited set, and each
 *       component is reported at most once.</li>
 *   <li><b>Element selectors only.</b> Attribute and class selectors ({@code [appHighlight]},
 *       {@code .foo}) are directives rather than embeddable elements, so they are not indexed.</li>
 * </ul>
 *
 * <p>Angular-only: it relies on {@code @Component} selectors and resolvable templates, neither of
 * which the React/Vue extractors currently produce (their {@code ComponentInfo} carries an empty
 * selector). {@link #empty()} is the no-op used for every other framework.
 *
 * <p>Regex-based over the resolved template text, like the rest of this package.
 */
public final class AngularEmbeddedComponentResolver {

    /**
     * Belt-and-braces bound on nesting depth. The shared visited set alone already guarantees
     * termination; this additionally keeps pathological component graphs cheap.
     */
    private static final int MAX_DEPTH = 10;

    /** Element selector → the non-routed component declaring it. */
    private final Map<String, ComponentInfo> componentsBySelector;

    private AngularEmbeddedComponentResolver(Map<String, ComponentInfo> componentsBySelector) {
        this.componentsBySelector = componentsBySelector;
    }

    /** A resolver that never reports anything — used for non-Angular frameworks. */
    public static AngularEmbeddedComponentResolver empty() {
        return new AngularEmbeddedComponentResolver(Map.of());
    }

    /**
     * @param allComponents        every component found in the project, routed or not
     * @param routedComponentNames class names that already have their own page entry
     */
    public static AngularEmbeddedComponentResolver build(Collection<ComponentInfo> allComponents,
                                                         Set<String> routedComponentNames) {
        Map<String, ComponentInfo> bySelector = new LinkedHashMap<>();
        for (ComponentInfo component : allComponents) {
            if (routedComponentNames.contains(component.getClassName())) continue;
            for (String selector : elementSelectorsOf(component)) {
                bySelector.putIfAbsent(selector, component);
            }
        }
        return new AngularEmbeddedComponentResolver(bySelector);
    }

    /**
     * @return the non-routed components embedded by {@code page}, transitively, breadth-first,
     *         each appearing once. Empty when the page has no resolvable template.
     */
    public List<ComponentInfo> resolveEmbedded(ComponentInfo page) {
        if (componentsBySelector.isEmpty() || page == null) return List.of();

        List<ComponentInfo> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(page.getClassName());

        Deque<ComponentInfo> currentLevel = new ArrayDeque<>();
        currentLevel.add(page);

        for (int depth = 0; depth < MAX_DEPTH && !currentLevel.isEmpty(); depth++) {
            Deque<ComponentInfo> nextLevel = new ArrayDeque<>();
            for (ComponentInfo component : currentLevel) {
                for (ComponentInfo embedded : directlyEmbedded(component)) {
                    if (!visited.add(embedded.getClassName())) continue;
                    found.add(embedded);
                    nextLevel.add(embedded);
                }
            }
            currentLevel = nextLevel;
        }
        return found;
    }

    private List<ComponentInfo> directlyEmbedded(ComponentInfo component) {
        String template = TemplateResolver.resolveTemplateText(component.getFilePath());
        if (template == null) return List.of();

        List<ComponentInfo> embedded = new ArrayList<>();
        for (Map.Entry<String, ComponentInfo> entry : componentsBySelector.entrySet()) {
            if (tagPattern(entry.getKey()).matcher(template).find()) {
                embedded.add(entry.getValue());
            }
        }
        return embedded;
    }

    /**
     * Matches {@code <selector} as a whole tag name. The trailing lookahead rejects a longer
     * selector sharing this prefix: {@code app-address} must not match {@code <app-address-form>},
     * and a plain {@code \b} would not prevent that since {@code -} is not a regex word character.
     */
    private static Pattern tagPattern(String selector) {
        return Pattern.compile("<" + Pattern.quote(selector) + "(?![\\w-])");
    }

    /**
     * Angular allows a comma-separated selector list; only the element-selector entries are
     * usable here. Returns an empty list for directives and selector-less components.
     */
    private static List<String> elementSelectorsOf(ComponentInfo component) {
        String selector = component.getSelector();
        if (selector == null || selector.isBlank()) return List.of();

        List<String> elementSelectors = new ArrayList<>();
        for (String candidate : selector.split(",")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty() && Character.isLetter(trimmed.charAt(0))) {
                elementSelectors.add(trimmed);
            }
        }
        return elementSelectors;
    }
}
