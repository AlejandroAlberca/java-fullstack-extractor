package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.angular.jvmparser.TypeScriptLexer;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.devmanchego.contextextractor.jsp.JspBundleExtractor.FunctionSpan;
import com.devmanchego.contextextractor.matching.PathNormalizer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.devmanchego.contextextractor.angular.jvmparser.TypeScriptLexer.*;

/**
 * Phase 03: extracts every AJAX call site from one JavaScript module — URL template, HTTP verb,
 * payload and callbacks — reporting each site it cannot resolve instead of dropping it.
 *
 * <p><b>Tokens, not characters.</b> The module is read through the project's own ANTLR lexer
 * ({@link TypeScriptLexer}, the same token stream {@code AntlrAngularParser} scans): comments are
 * skipped and every string or template literal is one token, so a parenthesis or quote inside
 * either can never unbalance a call. The project's grammar is lexer-only — there is no expression
 * parser to reuse — so call sites, object literals and concatenations are read structurally from
 * the token stream, the way the Angular extractor reads decorators.
 *
 * <p><b>Call sites.</b> {@code $.ajax(settings)}, {@code $.ajax(url, settings)},
 * {@code $.get/$.post/$.getJSON(url, …)}, {@code fetch(url, options)}, and a DataTables
 * {@code ajax:} option (object or URL). Verb keys {@code method} and {@code type} are equivalent,
 * values are case-insensitive and either quote style works; no verb means jQuery's default, GET.
 *
 * <p><b>URL reconstruction.</b> {@code +}-concatenations of string and template literals,
 * the context-path variable (dropped — a deployment constant, not a parameter) and expressions
 * (each a {@code {param}}). When the URL, or a piece of it, is a name, it is resolved literally:
 * <ul>
 *   <li>a variable assigned in the call's scope or an enclosing one ({@code const url = …});</li>
 *   <li>a property defined in an object literal or assigned ({@code importUrl: basepath + '/import/'});</li>
 *   <li>a parameter of the enclosing named function — through that function's call sites in the
 *       same module, so one call site can reach several URLs;</li>
 *   <li>a URL-building helper call ({@code Uri.initURL('/x/' + id, {…})}) — its first argument.</li>
 * </ul>
 * Templates are canonicalised exactly as backend paths are ({@link PathNormalizer}), minus the
 * query string, which is not part of a route.
 *
 * <p><b>Nothing is dropped silently.</b> A site whose settings, URL or verb cannot be resolved
 * is still returned, with {@link CallSite#unparseableReason()} saying why.
 */
public final class JQueryAjaxCallExtractor {

    private static final Set<String> JQUERY_METHODS = Set.of("ajax", "get", "post", "getJSON");
    private static final Set<String> PROMISE_METHODS = Set.of("done", "fail", "always", "then", "catch");
    private static final List<String> CALLBACK_KEYS = List.of("success", "error", "complete", "beforeSend");
    private static final Set<String> CONTEXT_PATH_IDENTIFIERS = Set.of("basepath", "contextPath");
    private static final Pattern TEMPLATE_EXPR = Pattern.compile("\\$\\{[^}]*}");
    /** $("#page").data("importactionurl") — a value the server rendered into the view's markup. */
    private static final Pattern DATA_ATTRIBUTE = Pattern.compile("^\\$\\(['\"]#([\\w-]+)['\"]\\)\\.data\\(['\"]([\\w-]+)['\"]\\)$");
    private static final Pattern SERIALIZE =Pattern.compile("^\\$\\(\\s*['\"](#[\\w-]+)['\"]\\s*\\)\\.serialize(?:Array)?\\(\\s*\\)$");
    private static final int MAX_DEPTH = 4;
    private static final int MAX_ALTERNATIVES = 8;
    /** Most constants a name inside a concatenation may stand for and still be enumerated, not {param}. */
    private static final int MAX_ENUMERATED = 4;

    /**
     * One call site.
     *
     * @param start              character offset where the call (or DataTables {@code ajax} key) starts
     * @param argsStart          first character of its arguments / option value
     * @param argsEnd            one past its last argument character
     * @param kind               {@code $.ajax}, {@code $.post}, {@code fetch}, {@code DataTables}…
     * @param function           innermost named enclosing function, or {@code unknown}
     * @param verbSource         {@code method}, {@code type}, {@code implied} or {@code default}
     * @param urlTemplates       canonical templates; several when one site reaches several URLs
     * @param urlProvenance      {@code literal}, or how a name in the URL was resolved
     * @param payload            what the request sends, or null
     * @param callbacks          {@code success: inline}, {@code done: page.onSaved}…
     * @param unparseableReason  null when extracted; otherwise why it could not be
     */
    public record CallSite(int line, int start, int argsStart, int argsEnd, String kind, String function,
                           HttpVerb verb, String verbSource, List<String> urlTemplates, String urlProvenance,
                           String payload, List<String> callbacks, String unparseableReason) {
        public CallSite {
            urlTemplates = List.copyOf(urlTemplates);
            callbacks = List.copyOf(callbacks);
        }

        public boolean extracted() { return unparseableReason == null; }
    }

    public List<CallSite> extract(String content) {
        if (content == null || content.isEmpty()) return List.of();
        return new Scan(content).run();
    }

    /** A URL resolution outcome: raw candidate templates and how they were found, or why none were. */
    private record Url(List<String> templates, String provenance, String failure) {
        static Url fail(String why) { return new Url(List.of(), null, why); }

        boolean ok() { return !templates.isEmpty(); }
    }

    private record Verb(String name, String source, String failure) {}

    /** One module's token stream and the resolution machinery over it. */
    private static final class Scan {
        private final List<Token> toks;
        private final List<FunctionSpan> spans;
        private final Map<String, Url> nameMemo = new HashMap<>();

        Scan(String content) {
            TypeScriptLexer lexer = new TypeScriptLexer(CharStreams.fromString(content));
            lexer.removeErrorListeners();
            this.toks = new ArrayList<>(lexer.getAllTokens());
            this.spans = JspBundleExtractor.functionSpans(content);
        }

        List<CallSite> run() {
            List<CallSite> sites = new ArrayList<>();
            for (int i = 0; i < toks.size(); i++) {
                if (isId(i, "$", "jQuery") && is(i + 1, DOT) && is(i + 2, ID)
                        && JQUERY_METHODS.contains(text(i + 2)) && is(i + 3, LPAREN)) {
                    sites.add(jquery(i, text(i + 2), i + 3));
                } else if (isId(i, "fetch") && is(i + 1, LPAREN) && !is(i - 1, DOT) && !isId(i - 1, "function")) {
                    sites.add(fetch(i, i + 1));
                } else if (isId(i, "ajax") && is(i + 1, COLON) && !is(i - 1, DOT)) {
                    sites.add(dataTables(i, i + 2));
                }
            }
            return sites;
        }

        // -------------------------------------------------------------------
        // Call-site shapes
        // -------------------------------------------------------------------

        private CallSite jquery(int i, String method, int lp) {
            String kind = "$." + method;
            int rp = match(lp);
            if (rp < 0) return unparseable(i, lp, lp, kind, "unbalanced parentheses — its arguments could not be delimited");
            List<int[]> args = split(lp + 1, rp, COMMA);
            List<String> callbacks = new ArrayList<>();

            if ("ajax".equals(method)) {
                if (args.isEmpty()) return unparseable(i, lp, rp, kind, "called without arguments");
                int[] settings = args.get(args.size() >= 2 ? 1 : 0);
                int[] object = objectLiteral(settings, start(i));
                if (object == null) {
                    return unparseable(i, lp, rp, kind, "the settings object `" + snippet(settings) + "` is not a literal");
                }
                Map<String, int[]> props = properties(object);
                int[] url = args.size() >= 2 ? args.get(0) : props.get("url");
                if (url == null) return unparseable(i, lp, rp, kind, "no `url` in the settings object");
                for (String key : CALLBACK_KEYS) {
                    if (props.containsKey(key)) callbacks.add(key + ": " + describeCallback(props.get(key)));
                }
                callbacks.addAll(chained(rp));
                return site(i, lp, rp, kind, url, verbOf(props, start(i)), payload(props.get("data"), start(i)), callbacks);
            }

            if (args.isEmpty()) return unparseable(i, lp, rp, kind, "called without arguments");
            String payload = null;
            for (int a = 1; a < args.size(); a++) {
                int[] arg = args.get(a);
                if (isFunctionExpr(arg)) callbacks.add("success: inline");
                else if (isSingle(arg, STRING_SQ, STRING_DQ)) continue; // dataType
                else if (a == 1) payload = payload(arg, start(i));
                else if (isName(arg)) callbacks.add("success: " + snippet(arg));
            }
            callbacks.addAll(chained(rp));
            Verb verb = new Verb("post".equals(method) ? "POST" : "GET", "implied", null);
            return site(i, lp, rp, kind, args.get(0), verb, payload, callbacks);
        }

        private CallSite fetch(int i, int lp) {
            int rp = match(lp);
            if (rp < 0) return unparseable(i, lp, lp, "fetch", "unbalanced parentheses — its arguments could not be delimited");
            List<int[]> args = split(lp + 1, rp, COMMA);
            if (args.isEmpty()) return unparseable(i, lp, rp, "fetch", "called without arguments");
            Verb verb = new Verb("GET", "default", null);
            String payload = null;
            if (args.size() >= 2) {
                int[] object = objectLiteral(args.get(1), start(i));
                if (object != null) {
                    Map<String, int[]> props = properties(object);
                    verb = verbOf(props, start(i));
                    payload = payload(props.get("body"), start(i));
                }
            }
            return site(i, lp, rp, "fetch", args.get(0), verb, payload, chained(rp));
        }

        /** DataTables {@code ajax:} — an options object ({@code url}, {@code type}) or the URL itself. */
        private CallSite dataTables(int keyIdx, int valueStart) {
            int end = valueEnd(valueStart);
            if (valueStart >= end) return unparseable(keyIdx, valueStart, valueStart, "DataTables", "empty `ajax` option");
            int[] value = {valueStart, end};
            if (is(valueStart, LBRACE) && match(valueStart) == end - 1) {
                Map<String, int[]> props = properties(value);
                int[] url = props.get("url");
                if (url == null) return unparseable(keyIdx, valueStart, end - 1, "DataTables", "no `url` in the `ajax` option");
                Verb verb = verbOf(props, start(keyIdx));
                if ("default".equals(verb.source())) verb = new Verb("GET", "default", null);
                int[] data = props.get("data");
                String payload = data != null && isFunctionExpr(data) ? "request parameters built by a function" : payload(data, start(keyIdx));
                return site(keyIdx, valueStart, end - 1, "DataTables", url, verb, payload, List.of());
            }
            if (isFunctionExpr(value)) {
                return unparseable(keyIdx, valueStart, end - 1, "DataTables", "the `ajax` option is a function that issues the request itself");
            }
            return site(keyIdx, valueStart, end - 1, "DataTables", value, new Verb("GET", "default", null), null, List.of());
        }

        // -------------------------------------------------------------------
        // Assembly
        // -------------------------------------------------------------------

        /** {@code open}/{@code close} are token indexes of the delimiters around the arguments. */
        private CallSite site(int i, int open, int close, String kind, int[] urlRange, Verb verb,
                              String payload, List<String> callbacks) {
            if (verb.failure() != null) return unparseable(i, open, close, kind, verb.failure());
            Url url = url(urlRange, start(i), 0, new HashSet<>());
            if (!url.ok()) return unparseable(i, open, close, kind, url.failure());
            List<String> templates = url.templates().stream().map(JQueryAjaxCallExtractor::canonical)
                    .distinct().collect(Collectors.toList());
            return new CallSite(line(i), start(i), argsStart(open), argsEnd(open, close), kind, function(i),
                    HttpVerb.valueOf(verb.name()), verb.source(), templates, url.provenance(), payload, callbacks, null);
        }

        private CallSite unparseable(int i, int open, int close, String kind, String reason) {
            return new CallSite(line(i), start(i), argsStart(open), argsEnd(open, close), kind, function(i),
                    null, null, List.of(), null, null, List.of(), reason);
        }

        private int argsStart(int open) {
            return is(open, LPAREN) || is(open, LBRACE) ? toks.get(open).getStopIndex() + 1 : start(open);
        }

        private int argsEnd(int open, int close) {
            if (close < open) return argsStart(open);
            return is(close, RPAREN) || is(close, RBRACE) ? start(close) : toks.get(close).getStopIndex() + 1;
        }

        private String function(int i) {
            FunctionSpan named = JspBundleExtractor.innermostNamed(spans, start(i));
            return named == null ? "unknown" : named.name();
        }

        private Verb verbOf(Map<String, int[]> props, int at) {
            String source = props.containsKey("method") ? "method" : props.containsKey("type") ? "type" : null;
            if (source == null) return new Verb("GET", "default", null);
            int[] value = props.get(source);
            String literal = stringValue(value, at);
            if (literal == null) return new Verb(null, null, "the HTTP verb is the runtime value `" + snippet(value) + "`");
            String upper = literal.strip().toUpperCase(Locale.ROOT);
            try {
                HttpVerb.valueOf(upper);
            } catch (IllegalArgumentException e) {
                return new Verb(null, null, "unknown HTTP verb `" + literal + "`");
            }
            return new Verb(upper, source, null);
        }

        private List<String> chained(int rp) {
            List<String> out = new ArrayList<>();
            int j = rp + 1;
            while (is(j, DOT) && is(j + 1, ID) && PROMISE_METHODS.contains(text(j + 1)) && is(j + 2, LPAREN)) {
                int close = match(j + 2);
                if (close < 0) break;
                List<int[]> args = split(j + 3, close, COMMA);
                for (int k = 0; k < args.size(); k++) {
                    out.add(text(j + 1) + (k == 0 ? "" : "#" + (k + 1)) + ": " + describeCallback(args.get(k)));
                }
                j = close + 1;
            }
            return out;
        }

        // -------------------------------------------------------------------
        // URL reconstruction
        // -------------------------------------------------------------------

        private Url url(int[] r, int at, int depth, Set<String> visiting) {
            if (r[0] >= r[1]) return Url.fail("empty URL expression");

            int question = topLevel(r[0], r[1], QUESTION);
            if (question >= 0) {
                int colon = topLevel(question + 1, r[1], COLON);
                if (colon >= 0) {
                    return merge(List.of(url(new int[]{question + 1, colon}, at, depth, visiting),
                            url(new int[]{colon + 1, r[1]}, at, depth, visiting)), "conditional");
                }
            }

            List<int[]> parts = splitPlus(r);
            if (parts.size() == 1) {
                int[] p = parts.get(0);
                if (isName(p) && !CONTEXT_PATH_IDENTIFIERS.contains(lastName(p))) return name(p, at, depth, visiting);
                if (isCall(p)) {
                    List<int[]> callArgs = callArgs(p);
                    if (!callArgs.isEmpty()) {
                        Url first = url(callArgs.get(0), at, depth + 1, visiting);
                        if (first.ok()) {
                            return new Url(first.templates(), "built by " + calleeName(p) + "()", null);
                        }
                    }
                    return Url.fail("the URL is computed by `" + snippet(p) + "`");
                }
            }

            List<String> alternatives = List.of("");
            boolean anyLiteral = false;
            String markupSource = null;
            Set<String> provenance = new LinkedHashSet<>();
            for (int[] p : parts) {
                List<String> pieces;
                if (isSingle(p, STRING_SQ, STRING_DQ)) {
                    pieces = List.of(unquote(text(p[0])));
                    anyLiteral = true;
                } else if (isSingle(p, TEMPLATE_STRING)) {
                    String t = template(text(p[0]));
                    pieces = List.of(t);
                    anyLiteral |= t.replace("{param}", "").chars().anyMatch(Character::isLetterOrDigit);
                } else if (isSingle(p, NUMBER)) {
                    pieces = List.of(text(p[0]));
                } else if (isName(p) && CONTEXT_PATH_IDENTIFIERS.contains(lastName(p))) {
                    pieces = List.of("");
                } else if (isName(p) && depth < MAX_DEPTH) {
                    // A name inside a concatenation is substituted only when it is a constant, or one of
                    // a few (sendFap('terminer') / sendFap('enregistrer') name distinct endpoints). More
                    // values than that are data passed through a path segment — a parameter. Resolving
                    // to something that itself holds a parameter would claim more than we know.
                    Url sub = name(p, at, depth + 1, visiting);
                    if (sub.ok() && sub.templates().size() <= MAX_ENUMERATED
                            && sub.templates().stream().noneMatch(t -> t.contains("{param}"))) {
                        pieces = sub.templates();
                        anyLiteral = true;
                        provenance.add(sub.provenance());
                    } else {
                        pieces = List.of("{param}");
                    }
                } else {
                    pieces = List.of("{param}");
                    Matcher data = DATA_ATTRIBUTE.matcher(compact(p));
                    if (data.matches()) {
                        markupSource = "the URL is read from the markup attribute `data-" + data.group(2)
                                + "` of #" + data.group(1) + ", rendered by the server";
                    }
                }
                alternatives = combine(alternatives, pieces);
            }
            if (!anyLiteral) {
                return Url.fail(markupSource != null ? markupSource : "the URL is the runtime value `" + snippet(r) + "`");
            }
            return new Url(alternatives, provenance.isEmpty() ? "literal" : String.join(", ", provenance), null);
        }

        /** A name used as (part of) a URL: variable, parameter traced to its call sites, or property. */
        private Url name(int[] p, int at, int depth, Set<String> visiting) {
            String chain = snippet(p).replaceAll("\\s+", "");
            String last = lastName(p);
            FunctionSpan scope = JspBundleExtractor.innermost(spans, at);
            String key = chain + "@" + (scope == null ? -1 : scope.bodyStart()) + "@" + at;
            Url memo = nameMemo.get(key);
            if (memo != null) return memo;
            if (depth > MAX_DEPTH) return Url.fail("the URL `" + chain + "` is defined too indirectly to follow");
            if (!visiting.add(key)) return Url.fail("the URL `" + chain + "` is defined circularly");
            Url result;
            try {
                if (p[1] - p[0] > 3) {
                    // page.fap.id — a field of a runtime object, not something the module defines.
                    result = Url.fail("the URL uses `" + chain + "`, a field of a runtime object");
                } else if (p[1] - p[0] == 3) {
                    result = property(last, chain, depth, visiting);
                } else {
                    Url variable = variable(last, at, depth, visiting);
                    Url parameter = variable == null ? parameter(last, at, depth, visiting) : null;
                    result = variable != null ? variable : parameter != null ? parameter
                            : Url.fail("the URL is the runtime value `" + chain + "` (no assignment found)");
                }
            } finally {
                visiting.remove(key);
            }
            nameMemo.put(key, result);
            return result;
        }

        private Url variable(String name, int at, int depth, Set<String> visiting) {
            List<int[]> values = assignments(name, at);
            if (values.isEmpty()) return null;
            List<Url> outcomes = new ArrayList<>();
            for (int[] v : values) outcomes.add(url(v, start(v[0]), depth + 1, visiting));
            Url merged = merge(outcomes, "variable `" + name + "`");
            return merged.ok() ? merged : Url.fail(merged.failure() + " (assigned to `" + name + "`)");
        }

        private Url parameter(String name, int at, int depth, Set<String> visiting) {
            List<FunctionSpan> chain = spans.stream().filter(s -> s.contains(at))
                    .sorted((a, b) -> Integer.compare(b.bodyStart(), a.bodyStart())).collect(Collectors.toList());
            for (FunctionSpan span : chain) {
                int index = params(span).indexOf(name);
                if (index < 0) continue;
                if (span.name() == null) return Url.fail("the URL is `" + name + "`, a parameter of a callback");
                List<Url> outcomes = new ArrayList<>();
                int callers = 0;
                for (int lp : invocations(span)) {
                    List<int[]> args = split(lp + 1, match(lp), COMMA);
                    if (index >= args.size()) continue;
                    callers++;
                    outcomes.add(url(args.get(index), start(lp), depth + 1, visiting));
                }
                if (callers == 0) {
                    return Url.fail("the URL is `" + name + "`, a parameter of " + span.name()
                            + "(), which this module never calls with it");
                }
                Url merged = merge(outcomes, "parameter `" + name + "` of " + span.name() + "(), from its call sites");
                return merged.ok() ? merged : Url.fail("the URL is `" + name + "`, a parameter of " + span.name()
                        + "(), whose call sites pass runtime values");
            }
            return null;
        }

        /**
         * {@code page.importUrl}: resolved only when the module gives the property one value. A
         * generic name ({@code id}) is a key of many object literals with unrelated values —
         * substituting any of them would be a confident wrong answer.
         */
        private Url property(String name, String chain, int depth, Set<String> visiting) {
            List<Url> outcomes = new ArrayList<>();
            for (int k = 0; k < toks.size() - 2; k++) {
                if (!isId(k, name)) continue;
                boolean objectKey = is(k + 1, COLON) && (is(k - 1, LBRACE) || is(k - 1, COMMA)) && !isId(k + 2, "function");
                boolean assigned = is(k - 1, DOT) && is(k + 1, EQUALS) && !is(k + 2, EQUALS);
                if (!objectKey && !assigned) continue;
                int from = k + 2;
                outcomes.add(url(new int[]{from, valueEnd(from)}, start(from), depth + 1, visiting));
            }
            if (outcomes.isEmpty()) return Url.fail("the URL is the runtime value `" + chain + "`");
            // A placeholder definition (importUrl: null, later assigned) resolves to nothing and is ignored.
            Set<List<String>> distinct = outcomes.stream().filter(Url::ok).map(Url::templates)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (distinct.size() > 1) {
                return Url.fail("the URL uses `" + chain + "`, and property `" + name + "` has "
                        + distinct.size() + " different values in this module");
            }
            Url merged = merge(outcomes, "property `" + name + "`");
            return merged.ok() ? merged : Url.fail(merged.failure() + " (property `" + name + "`)");
        }

        private static Url merge(List<Url> outcomes, String provenance) {
            Set<String> templates = new LinkedHashSet<>();
            Set<String> inner = new LinkedHashSet<>();
            for (Url u : outcomes) {
                if (!u.ok()) continue;
                templates.addAll(u.templates());
                if (u.provenance() != null && !"literal".equals(u.provenance())) inner.add(u.provenance());
            }
            if (templates.isEmpty()) {
                if (outcomes.isEmpty()) return Url.fail("no value found");
                // The most specific reason wins: naming the markup attribute beats "a runtime value".
                return outcomes.stream().filter(u -> u.failure() != null && u.failure().contains("markup attribute"))
                        .findFirst().orElse(outcomes.get(0));
            }
            List<String> capped = templates.stream().limit(MAX_ALTERNATIVES).collect(Collectors.toList());
            String how = inner.isEmpty() ? provenance : provenance + " → " + String.join(", ", inner);
            if (templates.size() > MAX_ALTERNATIVES) {
                how += " (first " + MAX_ALTERNATIVES + " of " + templates.size() + " URLs)"; // stated, not silently cut
            }
            return new Url(capped, how, null);
        }

        private static List<String> combine(List<String> prefixes, List<String> pieces) {
            List<String> out = new ArrayList<>();
            for (String a : prefixes) {
                for (String b : pieces) {
                    if (out.size() >= MAX_ALTERNATIVES) return out;
                    out.add(a + b);
                }
            }
            return out;
        }

        // -------------------------------------------------------------------
        // Names: assignments, parameters, call sites
        // -------------------------------------------------------------------

        /**
         * Values assigned to {@code name} before {@code at}, in {@code at}'s scope or an enclosing one —
         * from the innermost such scope only: {@code const fap = page.toJsonFap()} inside a function
         * shadows a module-level {@code fap = {…}}, and merging the two would report both.
         */
        private List<int[]> assignments(String name, int at) {
            List<int[]> values = new ArrayList<>();
            List<Integer> scopes = new ArrayList<>();
            for (int k = 0; k < toks.size() - 2; k++) {
                if (!isId(k, name) || !is(k + 1, EQUALS) || is(k + 2, EQUALS) || is(k - 1, DOT)) continue;
                if (start(k) >= at) break;
                FunctionSpan scope = JspBundleExtractor.innermost(spans, start(k));
                if (scope != null && !scope.contains(at)) continue;
                values.add(new int[]{k + 2, valueEnd(k + 2)});
                scopes.add(scope == null ? -1 : scope.bodyStart());
            }
            int deepest = scopes.stream().mapToInt(Integer::intValue).max().orElse(-1);
            List<int[]> innermost = new ArrayList<>();
            for (int v = 0; v < values.size(); v++) {
                if (scopes.get(v) == deepest) innermost.add(values.get(v));
            }
            return innermost;
        }

        /** Parameter names of a function, read back from the characters before its body. */
        private List<String> params(FunctionSpan span) {
            // Find the token that opens the body, then walk back over "=>" to the parameter list.
            int body = tokenAt(span.bodyStart() - 1);
            if (body < 0) return List.of();
            int j = body - 1;
            if (is(j, ARROW)) j--;
            if (is(j, ID)) return List.of(text(j)); // x => { … }
            if (!is(j, RPAREN)) return List.of();
            int depth = 0;
            int open = -1;
            for (int k = j; k >= 0; k--) {
                if (is(k, RPAREN)) depth++;
                else if (is(k, LPAREN) && --depth == 0) { open = k; break; }
            }
            if (open < 0) return List.of();
            List<String> names = new ArrayList<>();
            for (int[] param : split(open + 1, j, COMMA)) {
                int k = param[0];
                if (is(k, ELLIPSIS)) k++;
                names.add(is(k, ID) ? text(k) : "");
            }
            return names;
        }

        /** Opening-parenthesis token indexes of every call to {@code span}'s function in this module. */
        private List<Integer> invocations(FunctionSpan span) {
            List<Integer> out = new ArrayList<>();
            for (int k = 0; k < toks.size() - 1; k++) {
                if (!isId(k, span.name()) || !is(k + 1, LPAREN) || isId(k - 1, "function")) continue;
                int close = match(k + 1);
                if (close < 0 || is(close + 1, LBRACE)) continue; // name(…) { … } is the definition itself
                if (start(k) >= span.bodyStart() - 1 && start(k) < span.bodyEnd() && span.contains(start(k))) {
                    // A recursive call from inside the function passes its own parameter back — no new information.
                    continue;
                }
                out.add(k + 1);
            }
            return out;
        }

        private String stringValue(int[] r, int at) {
            if (isSingle(r, STRING_SQ, STRING_DQ)) return unquote(text(r[0]));
            if (isName(r) && r[1] - r[0] == 1) {
                for (int[] v : assignments(text(r[0]), at)) {
                    if (isSingle(v, STRING_SQ, STRING_DQ)) return unquote(text(v[0]));
                }
            }
            return null;
        }

        // -------------------------------------------------------------------
        // Payload and callbacks
        // -------------------------------------------------------------------

        private String payload(int[] r, int at) {
            if (r == null || r[0] >= r[1]) return null;
            String text = snippet(r);
            if ("null".equals(text) || "undefined".equals(text)) return null;
            if (isFunctionExpr(r)) return "built by a function at request time";
            if (isObject(r)) return "fields " + keys(r);
            Matcher serialize = SERIALIZE.matcher(text.replaceAll("\\s+", ""));
            if (serialize.matches()) return "serialized form " + serialize.group(1);
            if (text.replaceAll("\\s+", "").endsWith(".serialize()")) return "serialized form (`" + text + "`)";
            if (text.startsWith("new FormData")) return "multipart form data (FormData)";
            if (isCall(r) && "JSON.stringify".equals(calleeChain(r))) {
                List<int[]> args = callArgs(r);
                return args.isEmpty() ? "JSON" : "JSON of " + subject(args.get(0), at);
            }
            if (isCall(r)) return "value of " + calleeName(r) + "()";
            if (isName(r)) return subject(r, at);
            return "`" + truncate(text, 40) + "`";
        }

        /** What a payload expression denotes, following one variable assignment. */
        private String subject(int[] r, int at) {
            if (isObject(r)) return "fields " + keys(r);
            if (isCall(r)) return calleeName(r) + "()";
            if (isName(r) && r[1] - r[0] == 1) {
                for (int[] v : assignments(text(r[0]), at)) {
                    if (isCall(v)) return calleeName(v) + "() (via `" + text(r[0]) + "`)";
                    if (isObject(v)) return "fields " + keys(v) + " (via `" + text(r[0]) + "`)";
                    if (snippet(v).startsWith("new FormData")) return "multipart form data (FormData)";
                }
            }
            return "`" + truncate(snippet(r), 40) + "`";
        }

        private String keys(int[] object) {
            List<String> keys = new ArrayList<>(properties(object).keySet());
            String shown = keys.stream().limit(6).collect(Collectors.joining(", "));
            return "{" + shown + (keys.size() > 6 ? ", …" : "") + "}";
        }

        private String describeCallback(int[] r) {
            if (isFunctionExpr(r)) return "inline";
            if (isName(r)) return snippet(r);
            return "expression";
        }

        // -------------------------------------------------------------------
        // Structure
        // -------------------------------------------------------------------

        /** The object literal a settings argument is, or is assigned from; null when neither. */
        private int[] objectLiteral(int[] r, int at) {
            if (isObject(r)) return r;
            if (isName(r) && r[1] - r[0] == 1) {
                for (int[] v : assignments(text(r[0]), at)) {
                    if (isObject(v)) return v;
                }
            }
            return null;
        }

        /** Property name → value token range, for an object literal range (last definition wins). */
        private Map<String, int[]> properties(int[] object) {
            Map<String, int[]> props = new LinkedHashMap<>();
            for (int[] part : split(object[0] + 1, object[1] - 1, COMMA)) {
                int k = part[0];
                if (k >= part[1]) continue;
                String key = is(k, STRING_SQ) || is(k, STRING_DQ) ? unquote(text(k)) : text(k);
                if (is(k + 1, COLON)) props.put(key, new int[]{k + 2, part[1]});
                else if (is(k + 1, LPAREN)) props.put(key, new int[]{k, part[1]});   // success(d) { … }
                else if (part[1] - k == 1 && is(k, ID)) props.put(key, new int[]{k, k + 1}); // { url }
            }
            return props;
        }

        private boolean isObject(int[] r) {
            return is(r[0], LBRACE) && match(r[0]) == r[1] - 1;
        }

        private boolean isFunctionExpr(int[] r) {
            int k = r[0];
            if (isId(k, "async")) k++;
            if (isId(k, "function")) return true;
            if (is(k, ID) && is(k + 1, ARROW)) return true;
            if (is(k, LPAREN)) {
                int close = match(k);
                return close > 0 && is(close + 1, ARROW);
            }
            if (is(k, ID) && is(k + 1, LPAREN)) { // method shorthand inside an object literal
                int close = match(k + 1);
                return close > 0 && is(close + 1, LBRACE);
            }
            return false;
        }

        /** {@code a}, {@code this.a}, {@code page.a.b} — exactly, over the whole range. */
        private boolean isName(int[] r) {
            if (r[0] >= r[1]) return false;
            for (int k = r[0]; k < r[1]; k++) {
                boolean even = (k - r[0]) % 2 == 0;
                if (even && !(is(k, ID) || is(k, THIS))) return false;
                if (!even && !is(k, DOT)) return false;
            }
            return (r[1] - r[0]) % 2 == 1;
        }

        /** {@code callee.chain(args)} spanning the whole range. */
        private boolean isCall(int[] r) {
            int k = r[0];
            while (k + 1 < r[1] && (is(k, ID) || is(k, THIS)) && is(k + 1, DOT)) k += 2;
            if (!(is(k, ID) || is(k, THIS)) || !is(k + 1, LPAREN)) return false;
            return match(k + 1) == r[1] - 1;
        }

        private List<int[]> callArgs(int[] call) {
            int lp = r1Paren(call);
            return split(lp + 1, call[1] - 1, COMMA);
        }

        private int r1Paren(int[] call) {
            for (int k = call[0]; k < call[1]; k++) if (is(k, LPAREN)) return k;
            return call[1] - 1;
        }

        private String calleeChain(int[] call) {
            StringBuilder sb = new StringBuilder();
            for (int k = call[0]; k < r1Paren(call); k++) sb.append(text(k));
            return sb.toString();
        }

        private String calleeName(int[] call) {
            return text(r1Paren(call) - 1);
        }

        private String lastName(int[] r) {
            return text(r[1] - 1);
        }

        /** Matching closer for the opener at {@code open} (any bracket kind), or -1. */
        private int match(int open) {
            int depth = 0;
            for (int j = open; j < toks.size(); j++) {
                int t = type(j);
                if (t == LPAREN || t == LBRACE || t == LBRACKET) depth++;
                else if (t == RPAREN || t == RBRACE || t == RBRACKET) {
                    if (--depth == 0) return j;
                }
            }
            return -1;
        }

        /** End (exclusive) of the expression starting at {@code from}: a top-level ; or , or an unmatched closer. */
        private int valueEnd(int from) {
            int depth = 0;
            for (int j = from; j < toks.size(); j++) {
                int t = type(j);
                if (t == LPAREN || t == LBRACE || t == LBRACKET) depth++;
                else if (t == RPAREN || t == RBRACE || t == RBRACKET) {
                    if (depth == 0) return j;
                    depth--;
                } else if (depth == 0 && (t == SEMICOLON || t == COMMA)) {
                    return j;
                }
                // A line break ends the expression unless an operator carries it over (ASI).
                if (depth == 0 && j + 1 < toks.size() && toks.get(j + 1).getLine() > toks.get(j).getLine()
                        && !isPlus(j) && !isPlus(j + 1) && !is(j + 1, DOT) && !is(j, DOT)
                        && !is(j, QUESTION) && !is(j + 1, QUESTION) && !is(j, COLON) && !is(j + 1, COLON)
                        && !is(j, EQUALS)) {
                    return j + 1;
                }
            }
            return toks.size();
        }

        private List<int[]> split(int from, int to, int separator) {
            List<int[]> parts = new ArrayList<>();
            int depth = 0;
            int partStart = from;
            for (int j = from; j < to; j++) {
                int t = type(j);
                if (t == LPAREN || t == LBRACE || t == LBRACKET) depth++;
                else if (t == RPAREN || t == RBRACE || t == RBRACKET) depth--;
                else if (depth == 0 && t == separator) {
                    if (j > partStart) parts.add(new int[]{partStart, j});
                    partStart = j + 1;
                }
            }
            if (to > partStart) parts.add(new int[]{partStart, to});
            return parts;
        }

        private List<int[]> splitPlus(int[] r) {
            List<int[]> parts = new ArrayList<>();
            int depth = 0;
            int partStart = r[0];
            for (int j = r[0]; j < r[1]; j++) {
                int t = type(j);
                if (t == LPAREN || t == LBRACE || t == LBRACKET) depth++;
                else if (t == RPAREN || t == RBRACE || t == RBRACKET) depth--;
                else if (depth == 0 && isPlus(j)) {
                    if (j > partStart) parts.add(new int[]{partStart, j});
                    partStart = j + 1;
                }
            }
            if (r[1] > partStart) parts.add(new int[]{partStart, r[1]});
            return parts;
        }

        private int topLevel(int from, int to, int type) {
            int depth = 0;
            for (int j = from; j < to; j++) {
                int t = type(j);
                if (t == LPAREN || t == LBRACE || t == LBRACKET) depth++;
                else if (t == RPAREN || t == RBRACE || t == RBRACKET) depth--;
                else if (depth == 0 && t == type) return j;
            }
            return -1;
        }

        /** Index of the token starting at character {@code offset}, or -1. */
        private int tokenAt(int offset) {
            int lo = 0;
            int hi = toks.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int s = start(mid);
                if (s == offset) return mid;
                if (s < offset) lo = mid + 1;
                else hi = mid - 1;
            }
            return -1;
        }

        // -------------------------------------------------------------------
        // Token access
        // -------------------------------------------------------------------

        private int type(int i) { return i >= 0 && i < toks.size() ? toks.get(i).getType() : -1; }

        private boolean is(int i, int type) { return type(i) == type; }

        private boolean isPlus(int i) { return is(i, OTHER) && "+".equals(text(i)); }

        private boolean isId(int i, String... names) {
            if (!is(i, ID)) return false;
            String t = text(i);
            for (String n : names) if (n.equals(t)) return true;
            return false;
        }

        private boolean isSingle(int[] r, int... types) {
            if (r[1] - r[0] != 1) return false;
            for (int t : types) if (is(r[0], t)) return true;
            return false;
        }

        private String text(int i) { return toks.get(i).getText(); }

        private int start(int i) { return toks.get(i).getStartIndex(); }

        private int line(int i) { return toks.get(i).getLine(); }

        /** The range's tokens joined with no whitespace — for matching an expression's shape. */
        private String compact(int[] r) {
            StringBuilder sb = new StringBuilder();
            for (int k = r[0]; k < r[1] && k < toks.size(); k++) sb.append(text(k));
            return sb.toString();
        }

        private String snippet(int[] r) {
            StringBuilder sb = new StringBuilder();
            for (int k = r[0]; k < r[1] && k < toks.size(); k++) {
                if (k > r[0] && needsSpace(k)) sb.append(' ');
                sb.append(text(k));
            }
            return truncate(sb.toString(), 60);
        }

        private boolean needsSpace(int k) {
            return isPlus(k) || isPlus(k - 1) || is(k, QUESTION) || is(k - 1, QUESTION)
                    || is(k, COLON) || is(k - 1, COLON) || is(k - 1, COMMA)
                    || (isWord(k - 1) && isWord(k)); // "new FormData", not "newFormData"
        }

        private boolean isWord(int k) {
            int t = type(k);
            return t == ID || t == NUMBER || t == NEW || t == THIS || t == RETURN || t == CONST || t == LET || t == VAR;
        }
    }

    // -----------------------------------------------------------------------
    // Text helpers
    // -----------------------------------------------------------------------

    /** A reconstructed template in the backend's canonical form, without its query string. */
    static String canonical(String template) {
        String t = template;
        int q = t.indexOf('?');
        if (q >= 0) t = t.substring(0, q);
        int h = t.indexOf('#');
        if (h >= 0) t = t.substring(0, h);
        return PathNormalizer.normalize(HttpVerb.GET, t).pathTemplate();
    }

    private static String unquote(String literal) {
        if (literal.length() < 2) return literal;
        return literal.substring(1, literal.length() - 1).replace("\\'", "'").replace("\\\"", "\"");
    }

    /** A template literal's text: {@code ${basepath}} contributes nothing, any other {@code ${…}} is a parameter. */
    private static String template(String literal) {
        String inner = literal.length() >= 2 ? literal.substring(1, literal.length() - 1) : literal;
        Matcher m = TEMPLATE_EXPR.matcher(inner);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group().substring(2, m.group().length() - 1).strip();
            String last = expr.substring(expr.lastIndexOf('.') + 1);
            m.appendReplacement(sb, Matcher.quoteReplacement(CONTEXT_PATH_IDENTIFIERS.contains(last) ? "" : "{param}"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
