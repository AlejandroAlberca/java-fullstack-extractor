package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.HttpCallInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.angular.model.RouteOrigin;
import com.devmanchego.contextextractor.angular.model.ServiceInfo;
import com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog;
import com.devmanchego.contextextractor.angular.i18n.ConventionValidationMessageResolver;
import com.devmanchego.contextextractor.angular.template.AngularFormFieldExtractor;
import com.devmanchego.contextextractor.common.FieldLabel;
import com.devmanchego.contextextractor.angular.template.AngularEmbeddedComponentResolver;
import com.devmanchego.contextextractor.angular.template.AngularValidationMessageTemplateExtractor;
import com.devmanchego.contextextractor.common.FormValidationExtractorFactory;
import com.devmanchego.contextextractor.common.FormValidationExtractorStrategy;
import com.devmanchego.contextextractor.common.TemplateEventExtractorFactory;
import com.devmanchego.contextextractor.common.TemplateEventExtractorStrategy;
import com.devmanchego.contextextractor.common.ValidationMessage;
import com.devmanchego.contextextractor.common.ValidationMessageStatus;
import com.devmanchego.contextextractor.common.ValidatorInfo;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.jsp.DomIdentifierCorrelator;
import com.devmanchego.contextextractor.jsp.JQueryAjaxCallExtractor;
import com.devmanchego.contextextractor.jsp.JspAjaxCallReport;
import com.devmanchego.contextextractor.jsp.JspFormFieldExtractor;
import com.devmanchego.contextextractor.matching.MatchedFlow;
import com.devmanchego.contextextractor.react.template.ReactFormFieldExtractor;
import com.devmanchego.contextextractor.vue.template.VueFormFieldExtractor;
import com.devmanchego.contextextractor.matching.EndpointMatcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders spec-frontend-pages.md: detailed catalog of all frontend pages (routed components)
 * with their API dependencies, injected services, child components, and business-trigger interactions.
 */
public final class FrontendPagesRenderer {

    private final AngularI18nCatalog i18nCatalog;
    private final ConventionValidationMessageResolver conventionResolver = new ConventionValidationMessageResolver();
    private final AngularValidationMessageTemplateExtractor templateMessageExtractor =
            new AngularValidationMessageTemplateExtractor();
    /**
     * Render-scoped: populated from the route tree at the start of {@link #render}, and by
     * {@link FrontendPagesZipExporter} before it drives {@link #renderPage} directly. Defaults to
     * the no-op so a renderer used without that wiring simply omits embedded-field tables.
     */
    private AngularEmbeddedComponentResolver embeddedResolver = AngularEmbeddedComponentResolver.empty();

    /** Creates a renderer with no i18n resolution — form-field i18n keys are shown unresolved. */
    public FrontendPagesRenderer() {
        this(AngularI18nCatalog.empty());
    }

    /**
     * @param i18nCatalog catalog used to resolve form-field i18n keys to their message text;
     *                    pass {@link AngularI18nCatalog#empty()} to disable resolution.
     */
    public FrontendPagesRenderer(AngularI18nCatalog i18nCatalog) {
        this.i18nCatalog = i18nCatalog == null ? AngularI18nCatalog.empty() : i18nCatalog;
    }

    public static Path pagesOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-frontend-pages.md");
    }

    public String render(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                        EndpointMatcher.MatchResult matchResult) {
        return render(routes, componentsByName, matchResult, List.of(), false);
    }

    public String render(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                        EndpointMatcher.MatchResult matchResult, boolean unroutedFallback) {
        return render(routes, componentsByName, matchResult, List.of(), unroutedFallback);
    }

    /**
     * @param services         extracted Angular services, used to resolve which handler
     *                         methods trigger HTTP calls (business-trigger UI interactions).
     * @param unroutedFallback when true, {@code routes} was synthesized from components
     *                         (see {@link UnroutedPagesFallback}); an explanatory note is
     *                         emitted so the reader knows these are not confirmed navigable pages.
     * @param framework        the detected frontend framework, used to select
     *                         framework-specific extractors for forms and events
     */
    public String render(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                        EndpointMatcher.MatchResult matchResult, List<ServiceInfo> services,
                        boolean unroutedFallback, FrontendFramework framework) {
        return render(routes, componentsByName, matchResult, services, unroutedFallback, framework, Map.of());
    }

    /**
     * @param layoutsByPage component name → ordered layout chain (root-first); Next.js App
     *                      Router only. Pass {@code Map.of()} for frameworks without layout
     *                      nesting derivable from directory structure.
     */
    public String render(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                        EndpointMatcher.MatchResult matchResult, List<ServiceInfo> services,
                        boolean unroutedFallback, FrontendFramework framework,
                        Map<String, List<String>> layoutsByPage) {
        StringBuilder sb = new StringBuilder();
        sb.append("# FRONTEND PAGES\n\n");
        sb.append("*Detailed catalog of all frontend application pages with their dependencies, ")
          .append("services, child components, and business-trigger interactions.*\n\n");

        if (routes.isEmpty()) {
            sb.append("*No pages found.*\n");
            return sb.toString();
        }

        if (unroutedFallback) {
            sb.append(UnroutedPagesFallback.NOTE).append("\n\n");
        }

        // Build service→flows map for quick lookup
        Map<String, List<MatchedFlow>> flowsByService = buildFlowsByService(matchResult);
        Map<String, String> httpDescriptors = buildHttpMethodDescriptors(services);
        useEmbeddedComponentsFrom(routes, componentsByName, framework);

        // Render each page
        renderPages(sb, routes, componentsByName, flowsByService, httpDescriptors, framework, "", layoutsByPage);

        return sb.toString();
    }

    /**
     * Backwards-compatible overload that defaults to ANGULAR framework.
     */
    public String render(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                        EndpointMatcher.MatchResult matchResult, List<ServiceInfo> services,
                        boolean unroutedFallback) {
        return render(routes, componentsByName, matchResult, services, unroutedFallback, FrontendFramework.ANGULAR);
    }

    private void renderPages(StringBuilder sb, List<RouteNode> routes,
                            Map<String, ComponentInfo> componentsByName,
                            Map<String, List<MatchedFlow>> flowsByService,
                            Map<String, String> httpDescriptors,
                            FrontendFramework framework,
                            String parentPath,
                            Map<String, List<String>> layoutsByPage) {
        for (RouteNode route : routes) {
            String fullPath = joinPath(parentPath, route.getPath());

            // Render this page if it has a component (isPage() returns true)
            if (route.isPage()) {
                String componentName = route.getComponentName();
                ComponentInfo comp = componentsByName.get(componentName);
                List<String> layoutChain = layoutsByPage.getOrDefault(componentName, List.of());
                renderPage(sb, fullPath, componentName, comp, route, flowsByService, httpDescriptors, framework, layoutChain);
            }

            // Always recurse into children, even if parent has no component
            // (e.g., guarded routing groups: path='', canActivate=[...], children: [...])
            if (!route.getChildren().isEmpty()) {
                renderPages(sb, route.getChildren(), componentsByName, flowsByService, httpDescriptors, framework,
                        fullPath, layoutsByPage);
            }
        }
    }

    /** Package-visible: reused by {@link FrontendPagesZipExporter} to render a single page's own document. */
    void renderPage(StringBuilder sb, String path, String componentName,
                    ComponentInfo component, RouteNode route,
                    Map<String, List<MatchedFlow>> flowsByService,
                    Map<String, String> httpDescriptors,
                    FrontendFramework framework) {
        renderPage(sb, path, componentName, component, route, flowsByService, httpDescriptors, framework, List.of());
    }

    /** Overload carrying this page's layout chain (root-first; Next.js App Router only). */
    void renderPage(StringBuilder sb, String path, String componentName,
                    ComponentInfo component, RouteNode route,
                    Map<String, List<MatchedFlow>> flowsByService,
                    Map<String, String> httpDescriptors,
                    FrontendFramework framework,
                    List<String> layoutChain) {
        sb.append("### [ID: ").append(path.isEmpty() ? "/" : path).append("] ")
          .append(componentName).append("\n\n");

        // Description: humanized component name
        String description = humanize(componentName);
        sb.append("- **Description:** ").append(description).append("\n");
        renderOrigin(sb, route);

        if (layoutChain != null && !layoutChain.isEmpty()) {
            sb.append("- **Layout Chain:** ").append(String.join(" → ", layoutChain)).append("\n");
        }

        // API Dependencies: flows that use services injected in this component
        if (component != null) {
            List<String> apiDeps = extractApiDependencies(component, flowsByService);
            if (!apiDeps.isEmpty()) {
                sb.append("- **API Dependencies:** [").append(String.join(", ", apiDeps)).append("]\n");
            }

            // Injected Services
            if (!component.getInjectedServices().isEmpty()) {
                sb.append("- **Injected Services:** [").append(String.join(", ", component.getInjectedServices()))
                  .append("]\n");
            }

            // Form Fields: control name + label / i18n message + validation rules + resolved validation messages
            Map<String, List<ValidatorInfo>> formRules = extractFormValidation(component, framework);
            Map<String, FieldLabel> fieldLabels = extractFieldLabels(component, framework);
            Map<String, Map<String, ValidationMessage>> validationMessages =
                    resolveValidationMessages(component, formRules, framework);
            if (!formRules.isEmpty() || !fieldLabels.isEmpty()) {
                renderFormFields(sb, "Form Fields", formRules, fieldLabels, validationMessages);
            }

            renderEmbeddedComponentFields(sb, component, framework);

            if (framework == FrontendFramework.JSP_JQUERY) {
                renderAjaxCallSites(sb, new JspAjaxCallReport().of(component.getFilePath()));
                renderDomCorrelation(sb, new DomIdentifierCorrelator().correlate(component.getFilePath()));
            }

            // UI Interactions (business triggers)
            List<TemplateEventExtractorStrategy.Event> events = extractEvents(component, httpDescriptors, framework);
            if (!events.isEmpty()) {
                sb.append("- **UI Interactions:** \n");
                for (TemplateEventExtractorStrategy.Event e : events) {
                    sb.append("  - ").append(e.description()).append("\n");
                }
            }
        }

        // Child Components (direct children from routing)
        List<String> children = route.getChildren().stream()
                .filter(RouteNode::isPage)
                .map(RouteNode::getComponentName)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .toList();
        if (!children.isEmpty()) {
            sb.append("- **Child Components (Direct):** [").append(String.join(", ", children))
              .append("]\n");
        }

        sb.append("\n");
    }

    // -----------------------------------------------------------------------
    // Extraction helpers
    // -----------------------------------------------------------------------

    private List<String> extractApiDependencies(ComponentInfo component,
                                                Map<String, List<MatchedFlow>> flowsByService) {
        Set<String> deps = new HashSet<>();
        for (String service : component.getInjectedServices()) {
            List<MatchedFlow> flows = flowsByService.getOrDefault(service, List.of());
            for (MatchedFlow f : flows) {
                deps.add(f.getAngularCall().getHttpVerb() + " " + f.getJavaEndpoint().getPathTemplate());
            }
        }
        return new ArrayList<>(deps);
    }

    private Map<String, List<ValidatorInfo>> extractFormValidation(ComponentInfo component, FrontendFramework framework) {
        try {
            FormValidationExtractorStrategy extractor = FormValidationExtractorFactory.createFor(framework);
            return extractor.extract(component.getFilePath());
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Resolves form-control labels from the template. React and Vue detect only literal labels
     * (no i18n key) — see {@link ReactFormFieldExtractor} / {@link VueFormFieldExtractor} for why.
     * Other frameworks return an empty map rather than guessing.
     */
    private Map<String, FieldLabel> extractFieldLabels(ComponentInfo component, FrontendFramework framework) {
        try {
            return switch (framework) {
                case ANGULAR -> new AngularFormFieldExtractor().extract(component.getFilePath());
                case REACT, NEXTJS -> new ReactFormFieldExtractor().extract(component.getFilePath());
                case VUE2, VUE3, NUXT -> new VueFormFieldExtractor().extract(component.getFilePath());
                case JSP_JQUERY -> new JspFormFieldExtractor().extract(component.getFilePath());
                default -> Map.of();
            };
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Resolves each validator's error message, preferring real evidence over a guess: the
     * template correlation ({@link AngularValidationMessageTemplateExtractor}, which reads the
     * actual {@code *ngIf} error block) wins whenever it finds something for a given validator;
     * only when it finds nothing does the {@code errors.<field>.<validator>} naming-convention
     * guess ({@link ConventionValidationMessageResolver}) fill the gap. Angular-only, like
     * {@link #extractFieldLabels}: both resolvers and the i18n catalog they use are
     * Angular-specific today.
     */
    private Map<String, Map<String, ValidationMessage>> resolveValidationMessages(
            ComponentInfo component, Map<String, List<ValidatorInfo>> formRules, FrontendFramework framework) {
        if (framework != FrontendFramework.ANGULAR || formRules.isEmpty()) return Map.of();

        Map<String, Map<String, ValidationMessage>> fromTemplate;
        try {
            fromTemplate = templateMessageExtractor.extract(component.getFilePath(), i18nCatalog);
        } catch (Exception e) {
            fromTemplate = Map.of();
        }
        Map<String, Map<String, ValidationMessage>> fromConvention = conventionResolver.resolve(formRules, i18nCatalog);

        Map<String, Map<String, ValidationMessage>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<ValidatorInfo>> entry : formRules.entrySet()) {
            String field = entry.getKey();
            Map<String, ValidationMessage> templateForField = fromTemplate.getOrDefault(field, Map.of());
            Map<String, ValidationMessage> conventionForField = fromConvention.getOrDefault(field, Map.of());

            Map<String, ValidationMessage> perValidator = new LinkedHashMap<>();
            for (ValidatorInfo validator : entry.getValue()) {
                ValidationMessage fromTemplateMsg = templateForField.get(validator.name());
                perValidator.put(validator.name(), fromTemplateMsg != null
                        ? fromTemplateMsg
                        : conventionForField.getOrDefault(validator.name(), ValidationMessage.notFound()));
            }
            merged.put(field, perValidator);
        }
        return merged;
    }

    /**
     * Renders the combined "Form Fields" table: control name, resolved label, i18n key (if the
     * label comes from ngx-translate/transloco), validation rules, and — when at least one
     * validator has a resolved or literal message — a "Validation Messages" column. Fields are
     * listed in validation order first, then any label-only fields the validation extractor
     * didn't see.
     */
    /**
     * Emits one table per non-routed component this page embeds (e.g. {@code <app-address-form>}),
     * so fields the user plainly sees on the page are documented even though no route points at
     * the component declaring them.
     *
     * <p>Deliberately rendered as separate tables rather than merged into the page's own
     * "Form Fields" table: that table is keyed by bare field name, so merging would silently
     * collapse a page's {@code email} with an embedded component's {@code email} into one row.
     * A separate table also makes provenance structural instead of relying on the reader
     * noticing a column.
     */
    private void renderEmbeddedComponentFields(StringBuilder sb, ComponentInfo page, FrontendFramework framework) {
        for (ComponentInfo embedded : embeddedResolver.resolveEmbedded(page)) {
            Map<String, List<ValidatorInfo>> formRules = extractFormValidation(embedded, framework);
            Map<String, FieldLabel> fieldLabels = extractFieldLabels(embedded, framework);
            if (formRules.isEmpty() && fieldLabels.isEmpty()) continue;

            Map<String, Map<String, ValidationMessage>> validationMessages =
                    resolveValidationMessages(embedded, formRules, framework);
            String label = "Embedded Fields — `<" + embedded.getSelector() + ">` ("
                    + embedded.getClassName() + ")";
            renderFormFields(sb, label, formRules, fieldLabels, validationMessages);
        }
    }

    private void renderFormFields(StringBuilder sb, String bulletLabel,
                                  Map<String, List<ValidatorInfo>> formRules,
                                  Map<String, FieldLabel> fieldLabels,
                                  Map<String, Map<String, ValidationMessage>> validationMessages) {
        Set<String> fieldNames = new LinkedHashSet<>(formRules.keySet());
        fieldNames.addAll(fieldLabels.keySet());

        // A null value is a field with no resolvable label (the JSP extractor's contract) — not an i18n key.
        boolean anyI18n = fieldLabels.values().stream().anyMatch(l -> l != null && l.hasI18nKey());
        boolean anyValidationMessage = validationMessages.values().stream()
                .flatMap(m -> m.values().stream())
                .anyMatch(msg -> msg.status() != ValidationMessageStatus.NOT_FOUND);

        sb.append("- **").append(bulletLabel).append(":**\n\n");
        sb.append("  | Field | Label");
        if (anyI18n) sb.append(" | i18n Key");
        sb.append(" | Validations");
        if (anyValidationMessage) sb.append(" | Validation Messages");
        sb.append(" |\n");
        sb.append("  |---|---");
        if (anyI18n) sb.append("|---");
        sb.append("|---");
        if (anyValidationMessage) sb.append("|---");
        sb.append("|\n");

        for (String field : fieldNames) {
            FieldLabel label = fieldLabels.get(field);
            String labelCell = labelCell(label);
            String i18nCell = i18nKeyCell(label);
            String validations = formRules.containsKey(field)
                    ? formRules.get(field).stream().map(ValidatorInfo::rawText).collect(Collectors.joining(", "))
                    : "—";

            sb.append("  | `").append(field).append("` | ").append(labelCell);
            if (anyI18n) sb.append(" | ").append(i18nCell);
            sb.append(" | ").append(validations);
            if (anyValidationMessage) {
                sb.append(" | ").append(validationMessagesCell(formRules.get(field), validationMessages.get(field)));
            }
            sb.append(" |\n");
        }
        sb.append("\n");
    }

    /**
     * One field's "Validation Messages" cell: {@code `validator`: message} pairs joined by
     * "; ", in validator declaration order. Validators with no correlated message
     * ({@link ValidationMessageStatus#NOT_FOUND}) are omitted rather than shown as noise —
     * consistent with how {@link #renderFormFields} already omits the whole column when no
     * field in the component has any resolved message.
     */
    private String validationMessagesCell(List<ValidatorInfo> validators,
                                          Map<String, ValidationMessage> messagesForField) {
        if (validators == null || messagesForField == null) return "—";
        List<String> parts = new ArrayList<>();
        for (ValidatorInfo validator : validators) {
            ValidationMessage msg = messagesForField.get(validator.name());
            if (msg == null || msg.status() == ValidationMessageStatus.NOT_FOUND) continue;
            String text = switch (msg.status()) {
                case RESOLVED, LITERAL -> msg.text();
                case KEY_UNRESOLVED -> "*(unresolved: `" + msg.i18nKey() + "`)*";
                case NOT_FOUND -> null; // unreachable — filtered above
            };
            parts.add("`" + validator.name() + "`: " + text);
        }
        return parts.isEmpty() ? "—" : String.join("; ", parts);
    }

    /**
     * The human-readable label: literal text, resolved i18n message, or a marker. When a key
     * fails to resolve but the template carried the source message inline (@angular/localize),
     * that source text is shown rather than the marker — losing it would be a downgrade from
     * what the template plainly says.
     */
    private String labelCell(FieldLabel label) {
        if (label == null) return "—";
        if (!label.hasI18nKey()) return label.literal();
        return i18nCatalog.resolve(label.i18nKey())
                .or(() -> Optional.ofNullable(label.literal()))
                .orElse("*(unresolved)*");
    }

    /** The i18n key column: the key itself, or a dash for literal / missing labels. */
    private String i18nKeyCell(FieldLabel label) {
        if (label == null || !label.hasI18nKey()) return "—";
        return "`" + label.i18nKey() + "`";
    }

    private List<TemplateEventExtractorStrategy.Event> extractEvents(ComponentInfo component,
                                                                     Map<String, String> httpDescriptors,
                                                                     FrontendFramework framework) {
        try {
            TemplateEventExtractorStrategy extractor = TemplateEventExtractorFactory.createFor(framework);
            return extractor.extract(component.getFilePath(), httpDescriptors);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Builds a map of Angular service method name → {@code "VERB /url"} descriptor from
     * every extracted service's HTTP calls. Used by {@link TemplateEventExtractor} to
     * decide which template event handlers are business triggers.
     *
     * <p>Package-visible: reused by {@link FrontendPagesZipExporter} so the per-page
     * documents inside the ZIP show the same UI interactions.
     */
    Map<String, String> buildHttpMethodDescriptors(List<ServiceInfo> services) {
        Map<String, String> descriptors = new LinkedHashMap<>();
        for (ServiceInfo service : services) {
            for (HttpCallInfo call : service.getHttpCalls()) {
                // First occurrence wins on collisions; method names are near-unique in practice.
                descriptors.putIfAbsent(call.getMethodName(),
                        call.getHttpVerb() + " " + call.getUrlTemplate());
            }
        }
        return descriptors;
    }

    /**
     * Indexes the non-routed components that pages may embed, so {@link #renderPage} can document
     * their fields. Angular-only: it needs {@code @Component} selectors and resolvable templates,
     * which the React/Vue extractors do not currently produce.
     *
     * <p>Package-visible so {@link FrontendPagesZipExporter}, which drives {@link #renderPage}
     * directly, produces per-page documents matching the combined one.
     */
    void useEmbeddedComponentsFrom(List<RouteNode> routes, Map<String, ComponentInfo> componentsByName,
                                   FrontendFramework framework) {
        this.embeddedResolver = framework == FrontendFramework.ANGULAR
                ? AngularEmbeddedComponentResolver.build(componentsByName.values(), routedComponentNames(routes))
                : AngularEmbeddedComponentResolver.empty();
    }

    private Set<String> routedComponentNames(List<RouteNode> routes) {
        Set<String> names = new HashSet<>();
        collectRoutedComponentNames(routes, names);
        return names;
    }

    private void collectRoutedComponentNames(List<RouteNode> routes, Set<String> names) {
        for (RouteNode route : routes) {
            if (route.getComponentName() != null && !route.getComponentName().isEmpty()) {
                names.add(route.getComponentName());
            }
            collectRoutedComponentNames(route.getChildren(), names);
        }
    }

    /** Package-visible: reused by {@link FrontendPagesZipExporter}. */
    Map<String, List<MatchedFlow>> buildFlowsByService(EndpointMatcher.MatchResult result) {
        Map<String, List<MatchedFlow>> map = new HashMap<>();
        for (MatchedFlow flow : result.flows()) {
            String serviceName = flow.getAngularService().getClassName();
            map.computeIfAbsent(serviceName, k -> new ArrayList<>()).add(flow);
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * JSP + jQuery only: the view's AJAX call sites (Phase 03) — the page module's in full, each
     * either extracted or listed as unparseable with its reason, and shared modules summarised.
     */
    private static void renderAjaxCallSites(StringBuilder sb, JspAjaxCallReport.Result r) {
        if (!r.bound()) return; // the correlation section states why the view has no bundle
        List<JQueryAjaxCallExtractor.CallSite> calls = r.entryCalls();
        List<JQueryAjaxCallExtractor.CallSite> unparseable = calls.stream().filter(c -> !c.extracted()).toList();
        sb.append("- **AJAX Call Sites** *(page module `").append(r.entryModule()).append("`)*: ")
          .append(calls.size() - unparseable.size()).append(" extracted, ")
          .append(unparseable.size()).append(" unparseable\n\n");
        if (calls.size() > unparseable.size()) {
            sb.append("  | Verb | URL | Payload | Callbacks | In | Line |\n");
            sb.append("  |---|---|---|---|---|---|\n");
            for (JQueryAjaxCallExtractor.CallSite c : calls) {
                if (!c.extracted()) continue;
                String verb = c.verb() + ("default".equals(c.verbSource()) ? " *(default)*" : "")
                        + ("$.ajax".equals(c.kind()) ? "" : " *(" + c.kind() + ")*");
                String url = c.urlTemplates().stream().map(t -> "`" + t + "`").collect(Collectors.joining("<br>"))
                        + ("literal".equals(c.urlProvenance()) ? "" : " *(" + c.urlProvenance() + ")*");
                sb.append("  | ").append(verb)
                  .append(" | ").append(tableCell(url))
                  .append(" | ").append(c.payload() == null ? "—" : tableCell(c.payload()))
                  .append(" | ").append(c.callbacks().isEmpty() ? "—" : tableCell(String.join("; ", c.callbacks())))
                  .append(" | ").append(callSiteFunction(c))
                  .append(" | ").append(c.line()).append(" |\n");
            }
            sb.append("\n");
        }
        if (!unparseable.isEmpty()) {
            sb.append("  - **Unparseable call sites:**\n");
            for (JQueryAjaxCallExtractor.CallSite c : unparseable) {
                sb.append("    - line ").append(c.line()).append(" (").append(c.kind()).append(" in ")
                  .append(callSiteFunction(c)).append(") — ").append(c.unparseableReason()).append("\n");
            }
        }
        if (!r.sharedModules().isEmpty()) {
            sb.append("  - **Shared modules in the bundle:** ");
            sb.append(r.sharedModules().stream().map(m -> "`" + m.module() + "` — " + m.extracted() + " extracted"
                    + (m.unparseable().isEmpty() ? "" : ", " + m.unparseable().size() + " unparseable ("
                    + String.join("; ", m.unparseable()) + ")")).collect(Collectors.joining("; ")));
            sb.append("\n");
        }
        sb.append("\n");
    }

    private static String callSiteFunction(JQueryAjaxCallExtractor.CallSite c) {
        return "unknown".equals(c.function()) ? "top level" : c.function() + "()";
    }

    private static String tableCell(String s) {
        return s.replace("|", "\\|");
    }

    /**
     * JSP + jQuery only: which request each field feeds, which is repopulated at runtime and from
     * where — and, stated as plainly as the correlations, what could not be correlated.
     */
    private static void renderDomCorrelation(StringBuilder sb, DomIdentifierCorrelator.Result r) {
        if (!r.bound()) {
            sb.append("- **Field ↔ Request Correlation:** not available — ").append(r.unboundReason()).append(".\n");
            return;
        }
        if (!r.fields().isEmpty()) {
            sb.append("- **Field ↔ Request Correlation:** ").append(r.correlatedFieldCount()).append(" of ")
              .append(r.fields().size()).append(" field(s) feed a request *(literal identifiers only)*\n\n");
            sb.append("  | Field | Feeds request | Read in | Populated at runtime |\n");
            sb.append("  |---|---|---|---|\n");
            for (DomIdentifierCorrelator.FieldCorrelation f : r.fields()) {
                sb.append("  | `").append(f.id()).append("`");
                if (!f.fromMarkup()) sb.append(" *(").append(f.origin()).append(")*");
                String readIn = !f.readIn().isEmpty() ? String.join(", ", f.readIn())
                        : f.population() != null ? "—"
                        : f.referencedByScript() ? "*referenced, never read*"
                        : "*not referenced by script*";
                sb.append(" | ").append(f.feeds().isEmpty() ? "—" : String.join("; ", f.feeds()))
                  .append(" | ").append(readIn)
                  .append(" | ").append(f.population() == null ? "—" : f.population())
                  .append(" |\n");
            }
            sb.append("\n");
        }
        if (!r.unresolvedSelectors().isEmpty()) {
            sb.append("- **Unresolvable selectors** *(identifier built at runtime — not statically correlated)*:\n");
            r.unresolvedSelectors().forEach(s -> sb.append("  - ").append(s).append("\n"));
        }
        if (r.uncorrelatedGeneratedControls() > 0) {
            sb.append("- **Script-generated controls without a literal identifier:** ")
              .append(r.uncorrelatedGeneratedControls()).append(" — skipped, nothing to correlate by.\n");
        }
    }

    /** Provenance of a reconstructed (JSP) route: sources, menu gate, dispatch states, mapping. */
    private static void renderOrigin(StringBuilder sb, RouteNode route) {
        RouteOrigin origin = route == null ? null : route.getOrigin();
        if (origin == null) return;
        if (!origin.sources().isEmpty()) {
            sb.append("- **Reached via:** ").append(String.join("; ", origin.sources())).append("\n");
        }
        if (!origin.requiredPermissions().isEmpty()) {
            sb.append("- **Navigation gated by:** ").append(String.join(", ", origin.requiredPermissions()))
              .append(" *(UI-fragment `<sec:authorize>` around the link, not URL-level security)*\n");
        }
        if (!origin.triggeringStates().isEmpty()) {
            sb.append("- **Triggering states:** `").append(String.join("`, `", origin.triggeringStates())).append("`\n");
        }
        if (origin.viewMappingNote() != null) {
            sb.append("- **View mapping:** ").append(origin.viewMappingNote()).append("\n");
        }
    }

    private String joinPath(String parent, String segment) {
        if (segment == null || segment.isEmpty()) return parent;
        if (parent.isEmpty()) return segment;
        return parent + "/" + segment;
    }

    /** "LockDetailComponent" → "Lock Detail" */
    private static String humanize(String componentName) {
        String base = componentName.replaceAll("(Component|Page|View)$", "");
        return base.replaceAll("([a-z])([A-Z])", "$1 $2");
    }
}
