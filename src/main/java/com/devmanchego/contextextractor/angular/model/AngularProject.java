package com.devmanchego.contextextractor.angular.model;

import com.devmanchego.contextextractor.frontend.FrontendFramework;

import java.util.List;
import java.util.Map;

/**
 * Aggregated result of frontend project analysis.
 * Produced by either Strategy A (Node/ts-morph) or Strategy B (JVM parser).
 * Downstream matching and rendering are strategy-agnostic.
 */
public final class AngularProject {

    public enum ParsingStrategy { NODE_TS_MORPH, JVM_ANTLR }

    private final ParsingStrategy strategy;
    private final FrontendFramework framework;
    private final List<ComponentInfo> components;
    private final List<ServiceInfo> services;
    private final List<TsModelInfo> models;
    private final List<RouteNode> routes;
    private final Map<String, List<String>> layoutsByPage;
    /**
     * Frontend source files ({@code .ts}/{@code .tsx}/{@code .js}/{@code .jsx}/{@code .vue})
     * found on disk under the project root, counted independently of what any extractor
     * managed to understand. Set once by {@code FrontendAnalyzer} after every extraction
     * strategy has run; zero for any project built via a constructor that predates this field
     * (tests, mainly) — which is indistinguishable from "no frontend project found" and never
     * trips {@link #isUninterpreted()}, so existing callers are unaffected.
     */
    private final int scannedSourceFileCount;

    public AngularProject(ParsingStrategy strategy,
                          List<ComponentInfo> components,
                          List<ServiceInfo> services,
                          List<TsModelInfo> models) {
        this(strategy, FrontendFramework.ANGULAR, components, services, models, List.of());
    }

    public AngularProject(ParsingStrategy strategy,
                          FrontendFramework framework,
                          List<ComponentInfo> components,
                          List<ServiceInfo> services,
                          List<TsModelInfo> models) {
        this(strategy, framework, components, services, models, List.of());
    }

    public AngularProject(ParsingStrategy strategy,
                          FrontendFramework framework,
                          List<ComponentInfo> components,
                          List<ServiceInfo> services,
                          List<TsModelInfo> models,
                          List<RouteNode> routes) {
        this(strategy, framework, components, services, models, routes, Map.of());
    }

    public AngularProject(ParsingStrategy strategy,
                          FrontendFramework framework,
                          List<ComponentInfo> components,
                          List<ServiceInfo> services,
                          List<TsModelInfo> models,
                          List<RouteNode> routes,
                          Map<String, List<String>> layoutsByPage) {
        this(strategy, framework, components, services, models, routes, layoutsByPage, 0);
    }

    public AngularProject(ParsingStrategy strategy,
                          FrontendFramework framework,
                          List<ComponentInfo> components,
                          List<ServiceInfo> services,
                          List<TsModelInfo> models,
                          List<RouteNode> routes,
                          Map<String, List<String>> layoutsByPage,
                          int scannedSourceFileCount) {
        this.strategy      = strategy;
        this.framework     = framework;
        this.components    = List.copyOf(components);
        this.services      = List.copyOf(services);
        this.models        = List.copyOf(models);
        this.routes        = List.copyOf(routes);
        this.layoutsByPage = Map.copyOf(layoutsByPage);
        this.scannedSourceFileCount = scannedSourceFileCount;
    }

    /** Returns a copy of this project with the given framework tag. */
    public AngularProject withFramework(FrontendFramework fw) {
        return new AngularProject(strategy, fw, components, services, models, routes, layoutsByPage,
                scannedSourceFileCount);
    }

    /** Returns a copy of this project with the given route tree. */
    public AngularProject withRoutes(List<RouteNode> newRoutes) {
        return new AngularProject(strategy, framework, components, services, models, newRoutes, layoutsByPage,
                scannedSourceFileCount);
    }

    /** Returns a copy of this project with the given component list. */
    public AngularProject withComponents(List<ComponentInfo> newComponents) {
        return new AngularProject(strategy, framework, newComponents, services, models, routes, layoutsByPage,
                scannedSourceFileCount);
    }

    /** Returns a copy of this project with the given page→layout-chain map (Next.js App Router only). */
    public AngularProject withLayoutsByPage(Map<String, List<String>> newLayoutsByPage) {
        return new AngularProject(strategy, framework, components, services, models, routes, newLayoutsByPage,
                scannedSourceFileCount);
    }

    /**
     * Returns a copy of this project recording how many frontend source files were found on
     * disk, regardless of what was extracted from them. Called once by {@code FrontendAnalyzer}
     * at the end of analysis, after every strategy and fallback has had a chance to run.
     */
    public AngularProject withScannedSourceFileCount(int count) {
        return new AngularProject(strategy, framework, components, services, models, routes, layoutsByPage, count);
    }

    public ParsingStrategy getStrategy()      { return strategy; }
    public FrontendFramework getFramework()   { return framework; }
    public List<ComponentInfo> getComponents(){ return components; }
    public List<ServiceInfo> getServices()    { return services; }
    public List<TsModelInfo> getModels()      { return models; }
    public List<RouteNode> getRoutes()        { return routes; }

    /** Component name → ordered layout chain (root-first). Empty unless Next.js App Router. */
    public Map<String, List<String>> getLayoutsByPage() { return layoutsByPage; }

    /** Frontend source files found on disk under the project root — see {@link #isUninterpreted()}. */
    public int getScannedSourceFileCount() { return scannedSourceFileCount; }

    /** True when nothing at all was extracted: no components, services, routes, or data models. */
    public boolean isEmpty() {
        return components.isEmpty() && services.isEmpty() && routes.isEmpty() && models.isEmpty();
    }

    /**
     * True when analysis produced a completely {@link #isEmpty() empty} model despite finding
     * frontend source files to analyse — i.e. the frontend project could not be interpreted,
     * as distinct from genuinely having no components, services, routes, or data models.
     *
     * <p>Callers rendering a document derived from this project <b>must</b> treat this case as
     * "not analysed", never as confirmation that the frontend makes no calls: an empty flow
     * count here reflects a parser that didn't recognise the project's structure, not an
     * application with nothing to show.
     */
    public boolean isUninterpreted() {
        return isEmpty() && scannedSourceFileCount > 0;
    }
}
