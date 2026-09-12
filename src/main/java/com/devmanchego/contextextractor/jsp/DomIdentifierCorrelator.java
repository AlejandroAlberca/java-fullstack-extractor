package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.common.TemplateEventExtractorStrategy.Event;
import com.devmanchego.contextextractor.jsp.JspBundleExtractor.FunctionSpan;
import com.devmanchego.contextextractor.jsp.JspBundleExtractor.LocatedCall;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 06: joins a JSP view's markup element identifiers to the JavaScript that reads and
 * writes them — which field feeds which request, which control is populated at runtime and from
 * where, and which button triggers which request.
 *
 * <p>The JavaScript considered is exactly the view's own bundle closure, as Phase 05 binds it
 * ({@link JspBundleExtractor}); a view Phase 05 leaves unbound has nothing to correlate against,
 * and says so.
 *
 * <p><b>Literal identifiers only</b>, per this phase's constraints: {@code $('#id')} (optionally
 * with a descendant selector after it) and a jQuery object cached under a name
 * ({@code this.fooId = $('#foo')}, later {@code page.fooId.val()}). A selector assembled at
 * runtime ({@code $('#' + id)}, {@code $('#row' + i)}) is listed verbatim as unresolvable, never
 * guessed at; so is a cached name bound to two different identifiers.
 *
 * <p><b>Scope.</b> A read, write or call belongs to the innermost function around it — anonymous
 * ones included. That is what keeps a read inside one click handler from being credited to every
 * request some sibling handler in the same {@code init()} makes. A read feeds:
 * <ul>
 *   <li>a request issued in the same scope, or whose payload the read literally sits in;</li>
 *   <li>a request issued by a function that scope directly invokes (one hop, noted "via f()");</li>
 *   <li>a request whose argument object invokes the reading function to build its payload.</li>
 * </ul>
 * One hop only: every extra hop multiplies false positives, and a reader can act on a stated gap
 * but not on a confident wrong answer.
 *
 * <p><b>Runtime population.</b> {@code .empty()/.append()/.html(x)/.load()/.DataTable()} on a
 * control is reported as dynamic population; its data source is the request whose callback does
 * it, a {@code .load(url)} / DataTables {@code ajax} URL, or "not statically determined".
 *
 * <p><b>Script-generated controls.</b> An {@code <input>/<select>/<textarea>} inside a script
 * string with a literal {@code id} is correlated like a markup one; one whose identifier is built
 * at runtime is counted and skipped.
 */
public final class DomIdentifierCorrelator {

    private static final Logger log = LoggerFactory.getLogger(DomIdentifierCorrelator.class);

    /** $('#id') / $("#id option:selected") — a literal identifier, optionally narrowed by a descendant selector. */
    private static final Pattern LITERAL_SELECTOR = Pattern.compile("\\$\\(\\s*(['\"])#([\\w-]+)([^'\"]*)\\1\\s*\\)");
    /** x.name = $('#id') / var name = $('#id') — a jQuery object cached under a name. */
    private static final Pattern ALIAS_DEF = Pattern.compile(
            "(?:(?:this|[\\w$]+)\\.([\\w$]+)|(?:var|let|const)\\s+([\\w$]+))\\s*=\\s*\\$\\(\\s*(['\"])#([\\w-]+)\\3\\s*\\)");
    /** idProjet: '#projet' / this.idProjet = '#projet' / const sel = '#projet' — a selector kept as a string constant. */
    private static final Pattern SELECTOR_CONSTANT_DEF = Pattern.compile(
            "(?:(?<![\\w$.])([\\w$]+)\\s*:|(?:this|[\\w$]+)\\.([\\w$]+)\\s*=|(?:var|let|const)\\s+([\\w$]+)\\s*=)"
            + "\\s*(['\"])#([\\w-]+)\\4(?=\\s*[,;}\\r\\n])");
    /** $('#' + x), $('#prefix' + x), $(`#${x}`) — an identifier assembled at runtime. */
    private static final Pattern CONCAT_SELECTOR = Pattern.compile("\\$\\(\\s*(?:(['\"])#[\\w-]*\\1\\s*\\+|`#[^`]*\\$\\{)");
    private static final Pattern GENERATED_CONTROL = Pattern.compile("<(input|select|textarea)\\b");
    private static final Pattern GENERATED_LITERAL_ID = Pattern.compile("\\bid\\s*=\\s*\\\\?[\"']([\\w-]+)\\\\?[\"']");
    private static final Pattern INVOKED_NAME = Pattern.compile("(?<![\\w$])([\\w$]+)\\s*\\(");
    private static final Pattern URL_KEY = Pattern.compile("\\burl\\s*:\\s*");
    private static final Pattern AJAX_KEY = Pattern.compile("\\bajax\\s*:\\s*(?=['\"])");
    private static final Pattern TRAILING_NAME = Pattern.compile("([\\w$]+)\\s*$");

    private static final Set<String> READ_NO_ARG = Set.of("val", "text", "html");
    private static final Set<String> READ_ONE_ARG = Set.of("prop", "is");
    private static final Set<String> FORM_READ = Set.of("serialize", "serializeArray");
    private static final Set<String> CONTENT_WRITE = Set.of(
            "empty", "append", "prepend", "load", "replaceWith", "DataTable", "dataTable");
    private static final Set<String> EVENT_METHODS = Set.of(
            "click", "change", "submit", "keyup", "keydown", "blur", "focusout", "input", "dblclick");
    /** Chain steps after which the chain no longer addresses the identified element itself. */
    private static final Set<String> RETARGETING = Set.of("find", "closest", "parent", "parents", "children", "siblings", "next", "prev");
    private static final Set<String> BUTTON_TYPES = Set.of("button", "submit", "reset", "image");
    /** Names too generic to trust as a cached-selector alias — a later {@code x.data.} is almost surely unrelated. */
    private static final Set<String> ALIAS_STOPLIST = Set.of(
            "data", "id", "el", "value", "val", "page", "self", "that", "result", "response", "options");

    private static final int MAX_LISTED_UNRESOLVED = 40;

    /**
     * @param id     the element identifier
     * @param origin {@code "<select>"}-style markup tag, or {@code "script-generated at file:line"}
     * @param feeds  requests this field's value reaches, e.g. {@code "POST /workflow (via sendFee())"}
     * @param readIn functions reading the field, e.g. {@code "init()"}
     * @param population null when never repopulated at runtime; otherwise where its contents come from
     * @param referencedByScript whether any statement addresses the field at all — a field only
     *                           ever written, bound or toggled is referenced without being read
     */
    public record FieldCorrelation(String id, String origin, List<String> feeds, List<String> readIn, String population,
                                   boolean referencedByScript) {
        public FieldCorrelation {
            feeds = List.copyOf(feeds);
            readIn = List.copyOf(readIn);
        }

        public boolean fromMarkup() { return origin.startsWith("<"); }
    }

    /**
     * @param unboundReason              null when correlated; otherwise why nothing could be
     * @param fields                     one row per markup field, then per script-generated literal-id control
     * @param unresolvedSelectors        {@code `file:line` — `$('#' + x)`} entries, and ambiguous aliases
     * @param uncorrelatedGeneratedControls script-generated controls with no literal identifier
     * @param events                     business triggers: element event → request(s)
     */
    public record Result(String unboundReason, List<FieldCorrelation> fields, List<String> unresolvedSelectors,
                         int uncorrelatedGeneratedControls, List<Event> events) {
        public Result {
            fields = List.copyOf(fields);
            unresolvedSelectors = List.copyOf(unresolvedSelectors);
            events = List.copyOf(events);
        }

        static Result unbound(String reason) {
            return new Result(reason, List.of(), List.of(), 0, List.of());
        }

        public boolean bound() { return unboundReason == null; }

        public long correlatedFieldCount() { return fields.stream().filter(f -> !f.feeds().isEmpty()).count(); }
    }

    private record JsFile(Path path, String rel, String content, int[] lineStarts,
                          List<FunctionSpan> spans, List<LocatedCall> calls) {
        int line(int offset) {
            int i = Arrays.binarySearch(lineStarts, offset);
            return i >= 0 ? i + 1 : -i - 1;
        }

        String where(int offset) { return rel + ":" + line(offset); }

        FunctionSpan scopeAt(int offset) { return JspBundleExtractor.innermost(spans, offset); }
    }

    private record FnRef(JsFile file, FunctionSpan span) {}

    /** One {@code .method(args)} applied to an identified element; {@code refOffset} is where the reference starts. */
    private record Op(String id, String method, String args, int argsStart, JsFile file, int refOffset) {}

    private record Arg(String text, int start) {}

    private record Markup(Map<String, Element> fieldsById, Map<String, List<String>> formFieldIds,
                          Map<String, Element> allById) {}

    // Per-view results are cached: the page renderer asks once for the field table and once (through
    // JspTemplateEventExtractor) for the events of the same view. Keyed by path + mtime.
    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

    public Result correlate(String jspFilePath) {
        if (jspFilePath == null || JspRouteReconstructor.UNRESOLVED_FILE.equals(jspFilePath)) {
            return Result.unbound("no JSP view backs this route");
        }
        Path jsp;
        try {
            jsp = Path.of(jspFilePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return Result.unbound("invalid view path");
        }
        if (!Files.isRegularFile(jsp)) return Result.unbound("no JSP view backs this route");

        String key;
        try {
            key = jsp + "|" + Files.getLastModifiedTime(jsp).toMillis();
        } catch (IOException e) {
            key = jsp.toString();
        }
        return CACHE.computeIfAbsent(key, k -> {
            try {
                return compute(jsp);
            } catch (Exception e) {
                log.warn("DomIdentifierCorrelator: could not correlate {}: {}", jsp, e.getMessage());
                return Result.unbound("correlation failed: " + e.getMessage());
            }
        });
    }

    private Result compute(Path jsp) {
        Path webapp = JspFileParser.locateWebappRoot(jsp);
        if (webapp == null) return Result.unbound("no web application root (WEB-INF) above this view");
        JspPage page = new JspFileParser().parse(jsp, webapp);
        JspBundleExtractor.BoundView view = JspBundleExtractor.bindView(jsp, webapp, page);
        if (!view.bound()) return Result.unbound(view.unboundReason());

        List<JsFile> files = loadClosure(view.closure(), view.frontendRoot());
        Markup markup = readMarkup(page.document());
        Reach reach = new Reach(indexFunctions(files));

        List<String> unresolved = new ArrayList<>();
        Map<String, String> aliases = collectNamedIdentifiers(files, ALIAS_DEF, 4, "cached selector", unresolved);
        Map<String, String> selectorConstants =
                collectNamedIdentifiers(files, SELECTOR_CONSTANT_DEF, 5, "selector constant", unresolved);
        collectConcatenatedSelectors(files, unresolved);
        Map<String, String> generated = new LinkedHashMap<>();
        int uncorrelatedGenerated = collectGeneratedControls(files, generated);

        Map<String, Set<String>> feeds = new LinkedHashMap<>();
        Map<String, Set<String>> readIn = new LinkedHashMap<>();
        Map<String, Set<String>> knownSources = new LinkedHashMap<>();
        Map<String, String> unknownSourceScope = new LinkedHashMap<>();
        Map<String, Event> events = new LinkedHashMap<>();
        Set<String> referenced = new LinkedHashSet<>();

        for (Op op : collectOps(files, aliases, selectorConstants)) {
            referenced.add(op.id());
            if (isRead(op)) {
                recordRead(op, op.id(), reach, files, feeds, readIn);
            } else if (FORM_READ.contains(op.method())) {
                for (String fieldId : markup.formFieldIds().getOrDefault(op.id(), List.of())) {
                    referenced.add(fieldId);
                    recordRead(op, fieldId, reach, files, feeds, readIn);
                }
            } else if (isContentWrite(op)) {
                recordPopulation(op, knownSources, unknownSourceScope);
            } else if (isEvent(op)) {
                recordEvent(op, markup, reach, events);
            }
        }

        List<FieldCorrelation> rows = new ArrayList<>();
        for (Map.Entry<String, Element> e : markup.fieldsById().entrySet()) {
            rows.add(row(e.getKey(), "<" + tag(e.getValue()) + ">", feeds, readIn, knownSources, unknownSourceScope,
                    referenced.contains(e.getKey())));
        }
        generated.forEach((id, where) -> {
            if (!markup.fieldsById().containsKey(id)) {
                rows.add(row(id, "script-generated at " + where, feeds, readIn, knownSources, unknownSourceScope,
                        referenced.contains(id)));
            }
        });

        List<String> listed = unresolved.size() > MAX_LISTED_UNRESOLVED
                ? unresolved.subList(0, MAX_LISTED_UNRESOLVED) : unresolved;
        return new Result(null, rows, listed, uncorrelatedGenerated, new ArrayList<>(events.values()));
    }

    private static FieldCorrelation row(String id, String origin, Map<String, Set<String>> feeds,
                                        Map<String, Set<String>> readIn, Map<String, Set<String>> knownSources,
                                        Map<String, String> unknownSourceScope, boolean referenced) {
        String population = null;
        if (knownSources.containsKey(id)) {
            population = "yes — from " + String.join("; ", knownSources.get(id));
        } else if (unknownSourceScope.containsKey(id)) {
            population = "yes — source not statically determined (in " + unknownSourceScope.get(id) + ")";
        }
        return new FieldCorrelation(id, origin, new ArrayList<>(feeds.getOrDefault(id, Set.of())),
                new ArrayList<>(readIn.getOrDefault(id, Set.of())), population, referenced);
    }

    // -----------------------------------------------------------------------
    // Inputs: closure files and markup
    // -----------------------------------------------------------------------

    private static List<JsFile> loadClosure(List<Path> closure, Path frontendRoot) {
        List<JsFile> files = new ArrayList<>();
        for (Path p : closure) {
            String content;
            try {
                content = Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            files.add(new JsFile(p, rel(p, frontendRoot), content, lineStarts(content),
                    JspBundleExtractor.functionSpans(content), JspBundleExtractor.locateHttpCalls(content)));
        }
        return files;
    }

    private static Markup readMarkup(Document document) {
        Map<String, Element> fields = new LinkedHashMap<>();
        Map<String, Element> all = new LinkedHashMap<>();
        Map<String, List<String>> forms = new LinkedHashMap<>();
        for (Element el : document.getAllElements()) {
            String id = el.id();
            if (id.isBlank()) continue;
            all.putIfAbsent(id, el);
            if (isField(el)) fields.putIfAbsent(id, el);
            if ("form".equals(tag(el))) {
                forms.put(id, el.select("input[id], select[id], textarea[id]").stream()
                        .filter(DomIdentifierCorrelator::isField).map(Element::id).distinct()
                        .collect(Collectors.toList()));
            }
        }
        return new Markup(fields, forms, all);
    }

    private static boolean isField(Element el) {
        String tag = tag(el);
        if ("select".equals(tag) || "textarea".equals(tag)) return true;
        return "input".equals(tag) && !BUTTON_TYPES.contains(el.attr("type").toLowerCase(Locale.ROOT));
    }

    private static Map<String, List<FnRef>> indexFunctions(List<JsFile> files) {
        Map<String, List<FnRef>> byName = new LinkedHashMap<>();
        for (JsFile f : files) {
            for (FunctionSpan s : f.spans()) {
                if (s.name() != null) byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(new FnRef(f, s));
            }
        }
        return byName;
    }

    // -----------------------------------------------------------------------
    // References: aliases, element operations, unresolvable and generated identifiers
    // -----------------------------------------------------------------------

    /**
     * Names bound to one literal identifier — a cached jQuery object or a selector string constant.
     * The name is whichever of the definition pattern's leading groups matched; {@code idGroup}
     * holds the identifier. A name bound to two different identifiers is listed, not guessed.
     */
    private static Map<String, String> collectNamedIdentifiers(List<JsFile> files, Pattern definition, int idGroup,
                                                               String kind, List<String> unresolved) {
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        for (JsFile f : files) {
            Matcher m = definition.matcher(f.content());
            while (m.find()) {
                String name = null;
                for (int g = 1; g < idGroup && name == null; g++) {
                    if (m.group(g) != null && !m.group(g).startsWith("'") && !m.group(g).startsWith("\"")) name = m.group(g);
                }
                if (name == null || name.length() < 3 || ALIAS_STOPLIST.contains(name)) continue;
                candidates.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(m.group(idGroup));
            }
        }
        Map<String, String> named = new LinkedHashMap<>();
        candidates.forEach((name, ids) -> {
            if (ids.size() == 1) {
                named.put(name, ids.iterator().next());
            } else {
                unresolved.add(kind + " `" + name + "` is bound to several identifiers ("
                        + ids.stream().map(i -> "#" + i).collect(Collectors.joining(", "))
                        + ") — its uses are not correlated");
            }
        });
        return named;
    }

    private static List<Op> collectOps(List<JsFile> files, Map<String, String> aliases,
                                       Map<String, String> selectorConstants) {
        // page.fooId.val() / fooId.val() — a cached jQuery object used by name.
        Pattern aliasUse = aliases.isEmpty() ? null : Pattern.compile("(?<![\\w$])("
                + aliases.keySet().stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")(?=\\s*\\.)");
        // $(page.idFoo) / $(idFoo) — a selector string constant wrapped at the point of use.
        Pattern constantUse = selectorConstants.isEmpty() ? null : Pattern.compile("\\$\\(\\s*(?:(?:this|[\\w$]+)\\.)?("
                + selectorConstants.keySet().stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")\\s*\\)");
        List<Op> ops = new ArrayList<>();
        for (JsFile f : files) {
            Matcher m = LITERAL_SELECTOR.matcher(f.content());
            while (m.find()) walkChain(f, m.group(2), m.end(), m.start(), ops);
            if (aliasUse != null) {
                Matcher a = aliasUse.matcher(f.content());
                while (a.find()) walkChain(f, aliases.get(a.group(1)), a.end(), a.start(), ops);
            }
            if (constantUse != null) {
                Matcher c = constantUse.matcher(f.content());
                while (c.find()) walkChain(f, selectorConstants.get(c.group(1)), c.end(), c.start(), ops);
            }
        }
        return ops;
    }

    /** Records each {@code .method(args)} chained onto a reference, until the chain stops addressing the element. */
    private static void walkChain(JsFile f, String id, int pos, int refOffset, List<Op> ops) {
        String c = f.content();
        int i = pos;
        for (int steps = 0; steps < 8; steps++) {
            i = skipWhitespace(c, i);
            if (i >= c.length() || c.charAt(i) != '.') return;
            int nameStart = skipWhitespace(c, i + 1);
            int nameEnd = nameStart;
            while (nameEnd < c.length() && Character.isJavaIdentifierPart(c.charAt(nameEnd))) nameEnd++;
            if (nameEnd == nameStart) return;
            String method = c.substring(nameStart, nameEnd);
            int paren = skipWhitespace(c, nameEnd);
            if (paren >= c.length() || c.charAt(paren) != '(') return; // property access (.length) ends the chain
            String args = JspBundleExtractor.extractBalanced(c, paren, '(', ')');
            if (args == null) return;
            ops.add(new Op(id, method, args, paren + 1, f, refOffset));
            if (RETARGETING.contains(method)) return;
            i = paren + args.length() + 2;
        }
    }

    private static void collectConcatenatedSelectors(List<JsFile> files, List<String> unresolved) {
        for (JsFile f : files) {
            Matcher m = CONCAT_SELECTOR.matcher(f.content());
            while (m.find()) {
                String inner = JspBundleExtractor.extractBalanced(f.content(), m.start() + 1, '(', ')');
                String snippet = ("$(" + (inner == null ? "…" : inner.strip()) + ")")
                        .replace('`', '\'').replaceAll("\\s+", " ");
                if (snippet.length() > 80) snippet = snippet.substring(0, 77) + "...";
                unresolved.add("`" + f.where(m.start()) + "` — `" + snippet + "`");
            }
        }
    }

    /** Literal-id generated controls into {@code generated} (id → file:line); returns the count of the rest. */
    private static int collectGeneratedControls(List<JsFile> files, Map<String, String> generated) {
        int withoutLiteralId = 0;
        for (JsFile f : files) {
            String c = f.content();
            Matcher m = GENERATED_CONTROL.matcher(c);
            while (m.find()) {
                String tagText = c.substring(m.end(), Math.min(c.length(), m.end() + 300));
                int gt = tagText.indexOf('>');
                if (gt >= 0) tagText = tagText.substring(0, gt);
                Matcher id = GENERATED_LITERAL_ID.matcher(tagText);
                if (id.find()) generated.putIfAbsent(id.group(1), f.where(m.start()));
                else withoutLiteralId++;
            }
        }
        return withoutLiteralId;
    }

    // -----------------------------------------------------------------------
    // Classification
    // -----------------------------------------------------------------------

    private static boolean isRead(Op op) {
        if (READ_NO_ARG.contains(op.method())) return op.args().isBlank();
        if (READ_ONE_ARG.contains(op.method())) return splitArgs(op.args()).size() == 1;
        return false;
    }

    private static boolean isContentWrite(Op op) {
        if ("html".equals(op.method())) return !op.args().isBlank();
        return CONTENT_WRITE.contains(op.method());
    }

    private static boolean isEvent(Op op) {
        if ("on".equals(op.method())) return splitArgs(op.args()).size() >= 2;
        return EVENT_METHODS.contains(op.method()) && !op.args().isBlank();
    }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    private static void recordRead(Op op, String fieldId, Reach reach, List<JsFile> files,
                                   Map<String, Set<String>> feeds, Map<String, Set<String>> readIn) {
        JsFile f = op.file();
        FunctionSpan scope = f.scopeAt(op.refOffset());
        Set<String> out = feeds.computeIfAbsent(fieldId, k -> new LinkedHashSet<>());

        // Sitting directly in a request's argument object (not in a callback nested inside it) is payload.
        LocatedCall enclosing = callWhoseArgsContain(f, op.refOffset());
        if (enclosing != null && Objects.equals(scope, f.scopeAt(enclosing.start()))) out.add(enclosing.descriptor());

        out.addAll(reach.fromScope(f, scope));

        FunctionSpan named = JspBundleExtractor.innermostNamed(f.spans(), op.refOffset());
        if (named != null && named.equals(scope)) out.addAll(reach.payloadBuiltBy(new FnRef(f, named), files));

        readIn.computeIfAbsent(fieldId, k -> new LinkedHashSet<>()).add(named == null ? "(top level)" : named.name() + "()");
    }

    private static void recordPopulation(Op op, Map<String, Set<String>> knownSources, Map<String, String> unknownScope) {
        JsFile f = op.file();
        String source = null;
        LocatedCall enclosing = callWhoseArgsContain(f, op.refOffset());
        if (enclosing != null) {
            source = enclosing.descriptor();
        } else if ("DataTable".equals(op.method()) || "dataTable".equals(op.method())) {
            source = dataTableSource(op.args());
        } else if ("load".equals(op.method())) {
            List<Arg> args = splitArgs(op.args());
            String url = args.isEmpty() ? null : JspBundleExtractor.normalizeUrlExpr(args.get(0).text());
            if (url != null) source = "GET " + url;
        }
        if (source != null) {
            knownSources.computeIfAbsent(op.id(), k -> new LinkedHashSet<>()).add(source);
        } else {
            FunctionSpan named = JspBundleExtractor.innermostNamed(f.spans(), op.refOffset());
            unknownScope.putIfAbsent(op.id(), named == null ? "top-level code" : named.name() + "()");
        }
    }

    private static String dataTableSource(String args) {
        Matcher url = URL_KEY.matcher(args);
        Matcher ajax = AJAX_KEY.matcher(args);
        String expr = url.find() ? JspBundleExtractor.extractValueExpr(args, url.end())
                : ajax.find() ? JspBundleExtractor.extractValueExpr(args, ajax.end()) : null;
        String template = expr == null ? null : JspBundleExtractor.normalizeUrlExpr(expr);
        return template == null ? null : "GET " + template + " (DataTables)";
    }

    private static void recordEvent(Op op, Markup markup, Reach reach, Map<String, Event> events) {
        List<Arg> args = splitArgs(op.args());
        String trigger;
        if ("on".equals(op.method())) {
            trigger = unquote(args.get(0).text());
        } else {
            trigger = op.method();
        }
        Arg handler = args.get(args.size() - 1);
        Set<String> requests = reachOfHandler(op, handler, reach);
        if (requests.isEmpty()) return; // not a business trigger: never reaches the network

        String description = describe(markup.allById().get(op.id()), op.id()) + " → " + String.join(", ", requests);
        events.putIfAbsent(description, new Event(trigger, description));
    }

    private static Set<String> reachOfHandler(Op op, Arg handler, Reach reach) {
        JsFile f = op.file();
        String h = handler.text();
        int start = op.argsStart() + handler.start();
        int end = start + h.length();
        boolean inline = h.startsWith("function") || h.startsWith("async")
                || h.matches("(?s)^(\\([^()]*\\)|[\\w$]+)\\s*=>.*");
        if (inline) {
            FunctionSpan own = f.spans().stream()
                    .filter(s -> s.bodyStart() >= start && s.bodyEnd() <= end)
                    .min(Comparator.comparingInt(FunctionSpan::bodyStart)).orElse(null);
            return own != null ? reach.fromRange(f, own, own.bodyStart(), own.bodyEnd())
                    : reach.fromRange(f, f.scopeAt(start), start, end); // expression-bodied arrow
        }
        // A reference: saveIt / page.saveIt / this.saveIt / page.saveIt.bind(page)
        String ref = h.replaceFirst("(?s)\\.bind\\s*\\(.*\\)$", "");
        Matcher last = TRAILING_NAME.matcher(ref);
        return last.find() ? reach.fromFunction(last.group(1), f) : Set.of();
    }

    /** The innermost HTTP call whose argument text contains {@code offset}, or null. */
    private static LocatedCall callWhoseArgsContain(JsFile f, int offset) {
        LocatedCall best = null;
        for (LocatedCall c : f.calls()) {
            if (c.argsContain(offset) && (best == null || c.argsStart() > best.argsStart())) best = c;
        }
        return best;
    }

    // -----------------------------------------------------------------------
    // Reachability: which requests a scope issues, directly or one hop away
    // -----------------------------------------------------------------------

    private static final class Reach {
        private final Map<String, List<FnRef>> fnByName;
        private final Map<String, Set<String>> ownedMemo = new HashMap<>();
        private final Map<String, Set<String>> scopeMemo = new HashMap<>();
        private final Map<String, Set<String>> payloadMemo = new HashMap<>();

        Reach(Map<String, List<FnRef>> fnByName) {
            this.fnByName = fnByName;
        }

        Set<String> fromScope(JsFile f, FunctionSpan scope) {
            String key = f.rel() + "#" + (scope == null ? -1 : scope.bodyStart());
            return scopeMemo.computeIfAbsent(key, k -> scope == null
                    ? fromRange(f, null, 0, f.content().length())
                    : fromRange(f, scope, scope.bodyStart(), scope.bodyEnd()));
        }

        /** Requests owned by {@code scope} (issued in it, not in a nested function), plus one hop through what it invokes. */
        Set<String> fromRange(JsFile f, FunctionSpan scope, int from, int to) {
            Set<String> out = new LinkedHashSet<>();
            for (LocatedCall c : f.calls()) {
                if (c.start() >= from && c.start() < to && Objects.equals(f.scopeAt(c.start()), scope)) {
                    out.add(c.descriptor());
                }
            }
            Matcher m = INVOKED_NAME.matcher(f.content()).region(from, to);
            while (m.find()) {
                String name = m.group(1);
                if (scope != null && name.equals(scope.name())) continue;
                if (!Objects.equals(f.scopeAt(m.start()), scope)) continue;
                for (FnRef ref : resolve(name, f)) {
                    for (String d : owned(ref)) out.add(d + " (via " + name + "())");
                }
            }
            return out;
        }

        Set<String> fromFunction(String name, JsFile from) {
            Set<String> out = new LinkedHashSet<>();
            for (FnRef ref : resolve(name, from)) {
                out.addAll(fromRange(ref.file(), ref.span(), ref.span().bodyStart(), ref.span().bodyEnd()));
            }
            return out;
        }

        /**
         * Requests whose payload {@code builder} builds: the request's argument object invokes it
         * ({@code data: JSON.stringify(page.toJson())}), or passes a variable the same scope just
         * assigned from it ({@code const fap = page.toJson(); … data: JSON.stringify(fap)}).
         */
        Set<String> payloadBuiltBy(FnRef builder, List<JsFile> files) {
            String key = builder.file().rel() + "#" + builder.span().bodyStart();
            return payloadMemo.computeIfAbsent(key, k -> {
                Set<String> out = new LinkedHashSet<>();
                String name = builder.span().name();
                String label = " (payload built by " + name + "())";
                Pattern invocation = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*\\(");
                Pattern assignment = Pattern.compile("(?:const|let|var)\\s+([\\w$]+)\\s*=\\s*[^;\\n]*?(?<![\\w$])"
                        + Pattern.quote(name) + "\\s*\\(");
                for (JsFile g : files) {
                    if (!resolve(name, g).contains(builder)) continue;
                    for (LocatedCall c : g.calls()) {
                        if (invocation.matcher(argsOf(g, c)).find()) out.add(c.descriptor() + label);
                    }
                    Matcher a = assignment.matcher(g.content());
                    while (a.find()) {
                        FunctionSpan scope = g.scopeAt(a.start());
                        Pattern use = Pattern.compile("(?<![\\w$.])" + Pattern.quote(a.group(1)) + "(?![\\w$])");
                        for (LocatedCall c : g.calls()) {
                            if (c.start() > a.start() && Objects.equals(g.scopeAt(c.start()), scope)
                                    && use.matcher(argsOf(g, c)).find()) {
                                out.add(c.descriptor() + label);
                            }
                        }
                    }
                }
                return out;
            });
        }

        private static String argsOf(JsFile f, LocatedCall c) {
            return f.content().substring(c.argsStart(), c.argsEnd());
        }

        private Set<String> owned(FnRef ref) {
            return ownedMemo.computeIfAbsent(ref.file().rel() + "#" + ref.span().bodyStart(), k -> {
                Set<String> out = new LinkedHashSet<>();
                for (LocatedCall c : ref.file().calls()) {
                    if (ref.span().equals(ref.file().scopeAt(c.start()))) out.add(c.descriptor());
                }
                return out;
            });
        }

        /** The same module's definition first; else the one other module defining it; several → not followed. */
        private List<FnRef> resolve(String name, JsFile from) {
            List<FnRef> all = fnByName.getOrDefault(name, List.of());
            if (all.isEmpty()) return all;
            List<FnRef> same = all.stream().filter(r -> r.file() == from).collect(Collectors.toList());
            if (!same.isEmpty()) return same;
            return all.stream().map(FnRef::file).distinct().count() == 1 ? all : List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String describe(Element el, String id) {
        if (el == null) return "Element (#" + id + ")";
        String tag = tag(el);
        boolean buttonInput = "input".equals(tag) && BUTTON_TYPES.contains(el.attr("type").toLowerCase(Locale.ROOT));
        String kind = switch (tag) {
            case "button" -> "Button";
            case "a" -> "Link";
            case "select" -> "Select";
            case "textarea" -> "Field";
            case "form" -> "Form";
            case "input" -> buttonInput ? "Button" : "Field";
            default -> "Element";
        };
        String label;
        if ("button".equals(tag) || "a".equals(tag)) label = el.text();
        else if (buttonInput) label = el.attr("value");
        else label = labelFor(el, id);
        label = label.replaceAll("\\s+", " ").strip();
        if (label.length() > 40) label = label.substring(0, 37) + "...";
        return kind + (label.isEmpty() ? "" : " '" + label + "'") + " (#" + id + ")";
    }

    private static String labelFor(Element el, String id) {
        Document doc = el.ownerDocument();
        if (doc == null) return "";
        for (Element label : doc.getElementsByTag("label")) {
            if (id.equals(label.attr("for"))) return label.text().replaceAll("[:*]\\s*$", "");
        }
        return "";
    }

    /** Top-level arguments of a call's argument text, with their offsets within it. */
    private static List<Arg> splitArgs(String args) {
        List<Arg> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (quote != 0) {
                if (ch == '\\') { i++; continue; }
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') { quote = ch; continue; }
            int comment = JspBundleExtractor.commentEnd(args, i);
            if (comment >= 0) { i = comment; continue; }
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth--;
            else if (ch == ',' && depth == 0) {
                addArg(out, args, start, i);
                start = i + 1;
            }
        }
        addArg(out, args, start, args.length());
        return out;
    }

    private static void addArg(List<Arg> out, String args, int from, int to) {
        String raw = args.substring(from, to);
        String text = raw.strip();
        if (!text.isEmpty()) out.add(new Arg(text, from + raw.indexOf(text)));
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"') && t.charAt(t.length() - 1) == t.charAt(0)) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int[] lineStarts(String content) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') starts.add(i + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String tag(Element el) {
        return el.tagName().toLowerCase(Locale.ROOT);
    }

    private static String rel(Path p, Path root) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString().replace('\\', '/');
        }
    }
}
