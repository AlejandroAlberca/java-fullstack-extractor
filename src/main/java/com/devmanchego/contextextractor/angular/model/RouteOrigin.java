package com.devmanchego.contextextractor.angular.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a reconstructed route came from. Only frameworks with no declarative route table
 * (JSP + jQuery) set it: their tree is merged from several partial sources, and a reader needs
 * to know which ones vouch for each node. {@code null} on routes read from a route declaration.
 *
 * @param sources             human-readable provenance, one entry per kind of source, e.g.
 *                            {@code "link (WEB-INF/jsp/fragments/menu.jsp)"}
 * @param requiredPermissions permissions gating the navigation entry — UI-fragment granularity
 *                            ({@code <sec:authorize>} around the link), not URL-level security
 * @param triggeringStates    workflow states whose client-side dispatch function leads here
 * @param viewMappingNote     {@code null} when the URL → view file join is exact; otherwise why
 *                            it isn't (inferred by a fallback, or unresolved)
 */
public record RouteOrigin(List<String> sources, Set<String> requiredPermissions,
                          Set<String> triggeringStates, String viewMappingNote) {

    public RouteOrigin {
        sources = List.copyOf(sources);
        requiredPermissions = Collections.unmodifiableSet(new LinkedHashSet<>(requiredPermissions));
        triggeringStates = Collections.unmodifiableSet(new LinkedHashSet<>(triggeringStates));
    }
}
