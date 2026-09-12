package com.devmanchego.contextextractor;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog;
import com.devmanchego.contextextractor.angular.resolve.UrlPrefixResolver;
import com.devmanchego.contextextractor.angular.webpack.WebpackConstantReader;
import com.devmanchego.contextextractor.callgraph.CallGraphBuilder;
import com.devmanchego.contextextractor.callgraph.CallNode;
import com.devmanchego.contextextractor.cli.CliArguments;
import com.devmanchego.contextextractor.frontend.FrontendAnalyzer;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.java.extractor.*;
import com.devmanchego.contextextractor.java.model.ScheduledJobInfo;
import com.devmanchego.contextextractor.java.model.*;
import com.devmanchego.contextextractor.java.parser.ClasspathResolver;
import com.devmanchego.contextextractor.java.parser.DatabaseConfigExtractor;
import com.devmanchego.contextextractor.java.parser.FlywayMigrationParser;
import com.devmanchego.contextextractor.java.parser.JavaParserFactory;
import com.devmanchego.contextextractor.java.parser.LiveSchemaIntrospector;
import com.devmanchego.contextextractor.java.persistence.PersistenceMappingExtractor;
import com.devmanchego.contextextractor.java.schema.SchemaBuilder;
import com.devmanchego.contextextractor.render.DatabaseSchemaRenderer;
import com.devmanchego.contextextractor.render.PersistenceMappingIndexRenderer;
import com.devmanchego.contextextractor.render.DataContractIndexRenderer;
import com.devmanchego.contextextractor.render.StaticRoutesIndexRenderer;
import com.devmanchego.contextextractor.render.ErrorCatalogIndexRenderer;
import com.devmanchego.contextextractor.render.CrossReferenceGraph;
import com.devmanchego.contextextractor.render.RelatedLinksRenderer;
import com.devmanchego.contextextractor.matching.MatchedFlow;
import com.devmanchego.contextextractor.java.exception.ExceptionCatalogExtractor;
import com.devmanchego.contextextractor.java.exception.ExceptionInfo;
import com.devmanchego.contextextractor.jsp.JspAuthorizationExtractor;
import com.devmanchego.contextextractor.jsp.JspRouteReconstructor;
import com.devmanchego.contextextractor.java.security.SecurityConfigAnalyzer;
import com.devmanchego.contextextractor.java.security.SecurityMatrix;
import com.devmanchego.contextextractor.java.security.SecurityMatrixBuilder;
import com.devmanchego.contextextractor.java.security.SecurityRule;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
import com.devmanchego.contextextractor.render.ClassDiagramPngExporter;
import com.devmanchego.contextextractor.render.ClassDiagramRenderer;
import com.devmanchego.contextextractor.render.FlowDiagramPngExporter;
import com.devmanchego.contextextractor.render.FlowDiagramRenderer;
import com.devmanchego.contextextractor.render.MarkdownRenderer;
import com.devmanchego.contextextractor.render.RootIndexRenderer;
import com.devmanchego.contextextractor.render.FlowZipExporter;
import com.devmanchego.contextextractor.render.FrontendPagesRenderer;
import com.devmanchego.contextextractor.render.FrontendPagesZipExporter;
import com.devmanchego.contextextractor.render.SectionsRenderer;
import com.devmanchego.contextextractor.render.SitemapRenderer;
import com.devmanchego.contextextractor.render.SecurityMatrixRenderer;
import com.devmanchego.contextextractor.render.ErrorCatalogRenderer;
import com.devmanchego.contextextractor.render.TraceabilityRenderer;
import com.devmanchego.contextextractor.render.SequenceDiagramPngExporter;
import com.devmanchego.contextextractor.render.SequenceDiagramRenderer;
import com.devmanchego.contextextractor.render.UnroutedPagesFallback;
import com.devmanchego.contextextractor.angular.template.AngularFormFieldExtractor;
import com.devmanchego.contextextractor.common.FieldLabel;
import com.devmanchego.contextextractor.angular.model.FrontendCalculationDetector;
import com.devmanchego.contextextractor.java.model.CalculationEvidence;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main pipeline orchestrator.
 * Wires together all analysis layers and writes the output Markdown file.
 */
public final class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    private Application() {}

    public static void run(CliArguments args) throws IOException {
        log.info("=== java-angular-fullstack-extractor starting ===");
        log.info("Java project:    {}", args.getJavaProjectPath());
        log.info("Angular project: {}", args.getAngularProjectPath());
        log.info("Output file:     {}", args.getOutputFile());
        if (args.isEnableGenericExceptions()) {
            log.info("Generic exception classification: ENABLED");
        } else {
            log.info("Generic exception classification: DISABLED");
        }

        // ---------------------------------------------------------------
        // 1. Resolve Java project classpath
        // ---------------------------------------------------------------
        List<Path> classpath = ClasspathResolver.resolve(args.getJavaProjectPath());
        boolean classpathDegraded = classpath.isEmpty();
        if (classpathDegraded) {
            log.warn("Running in degraded mode: classpath could not be resolved.");
            log.warn("Ensure Maven ('mvn') is on PATH — this affects type resolution and TypeScript parsing.");
        }

        // ---------------------------------------------------------------
        // 2. Parse all Java source files
        // ---------------------------------------------------------------
        JavaParser javaParser = JavaParserFactory.create(args.getJavaProjectPath(), classpath);
        List<CompilationUnit> allCUs = parseJavaProject(args.getJavaProjectPath(), javaParser);
        log.info("Parsed {} Java compilation units.", allCUs.size());

        // ---------------------------------------------------------------
        // 3. Pre-pass: collect static String constants for annotation resolution
        //    (e.g. WebConstants.API_V1_ROOT_URL referenced in @RequestMapping)
        // ---------------------------------------------------------------
        Map<String, String> javaConstants = AnnotationValueResolver.collectConstants(allCUs);
        log.info("Collected {} Java string constant(s) for annotation resolution.", javaConstants.size());

        // ---------------------------------------------------------------
        // 4. Extract endpoints from all three extractor families
        // ---------------------------------------------------------------
        List<EndpointExtractor> extractors = List.of(
                new SpringEndpointExtractor(javaConstants),
                new JaxRsEndpointExtractor(javaConstants),
                new MicronautEndpointExtractor(javaConstants)
        );

        List<EndpointInfo> endpoints = new ArrayList<>();
        for (CompilationUnit cu : allCUs) {
            String sourceFile = cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown");
            for (EndpointExtractor extractor : extractors) {
                endpoints.addAll(extractor.extract(cu, sourceFile));
            }
        }
        log.info("Extracted {} endpoints.", endpoints.size());

        // ---------------------------------------------------------------
        // 4. Detect name collisions
        // ---------------------------------------------------------------
        List<String> nameCollisions = detectNameCollisions(endpoints);

        // ---------------------------------------------------------------
        // 5. Extract persistence mappings
        // ---------------------------------------------------------------
        PersistenceMappingExtractor persistenceExtractor = new PersistenceMappingExtractor();
        List<EntityInfo> entities = new ArrayList<>();
        List<DtoInfo> dtos = new ArrayList<>();
        Map<String, List<RepositoryMethodInfo>> repoMethods = new HashMap<>();

        for (CompilationUnit cu : allCUs) {
            String sourceFile = cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown");
            persistenceExtractor.extractEntity(cu, sourceFile, allCUs).ifPresent(entities::add);
            dtos.addAll(persistenceExtractor.extractDtos(cu, sourceFile));
            List<RepositoryMethodInfo> methods = persistenceExtractor.extractRepositoryMethods(cu);
            if (!methods.isEmpty()) {
                methods.forEach(m -> repoMethods
                        .computeIfAbsent("repo", k -> new ArrayList<>()).add(m));
            }
        }

        // Correlate DTOs with Entities. Both sides are normalised before comparison — a
        // codebase can just as easily suffix its persistence classes (AcheteurEntity ↔
        // Acheteur) as its transfer classes (Acheteur ↔ AcheteurDto); stripping only the DTO
        // side means the first convention never correlates at all.
        List<PersistenceMapping> persistenceMappings = new ArrayList<>();
        for (DtoInfo dto : dtos) {
            entities.stream()
                    .filter(e -> dtoAndEntityNamesCorrelate(dto.getSimpleName(), e.getSimpleName()))
                    .findFirst()
                    .ifPresent(entity -> persistenceMappings.add(
                            persistenceExtractor.correlate(dto, entity, allCUs)));
        }
        log.info("Found {} entities, {} DTOs, {} persistence mappings.",
                entities.size(), dtos.size(), persistenceMappings.size());

        // ---------------------------------------------------------------
        // 5b. Build the unified data model: live DB (if reachable) / SQL migrations / JPA entities.
        //     Never fails the pipeline — a missing DB connection or missing migrations just
        //     narrows what can be extracted, with a warning surfaced in the output document.
        // ---------------------------------------------------------------
        DatabaseSchema databaseSchema = buildDatabaseSchema(args, entities, allCUs);

        // ---------------------------------------------------------------
        // 6. Parse frontend project (Strategy A or B, any supported framework)
        // ---------------------------------------------------------------
        AngularProject angularProject = new FrontendAnalyzer(
                args.getAngularProjectPath(),
                args.getFrontendFramework(),
                allCUs).analyze();

        // ---------------------------------------------------------------
        // 6b. Resolve dynamic URL prefixes
        //     Layer 1 — webpack DefinePlugin constants (automatic)
        //     Layer 2 — user-supplied --url-prefix-map entries
        // ---------------------------------------------------------------
        Map<String, String> webpackConstants =
                WebpackConstantReader.read(args.getAngularProjectPath());
        UrlPrefixResolver urlResolver =
                new UrlPrefixResolver(webpackConstants, args.getUrlPrefixMap());
        List<String> unresolvedUrlWarnings = new ArrayList<>();
        if (!urlResolver.isEmpty()) {
            angularProject = urlResolver.applyToProject(angularProject, unresolvedUrlWarnings);
        }

        // ---------------------------------------------------------------
        // 7. Match endpoints
        // ---------------------------------------------------------------
        EndpointMatcher.MatchResult matchResult = EndpointMatcher.match(
                angularProject, endpoints, angularProject.getModels(), args.isStrictMatching());

        // ---------------------------------------------------------------
        // 8. Extract @Scheduled batch jobs
        // ---------------------------------------------------------------
        ScheduledJobExtractor scheduledJobExtractor = new ScheduledJobExtractor();
        List<ScheduledJobInfo> scheduledJobs = new ArrayList<>();
        for (CompilationUnit cu : allCUs) {
            String sourceFile = cu.getStorage().map(s -> s.getPath().toString()).orElse("unknown");
            scheduledJobs.addAll(scheduledJobExtractor.extract(cu, sourceFile));
        }
        log.info("Found {} @Scheduled batch job(s).", scheduledJobs.size());

        // ---------------------------------------------------------------
        // 9. Build call graphs for matched flows + scheduled jobs
        // ---------------------------------------------------------------
        CallGraphBuilder callGraphBuilder = new CallGraphBuilder(allCUs);
        Map<EndpointInfo, CallNode> callGraphs = new LinkedHashMap<>();
        for (var flow : matchResult.flows()) {
            callGraphBuilder.build(flow.getJavaEndpoint())
                    .ifPresent(root -> callGraphs.put(flow.getJavaEndpoint(), root));
        }
        Map<ScheduledJobInfo, CallNode> jobCallGraphs = new LinkedHashMap<>();
        for (ScheduledJobInfo job : scheduledJobs) {
            callGraphBuilder.buildForJob(job)
                    .ifPresent(root -> jobCallGraphs.put(job, root));
        }

        // ---------------------------------------------------------------
        // 10. Render Markdown
        // ---------------------------------------------------------------
        Map<String, DtoInfo> dtosMap = dtos.stream()
                .collect(Collectors.toMap(DtoInfo::getSimpleName, d -> d, (a, b) -> a));

        MarkdownRenderer.RenderInput renderInput = new MarkdownRenderer.RenderInput(
                angularProject,
                endpoints,
                matchResult,
                persistenceMappings,
                dtosMap,
                callGraphs,
                scheduledJobs,
                jobCallGraphs,
                callGraphBuilder.getCycleWarnings(),
                unresolvedUrlWarnings,
                classpathDegraded,
                nameCollisions,
                args.getRootPath()
        );

        String markdown = new MarkdownRenderer().render(renderInput);
        Files.writeString(args.getOutputFile(), markdown);

        // Generate flow diagrams Markdown files (filtered + unfiltered)
        FlowDiagramRenderer flowDiagramRenderer = new FlowDiagramRenderer();
        Path flowsFile = FlowDiagramRenderer.flowsOutputPath(args.getOutputFile());
        String flowsDiagram = flowDiagramRenderer.render(
                matchResult.flows(), callGraphs, jobCallGraphs, args.getRootPath());
        Files.writeString(flowsFile, flowsDiagram);

        Path flowsFullFile = FlowDiagramRenderer.flowsFullOutputPath(args.getOutputFile());
        String flowsFullDiagram = flowDiagramRenderer.renderFull(
                matchResult.flows(), callGraphs, jobCallGraphs, args.getRootPath());
        Files.writeString(flowsFullFile, flowsFullDiagram);

        // Generate full-flow ZIP (index + one MD per flow/job, Mermaid only)
        Path flowsZipFile = FlowZipExporter.zipOutputPath(args.getOutputFile());
        int zipEntries = new FlowZipExporter().export(
                matchResult.flows(), callGraphs, jobCallGraphs, flowsZipFile);

        // Frontend page/route model — needed both for the cross-reference graph (Page⟷Flow)
        // and for the frontend-pages documents further down.
        Map<String, ComponentInfo> componentsByName = angularProject.getComponents().stream()
                .collect(Collectors.toMap(ComponentInfo::getClassName, c -> c, (a, b) -> a));
        List<RouteNode> pageRoutes = angularProject.getRoutes();
        boolean unroutedFallback = false;
        if (pageRoutes.isEmpty() && !componentsByName.isEmpty()) {
            pageRoutes = UnroutedPagesFallback.synthesize(componentsByName.values());
            unroutedFallback = true;
            log.info("No route tree found; listing {} component(s) as unrouted pages.", pageRoutes.size());
        }

        // Build the cross-reference graph (Phase 3a exact + 3b inferred edges) before writing any
        // per-element detail document, so each can carry a bidirectional "## Related" section.
        DataContractIndexRenderer dcRenderer = new DataContractIndexRenderer();
        List<DataContractIndexRenderer.Contract> contracts = dcRenderer.collectContracts(matchResult, dtosMap);
        List<FlowZipExporter.FlowRef> flowRefs = new FlowZipExporter().flowRefs(matchResult.flows(), callGraphs);
        List<FrontendPagesZipExporter.PageRef> pageRefs =
                new FrontendPagesZipExporter().pageRefs(pageRoutes, componentsByName);
        Map<MatchedFlow, java.util.Set<String>> inferredFlowEntities = inferFlowEntities(flowRefs, callGraphs);
        Map<MatchedFlow, java.util.Set<String>> flowClasses = collectFlowClasses(flowRefs, callGraphs);
        List<ExceptionInfo> exceptions = new ExceptionCatalogExtractor().extractExceptions(
                allCUs, args.isEnableGenericExceptions(), callGraphs);
        CrossReferenceGraph xref = buildCrossReferenceGraph(
                args, databaseSchema, persistenceMappings, contracts, flowRefs, inferredFlowEntities,
                flowClasses, exceptions, pageRefs);
        RelatedLinksRenderer relatedRenderer = new RelatedLinksRenderer();
        Map<MatchedFlow, String> flowOrigin = new LinkedHashMap<>();
        for (FlowZipExporter.FlowRef fr : flowRefs) {
            flowOrigin.put(fr.flow(), "indexed_specs/" + fr.relPathWithinDir());
        }

        // Generate the same content unzipped, under indexed_specs/ (per-element index + detail docs),
        // each flow document carrying its bidirectional "## Related" cross-references.
        new FlowZipExporter().exportToDirectory(
                matchResult.flows(), callGraphs, jobCallGraphs, args.getIndexedSpecsDir(),
                flow -> relatedRenderer.render(xref.relatedForFlow(flow), flowOrigin.get(flow)));

        // Generate PNG flow diagrams if --flow-diagrams-dir was provided
        FlowDiagramPngExporter flowPngExporter = new FlowDiagramPngExporter();
        args.getFlowDiagramsDir().ifPresent(diagramsDir -> {
            try {
                List<Path> pngs = flowPngExporter.export(
                        matchResult.flows(), callGraphs, jobCallGraphs, diagramsDir);
                System.out.println("Flow diagram PNGs written to: " + diagramsDir.toAbsolutePath()
                        + " (" + pngs.size() + " file(s))");
            } catch (IOException e) {
                log.error("PNG export failed: {}", e.getMessage());
            }
        });

        // Generate PNG full-flow diagrams if --full-flow-diagrams-dir was provided
        args.getFullFlowDiagramsDir().ifPresent(diagramsDir -> {
            try {
                List<Path> pngs = flowPngExporter.exportFull(
                        matchResult.flows(), callGraphs, jobCallGraphs, diagramsDir);
                System.out.println("Full-flow diagram PNGs written to: " + diagramsDir.toAbsolutePath()
                        + " (" + pngs.size() + " file(s))");
            } catch (IOException e) {
                log.error("Full-flow PNG export failed: {}", e.getMessage());
            }
        });

        // Generate class diagrams Markdown file
        Path classesFile = ClassDiagramRenderer.classesOutputPath(args.getOutputFile());
        String classesDiagram = new ClassDiagramRenderer().render(
                matchResult.flows(), callGraphs, args.getRootPath());
        Files.writeString(classesFile, classesDiagram);

        // Generate PNG class diagrams if --class-diagrams-dir was provided
        args.getClassDiagramsDir().ifPresent(diagramsDir -> {
            try {
                List<Path> pngs = new ClassDiagramPngExporter().export(
                        matchResult.flows(), callGraphs, diagramsDir);
                System.out.println("Class diagram PNGs written to: " + diagramsDir.toAbsolutePath()
                        + " (" + pngs.size() + " file(s))");
            } catch (IOException e) {
                log.error("Class PNG export failed: {}", e.getMessage());
            }
        });

        // Generate application sections Markdown file (route tree + page→flows)
        Path sectionsFile = SectionsRenderer.sectionsOutputPath(args.getOutputFile());
        String sectionsDoc = new SectionsRenderer().render(angularProject, matchResult);
        Files.writeString(sectionsFile, sectionsDoc);

        // Generate frontend pages catalog (detailed page specifications).
        // (componentsByName / pageRoutes / unroutedFallback were computed above for the graph.)
        Path pagesFile = FrontendPagesRenderer.pagesOutputPath(args.getOutputFile());

        // Load i18n catalog (ngx-translate / transloco) so form-field labels declared as
        // translation keys resolve to real messages in the pages catalog.
        AngularI18nCatalog i18nCatalog = angularProject.getFramework() == FrontendFramework.ANGULAR
                ? AngularI18nCatalog.load(args.getAngularProjectPath())
                : AngularI18nCatalog.empty();

        String pagesDoc = new FrontendPagesRenderer(i18nCatalog).render(
                pageRoutes, componentsByName, matchResult, angularProject.getServices(), unroutedFallback,
                angularProject.getFramework(), angularProject.getLayoutsByPage());
        Files.writeString(pagesFile, pagesDoc);

        // Generate site map (hierarchical route tree with components and tabs)
        Path sitemapFile = SitemapRenderer.sitemapOutputPath(args.getOutputFile());
        String sitemapDoc = new SitemapRenderer().render(pageRoutes, componentsByName, unroutedFallback);
        Files.writeString(sitemapFile, sitemapDoc);

        // Generate frontend-pages ZIP (per-page docs + sitemap index + architecture index)
        Path pagesZipFile = FrontendPagesZipExporter.zipOutputPath(args.getOutputFile());
        int pagesZipCount = new FrontendPagesZipExporter().export(
                pageRoutes, componentsByName, angularProject.getServices(),
                matchResult, pagesZipFile, unroutedFallback, angularProject.getFramework(), i18nCatalog,
                angularProject.getLayoutsByPage());

        // Generate the same content unzipped, under indexed_specs/ (per-element index + detail docs),
        // each page document carrying its bidirectional "## Related" cross-references (Page⟷Flow).
        new FrontendPagesZipExporter().exportToDirectory(
                pageRoutes, componentsByName, angularProject.getServices(),
                matchResult, args.getIndexedSpecsDir(), unroutedFallback, angularProject.getFramework(), i18nCatalog,
                (route, slug) -> {
                    ComponentInfo comp = componentsByName.get(route.getComponentName());
                    String originRel = "indexed_specs/frontend-pages/" + slug + ".md";
                    return relatedRenderer.render(xref.relatedForPage(comp), originRel);
                },
                angularProject.getLayoutsByPage());

        // Analyze Spring Security configuration and build security matrix
        List<SecurityRule> securityRules = SecurityConfigAnalyzer.analyzeSecurityConfig(
                args.getJavaProjectPath());
        List<SecurityRule> uiFragmentRules = angularProject.getFramework() == FrontendFramework.JSP_JQUERY
                ? extractJspUiFragmentRules(pageRoutes, componentsByName)
                : List.of();
        SecurityMatrix securityMatrix = SecurityMatrixBuilder.build(endpoints, securityRules, uiFragmentRules);
        Path securityMatrixFile = SecurityMatrixRenderer.securityMatrixOutputPath(args.getOutputFile());
        String securityMatrixDoc = new SecurityMatrixRenderer().render(securityMatrix);
        Files.writeString(securityMatrixFile, securityMatrixDoc);

        // Render the exception catalog (exceptions were extracted above for the cross-ref graph).
        Path errorCatalogFile = ErrorCatalogRenderer.errorCatalogOutputPath(args.getOutputFile());
        String errorCatalogDoc = new ErrorCatalogRenderer().render(exceptions);
        Files.writeString(errorCatalogFile, errorCatalogDoc);

        // Per-exception index + detail documents under indexed_specs/, same threshold logic.
        Path errorCatalogIndexFile = null;
        if (exceptions.size() >= args.getIndexDetailThreshold()) {
            ErrorCatalogIndexRenderer ecRenderer = new ErrorCatalogIndexRenderer();
            errorCatalogIndexFile = args.getIndexedSpecsDir().resolve("index-spec-error-catalog.md");
            Files.writeString(errorCatalogIndexFile, ecRenderer.renderCategoryIndex(exceptions));
            Path detailedExceptionsDir = args.getIndexedSpecsDir().resolve("detailed_exceptions");
            Files.createDirectories(detailedExceptionsDir);
            for (ExceptionInfo exc : exceptions) {
                String originRel = "indexed_specs/detailed_exceptions/" + ecRenderer.exceptionSlug(exc) + ".md";
                String related = relatedRenderer.render(xref.relatedForException(exc), originRel);
                String detail = ecRenderer.renderExceptionDetail(exc, related);
                Files.writeString(detailedExceptionsDir.resolve(ecRenderer.exceptionSlug(exc) + ".md"), detail);
            }
        }

        // Extract field traceability (UI ↔ DTO ↔ Entity ↔ Database)
        Map<String, Map<String, String>> uiLabels = extractUiLabels(angularProject, i18nCatalog);
        Map<String, List<CalculationEvidence>> frontendCalculations =
                extractFrontendCalculations(angularProject);
        TraceabilityExtractor traceabilityExtractor = new TraceabilityExtractor();
        List<TraceabilityMapping> traceabilityMappings = traceabilityExtractor.extract(
                persistenceMappings,
                angularProject.getModels(),
                uiLabels,
                allCUs,
                frontendCalculations);
        Path traceabilityFile = TraceabilityRenderer.traceabilityOutputPath(args.getOutputFile());
        String traceabilityDoc = new TraceabilityRenderer().render(
                traceabilityMappings, traceabilityExtractor.getOrphanCalculations());
        Files.writeString(traceabilityFile, traceabilityDoc);

        // Render the unified data model (tables, columns, relationships — see step 5b)
        Path dataModelFile = DatabaseSchemaRenderer.dataModelOutputPath(args.getOutputFile());
        DatabaseSchemaRenderer dataModelRenderer = new DatabaseSchemaRenderer();
        String dataModelDoc = dataModelRenderer.render(databaseSchema);
        Files.writeString(dataModelFile, dataModelDoc);

        // Per-table index + detail documents under indexed_specs/, only once there are
        // enough tables that loading the whole data-model.md would waste AI context.
        Path dataModelIndexFile = null;
        if (databaseSchema.getTablesByName().size() >= args.getIndexDetailThreshold()) {
            dataModelIndexFile = args.getIndexedSpecsDir().resolve("index-spec-data-model.md");
            Files.writeString(dataModelIndexFile, dataModelRenderer.renderCategoryIndex(databaseSchema));
            Path detailedTablesDir = args.getIndexedSpecsDir().resolve("detailed_tables");
            Files.createDirectories(detailedTablesDir);
            for (var table : databaseSchema.getTablesByName().values()) {
                String originRel = "indexed_specs/detailed_tables/" + dataModelRenderer.tableSlug(table) + ".md";
                String related = relatedRenderer.render(xref.relatedForTable(table, databaseSchema), originRel);
                String detail = dataModelRenderer.renderTableDetail(table, databaseSchema, related);
                Files.writeString(detailedTablesDir.resolve(dataModelRenderer.tableSlug(table) + ".md"), detail);
            }
        }

        // Per-mapping index + detail documents under indexed_specs/, same threshold logic.
        // Field Traceability (UI ↔ DTO ↔ Entity ↔ Database) is merged in here rather than
        // becoming its own indexed category: it shares the exact same DTO+Entity identity as
        // a persistence mapping, so a separate category would duplicate the whole field table
        // and add a hop for no benefit. Only the incremental content — the UI Label column and
        // calculation evidence — gets folded into the same detail document.
        Map<String, TraceabilityMapping> traceabilityByDtoEntity = traceabilityMappings.stream()
                .collect(Collectors.toMap(
                        tm -> tm.getDtoSimpleName() + "|" + tm.getEntitySimpleName(), tm -> tm, (a, b) -> a));
        Path persistenceMappingsIndexFile = null;
        if (persistenceMappings.size() >= args.getIndexDetailThreshold()) {
            PersistenceMappingIndexRenderer pmRenderer = new PersistenceMappingIndexRenderer();
            persistenceMappingsIndexFile = args.getIndexedSpecsDir().resolve("index-spec-persistence-mappings.md");
            Files.writeString(persistenceMappingsIndexFile, pmRenderer.renderCategoryIndex(persistenceMappings));
            Path detailedMappingsDir = args.getIndexedSpecsDir().resolve("detailed_persistence_mappings");
            Files.createDirectories(detailedMappingsDir);
            for (var pm : persistenceMappings) {
                String originRel = "indexed_specs/detailed_persistence_mappings/" + pmRenderer.mappingSlug(pm) + ".md";
                String related = relatedRenderer.render(xref.relatedForMapping(pm), originRel);
                TraceabilityMapping traceability = traceabilityByDtoEntity.get(
                        pm.getDto().getSimpleName() + "|" + pm.getEntity().getSimpleName());
                String detail = pmRenderer.renderMappingDetail(pm, traceability, related);
                Files.writeString(detailedMappingsDir.resolve(pmRenderer.mappingSlug(pm) + ".md"), detail);
            }
        }

        // Per-contract index + detail documents under indexed_specs/, same threshold logic.
        // (contracts and dcRenderer were built above for the cross-reference graph.)
        Path dataContractsIndexFile = null;
        if (contracts.size() >= args.getIndexDetailThreshold()) {
            dataContractsIndexFile = args.getIndexedSpecsDir().resolve("index-spec-data-contracts.md");
            Files.writeString(dataContractsIndexFile, dcRenderer.renderCategoryIndex(contracts));
            Path detailedContractsDir = args.getIndexedSpecsDir().resolve("detailed_data_contracts");
            Files.createDirectories(detailedContractsDir);
            for (var contract : contracts) {
                String originRel = "indexed_specs/detailed_data_contracts/" + dcRenderer.contractSlug(contract) + ".md";
                String related = relatedRenderer.render(xref.relatedForContract(contract.javaType()), originRel);
                String detail = dcRenderer.renderContractDetail(contract, related);
                Files.writeString(detailedContractsDir.resolve(dcRenderer.contractSlug(contract) + ".md"), detail);
            }
        }

        // Per-route index + detail documents under indexed_specs/, same threshold logic.
        Path staticRoutesIndexFile = null;
        List<EndpointInfo> staticRoutes = endpoints.stream().filter(EndpointInfo::isStaticRoute).toList();
        if (staticRoutes.size() >= args.getIndexDetailThreshold()) {
            StaticRoutesIndexRenderer srRenderer = new StaticRoutesIndexRenderer();
            staticRoutesIndexFile = args.getIndexedSpecsDir().resolve("index-spec-static-routes.md");
            Files.writeString(staticRoutesIndexFile, srRenderer.renderCategoryIndex(staticRoutes, args.getRootPath()));
            Path detailedRoutesDir = args.getIndexedSpecsDir().resolve("detailed_static_routes");
            Files.createDirectories(detailedRoutesDir);
            for (EndpointInfo route : staticRoutes) {
                String detail = srRenderer.renderRouteDetail(route, args.getRootPath());
                Files.writeString(detailedRoutesDir.resolve(srRenderer.routeSlug(route) + ".md"), detail);
            }
        }

        // Generate sequence diagrams Markdown file
        Path seqFile = SequenceDiagramRenderer.sequenceOutputPath(args.getOutputFile());
        String seqDiagram = new SequenceDiagramRenderer().render(
                matchResult.flows(), callGraphs, jobCallGraphs, args.getRootPath());
        Files.writeString(seqFile, seqDiagram);

        // Generate PNG sequence diagrams if --sequence-diagrams-dir was provided
        args.getSequenceDiagramsDir().ifPresent(diagramsDir -> {
            try {
                List<Path> pngs = new SequenceDiagramPngExporter().export(
                        matchResult.flows(), callGraphs, jobCallGraphs, diagramsDir);
                System.out.println("Sequence diagram PNGs written to: " + diagramsDir.toAbsolutePath()
                        + " (" + pngs.size() + " file(s))");
            } catch (IOException e) {
                log.error("Sequence PNG export failed: {}", e.getMessage());
            }
        });

        // Generate the root index_specs.md — single entry point linking to every document
        // in full_specs/ and indexed_specs/, sized independently of application size.
        List<RootIndexRenderer.Entry> fullSpecsEntries = List.of(
                new RootIndexRenderer.Entry("Main Spec (API flows, data contracts, persistence, warnings)", args.getOutputFile()),
                new RootIndexRenderer.Entry("API Flow Diagrams", flowsFile),
                new RootIndexRenderer.Entry("API Flow Diagrams (full, unfiltered)", flowsFullFile),
                new RootIndexRenderer.Entry("Sequence Diagrams", seqFile),
                new RootIndexRenderer.Entry("Class Diagrams", classesFile),
                new RootIndexRenderer.Entry("Application Sections", sectionsFile),
                new RootIndexRenderer.Entry("Frontend Pages", pagesFile),
                new RootIndexRenderer.Entry("Site Map", sitemapFile),
                new RootIndexRenderer.Entry("Security Matrix", securityMatrixFile),
                new RootIndexRenderer.Entry("Error Catalog", errorCatalogFile),
                new RootIndexRenderer.Entry("Field Traceability", traceabilityFile),
                new RootIndexRenderer.Entry("Data Model (tables, columns, relationships)", dataModelFile)
        );
        List<RootIndexRenderer.Entry> indexedSpecsEntries = new ArrayList<>(List.of(
                new RootIndexRenderer.Entry("API Flows Index", args.getIndexedSpecsDir().resolve("index-spec-flows.md")),
                new RootIndexRenderer.Entry("Frontend Pages Index", args.getIndexedSpecsDir().resolve("index-spec-frontend-pages.md")),
                new RootIndexRenderer.Entry("Site Map Index", args.getIndexedSpecsDir().resolve("index-spec-sitemap.md"))
        ));
        if (dataModelIndexFile != null) {
            indexedSpecsEntries.add(new RootIndexRenderer.Entry("Data Model Index (per-table)", dataModelIndexFile));
        }
        if (persistenceMappingsIndexFile != null) {
            indexedSpecsEntries.add(new RootIndexRenderer.Entry(
                    "Persistence Mappings Index (per-mapping)", persistenceMappingsIndexFile));
        }
        if (dataContractsIndexFile != null) {
            indexedSpecsEntries.add(new RootIndexRenderer.Entry(
                    "Data Contracts Index (per-contract)", dataContractsIndexFile));
        }
        if (staticRoutesIndexFile != null) {
            indexedSpecsEntries.add(new RootIndexRenderer.Entry(
                    "SPA / Static Routes Index (per-route)", staticRoutesIndexFile));
        }
        if (errorCatalogIndexFile != null) {
            indexedSpecsEntries.add(new RootIndexRenderer.Entry(
                    "Error Catalog Index (per-exception)", errorCatalogIndexFile));
        }
        String rootIndexDoc = new RootIndexRenderer().render(args.getBaseOutputDir(), fullSpecsEntries, indexedSpecsEntries);
        Files.writeString(args.getRootIndexFile(), rootIndexDoc);

        log.info("=== Analysis complete ===");
        System.out.println();
        System.out.println("Index specs (start here): " + args.getRootIndexFile().toAbsolutePath());
        System.out.println("Output written to:      " + args.getOutputFile().toAbsolutePath());
        System.out.println("Flow diagrams:          " + flowsFile.toAbsolutePath());
        System.out.println("Flow diagrams (full):   " + flowsFullFile.toAbsolutePath());
        System.out.println("Flow diagrams (zip):    " + flowsZipFile.toAbsolutePath()
                + " (" + zipEntries + " flow(s))");
        System.out.println("Application sections:   " + sectionsFile.toAbsolutePath());
        System.out.println("Frontend pages:         " + pagesFile.toAbsolutePath());
        System.out.println("Site map:               " + sitemapFile.toAbsolutePath());
        System.out.println("Frontend pages (zip):   " + pagesZipFile.toAbsolutePath()
                + " (" + pagesZipCount + " page(s))");
        System.out.println("Security matrix:        " + securityMatrixFile.toAbsolutePath());
        System.out.println("Error catalog:          " + errorCatalogFile.toAbsolutePath());
        System.out.println("Field traceability:     " + traceabilityFile.toAbsolutePath());
        System.out.println("Data model:             " + dataModelFile.toAbsolutePath()
                + " (" + databaseSchema.getTablesByName().size() + " table(s))");
        System.out.println("Sequence diagrams:      " + seqFile.toAbsolutePath());
        System.out.println("Class diagrams:         " + classesFile.toAbsolutePath());
        System.out.println("  Endpoints found:     " + endpoints.size());
        System.out.println("  Matched flows:       " + matchResult.flows().size());
        System.out.println("  Persistence mappings:" + persistenceMappings.size());
        System.out.println("  Warnings:            "
                + (matchResult.unmatchedAngularCalls().size()
                   + matchResult.unmatchedJavaEndpoints().size()
                   + callGraphBuilder.getCycleWarnings().size()));
    }

    // -----------------------------------------------------------------------
    // Cross-reference graph (Phase 3a: the four exact edges)
    // -----------------------------------------------------------------------

    /**
     * Registers every table / persistence mapping / data contract / flow as a graph node,
     * with its detail-document path relative to the base output dir (null when the category
     * fell below the index-detail threshold, so backlinks still show the name as text).
     * Tables that are not indexed fall back to the {@code data-model.md} anchor.
     */
    private static CrossReferenceGraph buildCrossReferenceGraph(
            CliArguments args, DatabaseSchema schema, List<PersistenceMapping> mappings,
            List<DataContractIndexRenderer.Contract> contracts, List<FlowZipExporter.FlowRef> flowRefs,
            Map<MatchedFlow, java.util.Set<String>> inferredFlowEntities,
            Map<MatchedFlow, java.util.Set<String>> flowClasses, List<ExceptionInfo> exceptions,
            List<FrontendPagesZipExporter.PageRef> pageRefs) {

        CrossReferenceGraph g = new CrossReferenceGraph();
        g.setFlowInferredEntities(inferredFlowEntities);
        g.setFlowClasses(flowClasses);
        Path base = args.getBaseOutputDir();
        Path isd = args.getIndexedSpecsDir();
        int threshold = args.getIndexDetailThreshold();

        // Tables — fall back to data-model.md#table-<slug> when not indexed (stable anchor).
        DatabaseSchemaRenderer dmr = new DatabaseSchemaRenderer();
        Path dataModelFile = DatabaseSchemaRenderer.dataModelOutputPath(args.getOutputFile());
        boolean tablesIndexed = schema.getTablesByName().size() >= threshold;
        for (SchemaTable t : schema.getTablesByName().values()) {
            String slug = dmr.tableSlug(t);
            String path;
            String anchor;
            if (tablesIndexed) {
                path = relToBase(base, isd.resolve("detailed_tables").resolve(slug + ".md"));
                anchor = null;
            } else {
                path = relToBase(base, dataModelFile);
                anchor = "table-" + slug;
            }
            CrossReferenceGraph.Node node = new CrossReferenceGraph.Node(
                    CrossReferenceGraph.Category.TABLE, t.qualifiedName(), path, anchor);
            g.register(CrossReferenceGraph.tableKey(t.qualifiedName()), node);
            if (t.getEntitySimpleName() != null) {
                g.register(CrossReferenceGraph.entityKey(t.getEntitySimpleName()), node);
            }
        }

        // Persistence mappings — no dedicated full-spec anchor, so text-only when not indexed.
        PersistenceMappingIndexRenderer pmr = new PersistenceMappingIndexRenderer();
        boolean mappingsIndexed = mappings.size() >= threshold;
        for (PersistenceMapping pm : mappings) {
            String path = mappingsIndexed
                    ? relToBase(base, isd.resolve("detailed_persistence_mappings").resolve(pmr.mappingSlug(pm) + ".md"))
                    : null;
            String label = pm.getDto().getSimpleName() + " ↔ " + pm.getEntity().getSimpleName();
            CrossReferenceGraph.Node node = new CrossReferenceGraph.Node(
                    CrossReferenceGraph.Category.PERSISTENCE_MAPPING, label, path, null);
            g.register(CrossReferenceGraph.dtoKey(pm.getDto().getSimpleName()), node);
            g.register(CrossReferenceGraph.entityKey(pm.getEntity().getSimpleName()), node);
        }

        // Data contracts — text-only when not indexed (they live only in the monolithic api-spec.md).
        DataContractIndexRenderer dcr = new DataContractIndexRenderer();
        boolean contractsIndexed = contracts.size() >= threshold;
        for (DataContractIndexRenderer.Contract c : contracts) {
            String path = contractsIndexed
                    ? relToBase(base, isd.resolve("detailed_data_contracts").resolve(dcr.contractSlug(c) + ".md"))
                    : null;
            String label = c.javaType() + " ↔ " + c.tsModel().getName();
            CrossReferenceGraph.Node node = new CrossReferenceGraph.Node(
                    CrossReferenceGraph.Category.DATA_CONTRACT, label, path, null);
            g.register(CrossReferenceGraph.dtoKey(c.javaType()), node);
        }

        // Flows — always indexed (FlowZipExporter runs unconditionally).
        for (FlowZipExporter.FlowRef fr : flowRefs) {
            String path = relToBase(base, isd.resolve(fr.relPathWithinDir()));
            CrossReferenceGraph.Node node = new CrossReferenceGraph.Node(
                    CrossReferenceGraph.Category.FLOW, fr.label(), path, null);
            String resp = fr.flow().getJavaEndpoint().getResponseType();
            String body = fr.flow().getJavaEndpoint().getBodyParameterType();
            if (resp != null) g.register(CrossReferenceGraph.dtoKey(resp), node);
            if (body != null) g.register(CrossReferenceGraph.dtoKey(body), node);
            // Heuristic Flow⟷Table: register under each entity reached via repositories
            for (String entity : inferredFlowEntities.getOrDefault(fr.flow(), java.util.Set.of())) {
                g.register(CrossReferenceGraph.entityInferredKey(entity), node);
            }
            // Heuristic Exception→Flow: register under each class in the flow's call graph
            for (String cls : flowClasses.getOrDefault(fr.flow(), java.util.Set.of())) {
                g.register(CrossReferenceGraph.classKey(cls), node);
            }
            // Heuristic Page⟷Flow: register under the Angular service backing this flow
            if (fr.flow().getAngularService() != null) {
                g.register(CrossReferenceGraph.ngServiceKey(fr.flow().getAngularService().getClassName()), node);
            }
        }

        // Frontend pages — always indexed (FrontendPagesZipExporter runs unconditionally).
        for (FrontendPagesZipExporter.PageRef pr : pageRefs) {
            String path = relToBase(base, isd.resolve("frontend-pages").resolve(pr.slug() + ".md"));
            CrossReferenceGraph.Node node = new CrossReferenceGraph.Node(
                    CrossReferenceGraph.Category.FRONTEND_PAGE, pr.label(), path, null);
            if (pr.component() != null) {
                for (String service : pr.component().getInjectedServices()) {
                    g.register(CrossReferenceGraph.ngServiceKey(service), node);
                }
            }
        }

        // Exceptions — text-only fallback to errors-catalog.md when not indexed (no stable anchor).
        ErrorCatalogIndexRenderer ecr = new ErrorCatalogIndexRenderer();
        Path errorCatalogFile = ErrorCatalogRenderer.errorCatalogOutputPath(args.getOutputFile());
        boolean exceptionsIndexed = exceptions.size() >= threshold;
        for (ExceptionInfo exc : exceptions) {
            String path = exceptionsIndexed
                    ? relToBase(base, isd.resolve("detailed_exceptions").resolve(ecr.exceptionSlug(exc) + ".md"))
                    : relToBase(base, errorCatalogFile);
            CrossReferenceGraph.Node node = new CrossReferenceGraph.Node(
                    CrossReferenceGraph.Category.EXCEPTION,
                    exc.getErrorCodeOrDefault() + " " + exc.exceptionClassName(), path, null);
            for (String cls : CrossReferenceGraph.throwingClasses(exc)) {
                g.register(CrossReferenceGraph.classKey(cls), node);
            }
        }

        return g;
    }

    /** Simple class names appearing in each flow's call graph (heuristic). */
    private static Map<MatchedFlow, java.util.Set<String>> collectFlowClasses(
            List<FlowZipExporter.FlowRef> flowRefs, Map<EndpointInfo, CallNode> callGraphs) {
        Map<MatchedFlow, java.util.Set<String>> result = new LinkedHashMap<>();
        for (FlowZipExporter.FlowRef fr : flowRefs) {
            CallNode root = callGraphs.get(fr.flow().getJavaEndpoint());
            if (root == null) continue;
            java.util.Set<String> classes = new java.util.LinkedHashSet<>();
            collectClassNames(root, classes);
            if (!classes.isEmpty()) result.put(fr.flow(), classes);
        }
        return result;
    }

    private static void collectClassNames(CallNode node, java.util.Set<String> acc) {
        if (node.getClassName() != null) acc.add(CrossReferenceGraph.simpleName(node.getClassName()));
        for (CallNode child : node.getCallees()) collectClassNames(child, acc);
    }

    /** Entities each flow reaches through its call-graph {@code REPOSITORY} nodes (heuristic). */
    private static Map<MatchedFlow, java.util.Set<String>> inferFlowEntities(
            List<FlowZipExporter.FlowRef> flowRefs, Map<EndpointInfo, CallNode> callGraphs) {
        Map<MatchedFlow, java.util.Set<String>> result = new LinkedHashMap<>();
        for (FlowZipExporter.FlowRef fr : flowRefs) {
            CallNode root = callGraphs.get(fr.flow().getJavaEndpoint());
            if (root == null) continue;
            java.util.Set<String> entities = new java.util.LinkedHashSet<>();
            collectRepositoryEntities(root, entities);
            if (!entities.isEmpty()) result.put(fr.flow(), entities);
        }
        return result;
    }

    private static void collectRepositoryEntities(CallNode node, java.util.Set<String> acc) {
        if (node.getRole() == CallNode.Role.REPOSITORY) {
            String entity = repositoryToEntity(node.getClassName());
            if (entity != null) acc.add(entity);
        }
        for (CallNode child : node.getCallees()) collectRepositoryEntities(child, acc);
    }

    /** "EmployeeRepository" → "Employee"; returns null when the name isn't a conventional repo. */
    private static String repositoryToEntity(String repoClass) {
        if (repoClass == null) return null;
        String simple = repoClass.contains(".") ? repoClass.substring(repoClass.lastIndexOf('.') + 1) : repoClass;
        for (String suffix : List.of("Repository", "Repo", "DAO", "Dao")) {
            if (simple.endsWith(suffix) && simple.length() > suffix.length()) {
                return simple.substring(0, simple.length() - suffix.length());
            }
        }
        return null;
    }

    private static String relToBase(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }

    // Suffixes stripped from a DTO's simple name before correlating it to an entity.
    private static final List<String> DTO_NAME_SUFFIXES = List.of("Dto", "DTO", "Request", "Response");
    // Suffixes stripped from an entity's simple name before correlating it to a DTO — the
    // symmetric half of the fix: a codebase that suffixes its persistence classes instead of
    // (or as well as) its transfer classes correlates just as reliably either way.
    private static final List<String> ENTITY_NAME_SUFFIXES = List.of("JpaEntity", "EntityBean", "Entity");

    /**
     * True when a DTO and an entity's simple names correlate closely enough to attempt a
     * field-level mapping between them. Package-visible (not private) so it can be exercised
     * directly rather than only indirectly through a full pipeline run.
     */
    static boolean dtoAndEntityNamesCorrelate(String dtoSimpleName, String entitySimpleName) {
        String dtoBase = stripSuffix(dtoSimpleName, DTO_NAME_SUFFIXES);
        String entityBase = stripSuffix(entitySimpleName, ENTITY_NAME_SUFFIXES);
        return entityBase.equalsIgnoreCase(dtoBase)
                || entitySimpleName.equalsIgnoreCase(dtoBase)
                || dtoSimpleName.startsWith(entitySimpleName);
    }

    /** Strips the first matching suffix (case-insensitive) from {@code name}, or returns it unchanged. */
    private static String stripSuffix(String name, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (name.length() > suffix.length()
                    && name.regionMatches(true, name.length() - suffix.length(), suffix, 0, suffix.length())) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    // -----------------------------------------------------------------------
    // Data model extraction (live DB / SQL migrations / JPA entities)
    // -----------------------------------------------------------------------

    private static DatabaseSchema buildDatabaseSchema(CliArguments args, List<EntityInfo> entities,
                                                       List<CompilationUnit> allCUs) {
        List<String> warnings = new ArrayList<>();

        // 1. Resolve database connection info from application.yml/properties + CLI overrides.
        //    Absence of Spring datasource config is a normal case (not every analyzed project
        //    is a Spring Boot app, or it may not use a relational database at all) — degrade
        //    quietly to SQL-migrations-and-JPA-only rather than failing the whole run.
        DatabaseConnectionInfo connectionInfo = null;
        try {
            DatabaseConfigExtractor configExtractor = new DatabaseConfigExtractor(
                    args.getJavaProjectPath(),
                    args.getDbProfile().orElse(null),
                    args.getDbUrl().orElse(null),
                    args.getDbType().orElse(null),
                    args.getDbUser().orElse(null),
                    args.getDbPassword().orElse(null));
            connectionInfo = configExtractor.extract();
        } catch (RuntimeException e) {
            log.info("No database configuration found ({}). Data model will be built from "
                    + "SQL migrations and JPA entities only.", e.getMessage());
        }

        // 2. Live introspection — only attempted if we have connection info and it wasn't disabled.
        RelationalSchema liveSchema = RelationalSchema.empty();
        if (connectionInfo != null && !args.isSkipDbConnection()) {
            LiveSchemaIntrospector.Result liveResult = new LiveSchemaIntrospector().introspect(connectionInfo);
            liveSchema = liveResult.schema();
            if (liveResult.warning() != null) {
                warnings.add(liveResult.warning());
            }
        } else if (connectionInfo != null) {
            log.info("Live database introspection skipped (--skip-db-connection).");
        }

        // 3. SQL migrations — the offline structural fallback, parsed regardless of DB connectivity
        //    (used directly if the live DB is unreachable, or to cross-check/merge if it is).
        RelationalSchema sqlSchema = new FlywayMigrationParser(args.getJavaProjectPath()).parse();

        // 4. Merge everything with provenance tagging.
        DatabaseSchema schema = new SchemaBuilder().build(entities, allCUs, liveSchema, sqlSchema, warnings);
        log.info("Data model built: {} table(s), {} relationship(s), source(s): {}",
                schema.getTablesByName().size(), schema.getRelationships().size(), schema.getSourcesUsed());
        return schema;
    }

    // -----------------------------------------------------------------------
    // UI Label extraction
    // -----------------------------------------------------------------------

    private static Map<String, List<CalculationEvidence>> extractFrontendCalculations(
            AngularProject angularProject) {
        Map<String, List<CalculationEvidence>> merged = new LinkedHashMap<>();
        FrontendCalculationDetector detector = new FrontendCalculationDetector();

        for (ComponentInfo component : angularProject.getComponents()) {
            try {
                Map<String, List<CalculationEvidence>> perComponent =
                        detector.detectInComponent(component.getFilePath());
                perComponent.forEach((field, evidences) ->
                        merged.computeIfAbsent(field, k -> new ArrayList<>()).addAll(evidences));
            } catch (Exception e) {
                log.debug("Could not detect frontend calculations in {}: {}",
                        component.getFilePath(), e.getMessage());
            }
        }

        log.info("Frontend calculation detection: {} field(s) with value computations", merged.size());
        return merged;
    }

    /** Package-visible for direct unit testing (see {@code ApplicationUiLabelsTest}). */
    static Map<String, Map<String, String>> extractUiLabels(AngularProject angularProject,
                                                             AngularI18nCatalog i18nCatalog) {
        Map<String, Map<String, String>> result = new HashMap<>();
        AngularFormFieldExtractor extractor = new AngularFormFieldExtractor();

        for (ComponentInfo component : angularProject.getComponents()) {
            try {
                Map<String, FieldLabel> labels = extractor.extract(component.getFilePath());
                if (!labels.isEmpty()) {
                    Map<String, String> fieldLabelMap = new LinkedHashMap<>();
                    labels.forEach((fieldName, label) -> {
                        String displayLabel = label.literal() != null
                                ? label.literal()
                                : i18nCatalog.resolve(label.i18nKey()).orElse(label.i18nKey());
                        if (displayLabel != null) {
                            fieldLabelMap.put(fieldName, displayLabel);
                        }
                    });
                    if (!fieldLabelMap.isEmpty()) {
                        result.put(component.getClassName(), fieldLabelMap);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not extract UI labels from {}: {}", component.getFilePath(), e.getMessage());
            }
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Java source scanning
    // -----------------------------------------------------------------------

    /** Package-visible for direct unit testing (see {@code ParseJavaProjectTest}). */
    static List<CompilationUnit> parseJavaProject(Path root, JavaParser parser) throws IOException {
        List<CompilationUnit> result = new ArrayList<>();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".java"))
                  .filter(p -> !isUnderMavenBuildOutput(p, normalizedRoot))
                  .forEach(file -> {
                      try {
                          ParseResult<CompilationUnit> pr = parser.parse(file);
                          pr.getResult().ifPresent(cu -> {
                              cu.setStorage(file);
                              result.add(cu);
                          });
                          if (!pr.isSuccessful()) {
                              log.warn("Parse errors in {}: {}", file, pr.getProblems());
                          }
                      } catch (IOException e) {
                          log.warn("Cannot read file {}: {}", file, e.getMessage());
                      }
                  });
        }
        return result;
    }

    /**
     * Whether {@code file} sits under a Maven build-output directory ({@code target/}) of the
     * project rooted at {@code normalizedRoot}. Checked as an actual path segment relative to the
     * project root — never as a substring of the absolute path — so a project simply checked out
     * somewhere whose own path happens to contain "target" (a directory named
     * {@code target-app}, a fixture living under this very project's own {@code target/test-classes/}
     * at test time) is never mistaken for build output and silently excluded.
     */
    private static boolean isUnderMavenBuildOutput(Path file, Path normalizedRoot) {
        Path relative = normalizedRoot.relativize(file.toAbsolutePath().normalize());
        for (int i = 0; i < relative.getNameCount() - 1; i++) { // exclude the file's own name — only ancestor dirs count
            if ("target".equals(relative.getName(i).toString())) return true;
        }
        return false;
    }

    /**
     * JSP {@code <sec:authorize>} UI-fragment rules (Phase 02), across every JSP page in the
     * route tree — {@link JspAuthorizationExtractor} extracts each view's rules on its own; this
     * is the pipeline wiring that actually feeds them into the security matrix, using each page's
     * own route path so a reader can tell which page a rule gates.
     */
    private static List<SecurityRule> extractJspUiFragmentRules(List<RouteNode> routes,
                                                                 Map<String, ComponentInfo> componentsByName) {
        JspAuthorizationExtractor extractor = new JspAuthorizationExtractor();
        List<SecurityRule> rules = new ArrayList<>();
        collectJspUiFragmentRules(routes, componentsByName, extractor, rules);
        return rules;
    }

    private static void collectJspUiFragmentRules(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                                   JspAuthorizationExtractor extractor, List<SecurityRule> rules) {
        for (RouteNode route : routes) {
            if (route.getComponentName() != null) {
                ComponentInfo comp = componentsByName.get(route.getComponentName());
                if (comp != null && !JspRouteReconstructor.UNRESOLVED_FILE.equals(comp.getFilePath())) {
                    rules.addAll(extractor.extract(comp.getFilePath(), route.getPath()));
                }
            }
            collectJspUiFragmentRules(route.getChildren(), componentsByName, extractor, rules);
        }
    }

    // -----------------------------------------------------------------------
    // Name collision detection
    // -----------------------------------------------------------------------

    private static List<String> detectNameCollisions(List<EndpointInfo> endpoints) {
        Map<String, List<String>> simpleToFqn = new LinkedHashMap<>();
        for (EndpointInfo ep : endpoints) {
            String fqn = ep.getControllerClass();
            String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
            simpleToFqn.computeIfAbsent(simple, k -> new ArrayList<>()).add(fqn);
        }
        List<String> collisions = new ArrayList<>();
        simpleToFqn.forEach((simple, fqns) -> {
            if (fqns.stream().distinct().count() > 1) {
                collisions.add("Class name '" + simple + "' is ambiguous — qualified names: "
                        + fqns.stream().distinct().collect(Collectors.joining(", ")));
            }
        });
        return collisions;
    }
}
