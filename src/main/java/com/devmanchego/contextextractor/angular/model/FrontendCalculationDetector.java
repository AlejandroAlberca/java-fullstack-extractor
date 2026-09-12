package com.devmanchego.contextextractor.angular.model;

import com.devmanchego.contextextractor.angular.template.TemplateResolver;
import com.devmanchego.contextextractor.java.model.CalculationEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2c: Frontend — detects <em>value</em> computations in Angular/TypeScript
 * components (as opposed to display-only formatting).
 *
 * <p>Value computations are what can diverge from a backend calculation, so these are
 * the ones that feed the double-calculation check:
 * <ul>
 *   <li>Signal {@code computed(() => …)} — Angular 16+ reactive value</li>
 *   <li>Component getters {@code get field() { return …; }}</li>
 *   <li>RxJS derivations {@code control.valueChanges.pipe(… map(…) …)}</li>
 * </ul>
 * Display transforms (pipes, {@code {{ a * b }}} interpolation) are intentionally NOT
 * treated as value computations — they format an existing value and do not compete with
 * the backend formula.
 */
public final class FrontendCalculationDetector {

    private static final Logger log = LoggerFactory.getLogger(FrontendCalculationDetector.class);

    // fieldName = computed(() => EXPR)
    private static final Pattern SIGNAL_COMPUTED = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*computed\\s*\\(\\s*\\(\\)\\s*=>\\s*([^;]+?)\\)\\s*;");

    // get fieldName(): Type { return EXPR; }
    private static final Pattern COMPONENT_GETTER = Pattern.compile(
            "get\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*\\)\\s*(?::\\s*[^{]+)?\\{\\s*return\\s+([^;]+);");

    // control.valueChanges.pipe( ... map(... => EXPR) ... )
    private static final Pattern VALUE_CHANGES = Pattern.compile(
            "get\\(\\s*['\"]([a-zA-Z_][a-zA-Z0-9_]*)['\"]\\s*\\)\\s*[?.]*\\.valueChanges");

    // get fieldName(): Type {   — header only, body extracted via brace-matching (Phase 3)
    private static final Pattern GETTER_HEADER = Pattern.compile(
            "get\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*\\)\\s*(?::\\s*[^{]+)?\\{");

    // methodName(args): ReturnType {   — regular class method with an explicit return type,
    // which naturally excludes control-flow keywords (if/for/while/…) and the constructor.
    // The return type is captured (group 3) so `void` methods can be excluded: a method that
    // returns nothing cannot BE a field's calculation, no matter how many `this.x` it touches.
    private static final Pattern METHOD_HEADER = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()]*)\\)\\s*:\\s*([a-zA-Z_][\\w<>\\[\\].,\\s]*?)\\s*\\{");

    private static final Pattern SOLE_RETURN = Pattern.compile(
            "\\A\\s*return\\s+([\\s\\S]+?);\\s*\\z");

    private static final int MAX_SLICE_STATEMENTS = 12;

    /**
     * Scans an Angular component (.ts) for frontend value computations.
     *
     * @param componentTsPath path to the component's {@code .ts} file
     * @return map of field name → calculation evidences (empty if none / unreadable)
     */
    public Map<String, List<CalculationEvidence>> detectInComponent(String componentTsPath) {
        Map<String, List<CalculationEvidence>> results = new LinkedHashMap<>();

        String code = readFile(componentTsPath);
        if (code == null) {
            return results;
        }
        String fileLabel = Path.of(componentTsPath).getFileName().toString();

        detectSignalComputed(code, componentTsPath, fileLabel, results);
        detectComponentGetters(code, componentTsPath, fileLabel, results);
        detectRxjsDerivations(code, componentTsPath, fileLabel, results);
        detectRegularMethods(code, componentTsPath, fileLabel, results);
        detectMultiStatementGetters(code, componentTsPath, fileLabel, results);

        return results;
    }

    private void detectSignalComputed(String code, String path, String fileLabel,
                                      Map<String, List<CalculationEvidence>> results) {
        Matcher m = SIGNAL_COMPUTED.matcher(code);
        while (m.find()) {
            String field = m.group(1);
            String expr = normalize(m.group(2));
            add(results, field, CalculationEvidence.builder()
                    .targetField(field)
                    .locus(CalculationEvidence.Locus.FRONTEND_SIGNAL_COMPUTED)
                    .expression(expr)
                    .inputFields(extractInputs(expr))
                    .sourceFile(path)
                    .sourceClass(fileLabel)
                    .sourceMethod(field) // renderer appends "()"
                    .lineNumber(lineOf(code, m.start()))
                    .confidence(CalculationEvidence.Confidence.HIGH)
                    .tier(1)
                    .description("Angular Signal computed()")
                    .build());
        }
    }

    private void detectComponentGetters(String code, String path, String fileLabel,
                                        Map<String, List<CalculationEvidence>> results) {
        Matcher m = COMPONENT_GETTER.matcher(code);
        while (m.find()) {
            String field = m.group(1);
            String expr = normalize(m.group(2));
            if (!isValueCalculation(expr)) {
                continue; // plain passthrough getter → not a computation
            }
            add(results, field, CalculationEvidence.builder()
                    .targetField(field)
                    .locus(CalculationEvidence.Locus.FRONTEND_GETTER)
                    .expression(expr)
                    .inputFields(extractInputs(expr))
                    .sourceFile(path)
                    .sourceClass(fileLabel)
                    .sourceMethod("get " + field) // renderer appends "()"
                    .lineNumber(lineOf(code, m.start()))
                    .confidence(CalculationEvidence.Confidence.MEDIUM)
                    .tier(2)
                    .description("Component getter computation")
                    .build());
        }
    }

    private void detectRxjsDerivations(String code, String path, String fileLabel,
                                       Map<String, List<CalculationEvidence>> results) {
        Matcher m = VALUE_CHANGES.matcher(code);
        while (m.find()) {
            String control = m.group(1);
            // Only treat as a derivation when the reactive pipe contains a map() transform.
            int tail = Math.min(code.length(), m.end() + 400);
            String window = code.substring(m.end(), tail);
            if (!window.contains(".pipe(") || !window.contains("map(")) {
                continue;
            }
            String field = control + "$derived";
            add(results, field, CalculationEvidence.builder()
                    .targetField(field)
                    .locus(CalculationEvidence.Locus.FRONTEND_RXJS_VALUECHANAGES)
                    .expression("valueChanges of `" + control + "` .pipe(map(…))")
                    .inputField(control)
                    .sourceFile(path)
                    .sourceClass(fileLabel)
                    .sourceMethod("ngOnInit") // renderer appends "()"
                    .lineNumber(lineOf(code, m.start()))
                    .confidence(CalculationEvidence.Confidence.LOW)
                    .tier(2)
                    .description("RxJS valueChanges derivation on control '" + control + "'")
                    .build());
        }
    }

    /**
     * Phase 3: regular class methods with an explicit return type ({@code name(args): Type {…}}).
     * These are invisible to {@link #detectComponentGetters}, which only matches {@code get}
     * accessors. A single-return body is treated as a FORMULA (mirrors a getter); a
     * multi-statement body that contains a genuine calculation signal is cited verbatim as a
     * SLICE (mirrors backend Phase 3b — no symbolic summary, just the source).
     */
    private void detectRegularMethods(String code, String path, String fileLabel,
                                      Map<String, List<CalculationEvidence>> results) {
        Matcher m = METHOD_HEADER.matcher(code);
        while (m.find()) {
            String name = m.group(1);
            String returnType = m.group(3).trim();
            if (RESERVED_HEADER_NAMES.contains(name) || "void".equals(returnType)) continue;

            int braceStart = code.indexOf('{', m.end() - 1);
            if (braceStart < 0) continue;
            int braceEnd = findMatchingBrace(code, braceStart);
            if (braceEnd < 0) continue;
            String body = code.substring(braceStart + 1, braceEnd);

            emitFromMethodBody(results, path, fileLabel, name, body, m.start(), code);
        }
    }

    /**
     * Phase 3: getters whose body is NOT a single {@code return EXPR;} — invisible to
     * {@link #detectComponentGetters} by construction (its regex requires {@code return}
     * immediately after {@code {}). Cited verbatim as a SLICE.
     */
    private void detectMultiStatementGetters(String code, String path, String fileLabel,
                                             Map<String, List<CalculationEvidence>> results) {
        Matcher m = GETTER_HEADER.matcher(code);
        while (m.find()) {
            String field = m.group(1);
            int braceStart = code.indexOf('{', m.end() - 1);
            if (braceStart < 0) continue;
            int braceEnd = findMatchingBrace(code, braceStart);
            if (braceEnd < 0) continue;
            String body = code.substring(braceStart + 1, braceEnd);

            if (SOLE_RETURN.matcher(body.trim()).matches()) {
                continue; // single-return getters are already handled by detectComponentGetters
            }
            emitSliceIfCalculation(results, path, fileLabel, field, "get " + field,
                    body, m.start(), code);
        }
    }

    private static final Set<String> RESERVED_HEADER_NAMES = Set.of(
            "if", "for", "while", "switch", "catch", "constructor", "function"
    );

    private void emitFromMethodBody(Map<String, List<CalculationEvidence>> results,
                                    String path, String fileLabel, String name,
                                    String body, int startOffset, String code) {
        Matcher sole = SOLE_RETURN.matcher(body.trim());
        if (sole.matches()) {
            String expr = normalize(sole.group(1));
            if (!isValueCalculation(expr)) return;
            add(results, name, CalculationEvidence.builder()
                    .targetField(name)
                    .locus(CalculationEvidence.Locus.FRONTEND_GETTER)
                    .expression(expr)
                    .inputFields(extractInputs(expr))
                    .sourceFile(path)
                    .sourceClass(fileLabel)
                    .sourceMethod(name) // renderer appends "()"
                    .lineNumber(lineOf(code, startOffset))
                    .confidence(CalculationEvidence.Confidence.MEDIUM)
                    .tier(2)
                    .kind(CalculationEvidence.Kind.FORMULA)
                    .description("Method computation (single return)")
                    .build());
            return;
        }

        emitSliceIfCalculation(results, path, fileLabel, name, name, body, startOffset, code);
    }

    private void emitSliceIfCalculation(Map<String, List<CalculationEvidence>> results,
                                        String path, String fileLabel, String field,
                                        String sourceMethod, String body, int startOffset, String code) {
        if (!isValueCalculation(body)) return;
        add(results, field, CalculationEvidence.builder()
                .targetField(field)
                .locus(CalculationEvidence.Locus.FRONTEND_METHOD_SLICE)
                .expression(sliceBody(body))
                .inputFields(extractInputs(body))
                .sourceFile(path)
                .sourceClass(fileLabel)
                .sourceMethod(sourceMethod)
                .lineNumber(lineOf(code, startOffset))
                .confidence(CalculationEvidence.Confidence.LOW)
                .tier(3)
                .kind(CalculationEvidence.Kind.SLICE)
                .language("ts")
                .description("Multi-statement body — cited verbatim, no single formula")
                .build());
    }

    /** Trims blank lines and truncates a method/getter body for citation. */
    private String sliceBody(String body) {
        List<String> lines = new ArrayList<>();
        for (String raw : body.split("\n")) {
            String trimmed = raw.strip();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        if (lines.size() > MAX_SLICE_STATEMENTS) {
            int extra = lines.size() - MAX_SLICE_STATEMENTS;
            lines = new ArrayList<>(lines.subList(0, MAX_SLICE_STATEMENTS));
            lines.add("// … (+" + extra + " more)");
        }
        return String.join("\n", lines);
    }

    /** Finds the index of the {@code }} matching the {@code {} at {@code openIndex}, skipping
     *  string/template-literal contents so braces inside them don't unbalance the count. */
    private int findMatchingBrace(String code, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipStringLiteral(code, i);
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private int skipStringLiteral(String code, int start) {
        char quote = code.charAt(start);
        for (int i = start + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return i;
        }
        return code.length() - 1;
    }

    // ========== Value-vs-passthrough discriminator (frontend) ==========

    private static final Pattern ARITHMETIC = Pattern.compile("[+\\-*/%]|&&|\\|\\||[<>]=?|===|!==|\\?\\?|\\?");
    private static final Pattern AGG_METHOD = Pattern.compile("\\.(length|size|reduce|filter|map|count)\\b");
    private static final Pattern TEMPLATE_INTERP = Pattern.compile("\\$\\{");

    /**
     * A frontend expression is a value calculation when it contains an operator or ternary,
     * a known aggregation call, a template literal that concatenates >=2 interpolations,
     * or references two or more distinct member fields.
     *
     * <p>Optional chaining ({@code ?.}) is stripped first so it isn't confused with a ternary.
     */
    boolean isValueCalculation(String expr) {
        String e = expr.replace("?.", ".");
        if (countMatches(TEMPLATE_INTERP, e) >= 2) return true; // `${a} ${b}` concatenation
        if (ARITHMETIC.matcher(e).find()) return true;
        if (AGG_METHOD.matcher(e).find()) return true;
        return extractInputs(expr).size() >= 2;
    }

    private int countMatches(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    // ========== Helpers ==========

    private List<String> extractInputs(String expression) {
        List<String> inputs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // this.field / this.signal() references
        Matcher m = Pattern.compile("this\\.([a-zA-Z_][a-zA-Z0-9_]*)").matcher(expression);
        while (m.find()) {
            String id = m.group(1);
            if (!KEYWORDS.contains(id) && seen.add(id)) {
                inputs.add(id);
            }
        }
        return inputs;
    }

    private static final Set<String> KEYWORDS = Set.of(
            "return", "if", "else", "for", "while", "true", "false", "null", "new",
            "this", "get", "const", "let", "var", "function", "toFixed", "getTime", "floor"
    );

    private String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < code.length(); i++) {
            if (code.charAt(i) == '\n') line++;
        }
        return line;
    }

    private void add(Map<String, List<CalculationEvidence>> results, String field, CalculationEvidence ev) {
        List<CalculationEvidence> list = results.computeIfAbsent(field, k -> new ArrayList<>());
        // Dedupe identical (field, locus, expression) evidence, e.g. two valueChanges streams.
        boolean dup = list.stream().anyMatch(e ->
                e.getLocus() == ev.getLocus()
                        && java.util.Objects.equals(e.getExpression(), ev.getExpression()));
        if (!dup) {
            list.add(ev);
        }
    }

    private String readFile(String filePath) {
        try {
            return new String(Files.readAllBytes(Paths.get(filePath)));
        } catch (Exception e) {
            log.debug("Could not read component file {}: {}", filePath, e.getMessage());
            return null;
        }
    }
}
