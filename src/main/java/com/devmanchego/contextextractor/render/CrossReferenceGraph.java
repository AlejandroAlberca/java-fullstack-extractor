package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.java.exception.ExceptionInfo;
import com.devmanchego.contextextractor.java.model.DatabaseSchema;
import com.devmanchego.contextextractor.java.model.PersistenceMapping;
import com.devmanchego.contextextractor.java.model.SchemaRelationship;
import com.devmanchego.contextextractor.java.model.SchemaTable;
import com.devmanchego.contextextractor.matching.MatchedFlow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves cross-references ("backlinks") between the per-element detail documents of
 * different categories, so an AI assistant can hop from one element to a related one
 * without loading a whole monolithic document.
 *
 * <p>Phase 3a covers the four <em>exact</em> edges (all matched by domain name, no
 * heuristics):
 * <ul>
 *   <li>Data Contract ⟷ Persistence Mapping — share the DTO name (key {@code dto:})</li>
 *   <li>Persistence Mapping → Table — Entity simple name (key {@code entity:})</li>
 *   <li>Table ⟷ Table — foreign keys (from {@link DatabaseSchema})</li>
 *   <li>Flow → Data Contract — endpoint response/body type = contract Java type (key {@code dto:})</li>
 * </ul>
 *
 * <p>Nodes are registered for <em>every</em> element, even those whose category fell below
 * {@code --index-detail-threshold} and therefore has no detail document: such a node is
 * still resolvable (its name is preserved) but is rendered as plain text {@code (not indexed)}
 * instead of a link — except tables, which fall back to the {@code data-model.md} anchor.
 *
 * <p>All paths are relative to the <em>base output directory</em> (e.g.
 * {@code indexed_specs/detailed_tables/employees.md}); {@link RelatedLinksRenderer}
 * re-relativizes them against each document's own location.
 */
public final class CrossReferenceGraph {

    public enum Category {
        FLOW("Flow"),
        DATA_CONTRACT("Data contract"),
        PERSISTENCE_MAPPING("Persistence mapping"),
        TABLE("Table"),
        EXCEPTION("Exception"),
        FRONTEND_PAGE("Page");

        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
    }

    /**
     * A registered element. {@code pathRelToBase} is the detail document (null when the
     * element's category was not indexed); {@code anchor} is an optional fragment for the
     * fallback target. When {@code pathRelToBase} is null the element renders as plain text.
     */
    public record Node(Category category, String label, String pathRelToBase, String anchor) {
        public boolean navigable() { return pathRelToBase != null; }
    }

    /** A resolved link from the document being rendered to a related element. */
    public record RelatedLink(Node target, String relation, boolean inferred, boolean outgoing) {}

    private final Map<String, List<Node>> byKey = new LinkedHashMap<>();

    /** Entities each flow reaches through its call-graph repositories (Phase 3b, heuristic). */
    private Map<MatchedFlow, Set<String>> flowInferredEntities = Map.of();

    /** Simple class names appearing in each flow's call graph (Phase 3b, heuristic). */
    private Map<MatchedFlow, Set<String>> flowClasses = Map.of();

    private static final Pattern COLLECTION_TYPE =
            Pattern.compile("^(?:List|Set|Collection|Iterable|Page|Slice)<(.+)>$");

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    public void register(String key, Node node) {
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
    }

    /** Entities reachable from each flow's repositories, for the inferred Flow⟷Table edge. */
    public void setFlowInferredEntities(Map<MatchedFlow, Set<String>> map) {
        this.flowInferredEntities = map != null ? map : Map.of();
    }

    /** Simple class names in each flow's call graph, for the inferred Exception→Flow edge. */
    public void setFlowClasses(Map<MatchedFlow, Set<String>> map) {
        this.flowClasses = map != null ? map : Map.of();
    }

    /** Domain-key helpers so callers and resolvers agree on the exact string form. */
    public static String dtoKey(String javaTypeName)      { return "dto:" + innerType(javaTypeName); }
    public static String entityKey(String entitySimple)   { return "entity:" + entitySimple; }
    public static String tableKey(String qualifiedName)   { return "table:" + qualifiedName; }
    public static String entityInferredKey(String entity) { return "entity-inferred:" + entity; }
    public static String classKey(String simpleClassName) { return "class:" + simpleClassName; }
    public static String ngServiceKey(String serviceName) { return "ng-service:" + serviceName; }

    /** Simple name from a possibly qualified class name. */
    public static String simpleName(String className) {
        if (className == null) return null;
        return className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
    }

    /**
     * Classes from which an exception is actually thrown, usable for call-graph matching.
     * Excludes the exception's own class (some are only located at their declaration, with
     * origin method "unknown") so we don't emit a meaningless self-edge.
     */
    public static Set<String> throwingClasses(ExceptionInfo exc) {
        Set<String> classes = new LinkedHashSet<>();
        String origin = simpleName(exc.originClass());
        if (origin != null && !origin.equals(exc.exceptionClassName())) {
            classes.add(origin);
        }
        return classes;
    }

    /**
     * Normalizes a Java type to the referenced element's simple name: strips an array
     * suffix and unwraps a single collection/pagination generic ({@code List<X>} → {@code X})
     * so a list endpoint resolves to the same DTO as its single-item counterpart.
     */
    static String innerType(String type) {
        if (type == null) return "";
        String t = type.trim();
        if (t.endsWith("[]")) t = t.substring(0, t.length() - 2).trim();
        Matcher m = COLLECTION_TYPE.matcher(t);
        if (m.matches()) t = m.group(1).trim();
        return t;
    }

    // -----------------------------------------------------------------------
    // Typed resolvers — one per category that gets a "## Related" section
    // -----------------------------------------------------------------------

    public List<RelatedLink> relatedForTable(SchemaTable table, DatabaseSchema schema) {
        List<RelatedLink> links = new ArrayList<>();
        String self = table.qualifiedName();

        // Outgoing foreign keys → other tables
        for (SchemaRelationship rel : schema.outgoingFrom(self)) {
            if (rel.getToTable().equals(self)) continue; // self-join: skip self-link
            for (Node n : lookup(tableKey(rel.getToTable()), Category.TABLE)) {
                links.add(new RelatedLink(n, "FK " + rel.cardinalityNotation()
                        + " via `" + rel.getFromColumn() + "`", false, true));
            }
        }
        // Incoming foreign keys ← other tables
        for (SchemaRelationship rel : schema.incomingTo(self)) {
            if (rel.getFromTable().equals(self)) continue;
            for (Node n : lookup(tableKey(rel.getFromTable()), Category.TABLE)) {
                links.add(new RelatedLink(n, "referenced by FK `" + rel.getFromColumn() + "`", false, false));
            }
        }
        // Mapped by persistence mappings (entity)
        if (table.getEntitySimpleName() != null) {
            for (Node n : lookup(entityKey(table.getEntitySimpleName()), Category.PERSISTENCE_MAPPING)) {
                links.add(new RelatedLink(n, "mapped by", false, false));
            }
            // Queried by flows whose call graph reaches this entity's repository (heuristic)
            for (Node n : lookup(entityInferredKey(table.getEntitySimpleName()), Category.FLOW)) {
                links.add(new RelatedLink(n, "queried by flow", true, false));
            }
        }
        return links;
    }

    public List<RelatedLink> relatedForMapping(PersistenceMapping pm) {
        List<RelatedLink> links = new ArrayList<>();
        // → Table (entity)
        for (Node n : lookup(entityKey(pm.getEntity().getSimpleName()), Category.TABLE)) {
            links.add(new RelatedLink(n, "maps to table", false, true));
        }
        // ⟷ Data contract (DTO)
        for (Node n : lookup(dtoKey(pm.getDto().getSimpleName()), Category.DATA_CONTRACT)) {
            links.add(new RelatedLink(n, "data contract", false, true));
        }
        return links;
    }

    public List<RelatedLink> relatedForContract(String javaType) {
        List<RelatedLink> links = new ArrayList<>();
        // ⟷ Persistence mapping (DTO)
        for (Node n : lookup(dtoKey(javaType), Category.PERSISTENCE_MAPPING)) {
            links.add(new RelatedLink(n, "persistence mapping", false, true));
        }
        // ← Used by flows (DTO = endpoint response/body type)
        for (Node n : lookup(dtoKey(javaType), Category.FLOW)) {
            links.add(new RelatedLink(n, "used by flow", false, false));
        }
        return links;
    }

    public List<RelatedLink> relatedForFlow(MatchedFlow flow) {
        List<RelatedLink> links = new ArrayList<>();
        String response = flow.getJavaEndpoint().getResponseType();
        String body = flow.getJavaEndpoint().getBodyParameterType();
        if (response != null) {
            for (Node n : lookup(dtoKey(response), Category.DATA_CONTRACT)) {
                links.add(new RelatedLink(n, "returns data contract", false, true));
            }
        }
        if (body != null) {
            for (Node n : lookup(dtoKey(body), Category.DATA_CONTRACT)) {
                links.add(new RelatedLink(n, "accepts data contract", false, true));
            }
        }
        // Tables reached through this flow's call-graph repositories (heuristic)
        for (String entity : flowInferredEntities.getOrDefault(flow, Set.of())) {
            for (Node n : lookup(entityKey(entity), Category.TABLE)) {
                links.add(new RelatedLink(n, "queries table", true, true));
            }
        }
        // Exceptions thrown from a class in this flow's call graph (heuristic)
        for (String cls : flowClasses.getOrDefault(flow, Set.of())) {
            for (Node n : lookup(classKey(cls), Category.EXCEPTION)) {
                links.add(new RelatedLink(n, "may throw", true, true));
            }
        }
        // Frontend pages that inject the Angular service backing this flow (heuristic)
        if (flow.getAngularService() != null) {
            for (Node n : lookup(ngServiceKey(flow.getAngularService().getClassName()), Category.FRONTEND_PAGE)) {
                links.add(new RelatedLink(n, "called from page", true, false));
            }
        }
        return links;
    }

    public List<RelatedLink> relatedForException(ExceptionInfo exc) {
        List<RelatedLink> links = new ArrayList<>();
        for (String cls : throwingClasses(exc)) {
            for (Node n : lookup(classKey(cls), Category.FLOW)) {
                links.add(new RelatedLink(n, "thrown by flow", true, false));
            }
        }
        return links;
    }

    public List<RelatedLink> relatedForPage(ComponentInfo component) {
        List<RelatedLink> links = new ArrayList<>();
        if (component == null) return links;
        for (String service : component.getInjectedServices()) {
            for (Node n : lookup(ngServiceKey(service), Category.FLOW)) {
                links.add(new RelatedLink(n, "calls flow", true, true));
            }
        }
        return links;
    }

    // -----------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------

    private List<Node> lookup(String key, Category category) {
        List<Node> result = new ArrayList<>();
        for (Node n : byKey.getOrDefault(key, List.of())) {
            if (n.category() == category && !result.contains(n)) {
                result.add(n);
            }
        }
        return result;
    }
}
