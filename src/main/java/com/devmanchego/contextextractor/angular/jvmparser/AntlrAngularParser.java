package com.devmanchego.contextextractor.angular.jvmparser;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.antlr.v4.runtime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * JVM-native Angular/TypeScript parser (Strategy B).
 *
 * Uses the ANTLR lexer to tokenize TypeScript files, then applies a
 * state-machine pattern scanner on the CommonTokenStream to extract:
 * - @Component decorator (className, selector, injected services)
 * - @Injectable service methods with this.http.* calls
 * - TypeScript interfaces and classes (model field declarations)
 *
 * This "island grammar" approach ignores syntactic constructs it does not
 * recognize instead of failing, at the cost of lower precision on
 * complex generics or unusual patterns.
 */
public final class AntlrAngularParser {

    private static final Logger log = LoggerFactory.getLogger(AntlrAngularParser.class);

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete", "patch");

    private final Path projectRoot;

    public AntlrAngularParser(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public AngularProject parse() throws IOException {
        List<ComponentInfo> components = new ArrayList<>();
        List<ServiceInfo> services = new ArrayList<>();
        List<TsModelInfo> models = new ArrayList<>();

        List<Path> tsFiles = collectTypeScriptFiles(projectRoot);
        log.info("Strategy B (ANTLR JVM parser): scanning {} TypeScript files.", tsFiles.size());

        for (Path file : tsFiles) {
            try {
                List<Token> tokens = tokenize(file);
                FileResult result = scanTokens(tokens, file.toAbsolutePath().toString());
                components.addAll(result.components());
                services.addAll(result.services());
                models.addAll(result.models());
            } catch (Exception e) {
                log.warn("Could not parse '{}': {} ({}) — skipping.",
                         file, e.getMessage(), e.getClass().getSimpleName());
                log.debug("Stack trace for {}:", file, e);
            }
        }

        return new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                components, services, models);
    }

    // -----------------------------------------------------------------------
    // Tokenization
    // -----------------------------------------------------------------------

    private List<Token> tokenize(Path file) throws IOException {
        CharStream input = CharStreams.fromPath(file, StandardCharsets.UTF_8);
        TypeScriptLexer lexer = new TypeScriptLexer(input);
        lexer.removeErrorListeners();
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        List<Token> tokens = stream.getTokens();
        return tokens != null ? tokens : List.of();
    }

    // -----------------------------------------------------------------------
    // Token stream scanner
    // -----------------------------------------------------------------------

    private FileResult scanTokens(List<Token> tokens, String filePath) {
        List<ComponentInfo> components = new ArrayList<>();
        List<ServiceInfo> services = new ArrayList<>();
        List<TsModelInfo> models = new ArrayList<>();

        int i = 0;
        while (i < tokens.size()) {
            Token t = tokens.get(i);

            if (t.getType() == TypeScriptLexer.AT) {
                // Look for @Component or @Injectable
                Token next = peek(tokens, i + 1);
                if (next != null && "Component".equals(next.getText())) {
                    int[] end = {i};
                    extractComponent(tokens, i, filePath).ifPresent(components::add);
                } else if (next != null && "Injectable".equals(next.getText())) {
                    // mark the following class as a service candidate
                }
                i++;
                continue;
            }

            if (t.getType() == TypeScriptLexer.CLASS) {
                Token name = peek(tokens, i + 1);
                if (name != null && name.getType() == TypeScriptLexer.ID) {
                    int bodyStart = findNext(tokens, i, TypeScriptLexer.LBRACE);
                    if (bodyStart >= 0) {
                        int bodyEnd = findMatchingBrace(tokens, bodyStart);
                        List<Token> bodyTokens = tokens.subList(bodyStart + 1, bodyEnd);

                        // Check if this class was preceded by @Injectable (service)
                        boolean isInjectable = lookBackForDecorator(tokens, i, "Injectable");
                        boolean isComponent  = lookBackForDecorator(tokens, i, "Component");

                        if (!isComponent && !isInjectable) {
                            // Model class
                            extractModelClass(name.getText(), bodyTokens, filePath, false)
                                    .ifPresent(models::add);
                        } else if (isInjectable) {
                            extractService(name.getText(), bodyTokens, filePath)
                                    .ifPresent(services::add);
                        }
                        i = bodyEnd + 1;
                        continue;
                    }
                }
            }

            if (t.getType() == TypeScriptLexer.INTERFACE) {
                Token name = peek(tokens, i + 1);
                if (name != null && name.getType() == TypeScriptLexer.ID) {
                    int bodyStart = findNext(tokens, i, TypeScriptLexer.LBRACE);
                    if (bodyStart >= 0) {
                        int bodyEnd = findMatchingBrace(tokens, bodyStart);
                        List<Token> bodyTokens = tokens.subList(bodyStart + 1, bodyEnd);
                        extractModelClass(name.getText(), bodyTokens, filePath, true)
                                .ifPresent(models::add);
                        i = bodyEnd + 1;
                        continue;
                    }
                }
            }

            i++;
        }

        return new FileResult(components, services, models);
    }

    // -----------------------------------------------------------------------
    // @Component extraction
    // -----------------------------------------------------------------------

    private Optional<ComponentInfo> extractComponent(List<Token> tokens, int atIndex, String filePath) {
        // @Component({ selector: '...', ... })
        int argStart = findNext(tokens, atIndex, TypeScriptLexer.LPAREN);
        if (argStart < 0) return Optional.empty();
        int argEnd = findMatchingParen(tokens, argStart);
        List<Token> args = tokens.subList(argStart + 1, argEnd);

        String selector = extractStringProp(args, "selector").orElse("");

        // Find the class that follows
        int classIndex = findTokenType(tokens, argEnd, TypeScriptLexer.CLASS);
        if (classIndex < 0) return Optional.empty();

        Token className = peek(tokens, classIndex + 1);
        if (className == null) return Optional.empty();

        // Find constructor to detect injected services
        int bodyStart = findNext(tokens, classIndex, TypeScriptLexer.LBRACE);
        if (bodyStart < 0) return Optional.empty();
        int bodyEnd = findMatchingBrace(tokens, bodyStart);
        List<Token> body = tokens.subList(bodyStart + 1, bodyEnd);
        List<String> injected = extractInjectedServices(body);

        return Optional.of(new ComponentInfo(className.getText(), filePath, selector, injected));
    }

    // -----------------------------------------------------------------------
    // Service extraction
    // -----------------------------------------------------------------------

    private Optional<ServiceInfo> extractService(String className, List<Token> body, String filePath) {
        List<HttpCallInfo> httpCalls = new ArrayList<>();

        // Step 1: collect class-level string constant fields for URL resolution.
        // Handles: private readonly base = '/api/v1';
        Map<String, String> constants = collectStringConstants(body);
        if (!constants.isEmpty()) {
            log.debug("Resolved {} string constant(s) in {}: {}", constants.size(), className, constants);
        }

        // Detect the injected HttpClient field name from the constructor.
        // Standard Angular uses `private http: HttpClient` but many projects use
        // `private httpClient: HttpClient`, `private client: HttpClient`, etc.
        Set<String> httpFieldNames = findHttpClientFieldNames(body);
        if (httpFieldNames.isEmpty()) httpFieldNames = Set.of("http"); // safe fallback
        log.debug("HttpClient field name(s) in {}: {}", className, httpFieldNames);

        // Find all this.<httpField>.{method}<T>(url, ...) patterns
        // Token layout: THIS(i) DOT(i+1) <httpField>(i+2) DOT(i+3) get/post/…(i+4)
        for (int i = 0; i < body.size() - 5; i++) {
            Token t = body.get(i);
            if (t.getType() != TypeScriptLexer.THIS) continue;
            // body[i+1] must be DOT, body[i+2] must be the HttpClient field name
            if (i + 2 >= body.size()) continue;
            if (body.get(i + 1).getType() != TypeScriptLexer.DOT) continue;
            if (body.get(i + 2).getType() != TypeScriptLexer.ID) continue;
            if (!httpFieldNames.contains(body.get(i + 2).getText())) continue;
            // body[i+3] must be DOT, body[i+4] must be the HTTP verb name
            if (i + 4 >= body.size()) continue;
            if (body.get(i + 3).getType() != TypeScriptLexer.DOT) continue;
            Token methodToken = body.get(i + 4);
            if (methodToken.getType() != TypeScriptLexer.ID) continue;
            String method = methodToken.getText().toLowerCase();
            if (!HTTP_METHODS.contains(method)) continue;

            HttpVerb verb = HttpVerb.valueOf(method.toUpperCase());

            // Generic type: this.http.get<Type>(
            int afterMethod = i + 5;
            String responseType = "any";
            if (afterMethod < body.size() && body.get(afterMethod).getType() == TypeScriptLexer.LANGLE) {
                int angleEnd = findMatchingAngle(body, afterMethod);
                if (angleEnd > afterMethod) {
                    responseType = extractTypeText(body.subList(afterMethod + 1, angleEnd));
                    afterMethod = angleEnd + 1;
                }
            }

            // Arguments: (url, body?, options?)
            if (afterMethod >= body.size() || body.get(afterMethod).getType() != TypeScriptLexer.LPAREN) continue;
            int argEnd = findMatchingParen(body, afterMethod);
            List<Token> callArgs = body.subList(afterMethod + 1, argEnd);

            String urlTemplate = extractUrlFromArgs(callArgs, constants);
            String bodyType = (verb == HttpVerb.POST || verb == HttpVerb.PUT || verb == HttpVerb.PATCH)
                    ? extractBodyTypeFromArgs(callArgs) : null;

            // Find the enclosing method name by looking backwards
            String enclosingMethod = findEnclosingMethodName(body, i);

            httpCalls.add(new HttpCallInfo(enclosingMethod, verb, urlTemplate, responseType, bodyType));
        }

        // Step 2: URL-builder methods — methods that return a URL string (not via HttpClient).
        // Example:  exportPdfUrl(id: string): string { return `${this.base}/export/pdf/${id}`; }
        // These are treated as implicit GET calls (browser navigation / href / window.open).
        httpCalls.addAll(extractUrlBuilderCalls(body, constants));

        if (httpCalls.isEmpty()) return Optional.empty();
        return Optional.of(new ServiceInfo(className, filePath, httpCalls));
    }

    /**
     * Scans for `return <url-template>` patterns in the class body.
     * Any method that directly returns a URL-like string (starts with '/') is treated
     * as an implicit GET call — typically used for href/download links or window.open().
     */
    private List<HttpCallInfo> extractUrlBuilderCalls(List<Token> body, Map<String, String> constants) {
        List<HttpCallInfo> result = new ArrayList<>();

        for (int i = 0; i < body.size() - 1; i++) {
            if (body.get(i).getType() != TypeScriptLexer.RETURN) continue;

            int j = i + 1;
            if (j >= body.size()) continue;
            Token next = body.get(j);

            String url = null;

            if (next.getType() == TypeScriptLexer.TEMPLATE_STRING) {
                url = normalizeTemplateLiteralUrl(next.getText(), constants);
            } else if (next.getType() == TypeScriptLexer.STRING_SQ
                    || next.getType() == TypeScriptLexer.STRING_DQ) {
                url = next.getText().substring(1, next.getText().length() - 1);
            } else if (next.getType() == TypeScriptLexer.THIS) {
                // Possible: return this.field + '/path'
                List<Token> urlTokens = new ArrayList<>();
                while (j < body.size() && body.get(j).getType() != TypeScriptLexer.SEMICOLON) {
                    urlTokens.add(body.get(j));
                    j++;
                }
                url = resolveStringConcatenation(urlTokens, constants);
            }

            if (url != null && url.startsWith("/")) {
                String methodName = findEnclosingMethodName(body, i);
                // Only add if not already captured as an HttpClient call
                String finalUrl = url;
                boolean alreadyCaptured = result.stream().anyMatch(c -> c.getUrlTemplate().equals(finalUrl));
                if (!alreadyCaptured) {
                    result.add(new HttpCallInfo(methodName, HttpVerb.GET, url, "string", null));
                }
            }
        }
        return result;
    }

    /**
     * Scans constructor parameters to find the names of fields whose declared type
     * is {@code HttpClient} (or ends with {@code HttpClient}).
     *
     * Handles all naming conventions:
     *   constructor(private http: HttpClient)         → "http"
     *   constructor(private httpClient: HttpClient)   → "httpClient"
     *   constructor(private client: HttpClient)       → "client"
     */
    private Set<String> findHttpClientFieldNames(List<Token> body) {
        Set<String> result = new LinkedHashSet<>();
        int constructorIdx = -1;
        for (int i = 0; i < body.size(); i++) {
            if (body.get(i).getType() == TypeScriptLexer.CONSTRUCTOR) {
                constructorIdx = i;
                break;
            }
        }
        if (constructorIdx < 0) return result;

        int parenStart = findNext(body, constructorIdx, TypeScriptLexer.LPAREN);
        if (parenStart < 0) return result;
        int parenEnd = findMatchingParen(body, parenStart);
        List<Token> params = body.subList(parenStart + 1, parenEnd);

        Set<Integer> modifierTypes = Set.of(
                TypeScriptLexer.PRIVATE, TypeScriptLexer.PUBLIC,
                TypeScriptLexer.PROTECTED, TypeScriptLexer.READONLY);

        // Each param: [modifier*] paramName [?] : TypeName [,]
        for (int i = 0; i < params.size(); i++) {
            // Skip modifiers
            if (modifierTypes.contains(params.get(i).getType())) continue;
            if (params.get(i).getType() != TypeScriptLexer.ID) continue;
            String paramName = params.get(i).getText();
            int j = i + 1;
            // Optional question mark
            if (j < params.size() && params.get(j).getType() == TypeScriptLexer.QUESTION) j++;
            // Colon
            if (j >= params.size() || params.get(j).getType() != TypeScriptLexer.COLON) continue;
            j++;
            if (j >= params.size() || params.get(j).getType() != TypeScriptLexer.ID) continue;
            String typeName = params.get(j).getText();
            if ("HttpClient".equals(typeName) || typeName.endsWith("HttpClient")) {
                result.add(paramName);
            }
        }
        return result;
    }

    /**
     * Scans class body tokens for simple string-literal field declarations and returns
     * a map of {fieldName → stringValue}.
     *
     * Recognises these patterns (with optional access modifiers / readonly):
     *   private readonly base = '/api/v1';
     *   protected apiUrl = "/api/v1";
     *   base = '/api/v1';
     */
    private Map<String, String> collectStringConstants(List<Token> body) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<Integer> modifierTypes = Set.of(
                TypeScriptLexer.PRIVATE, TypeScriptLexer.PUBLIC, TypeScriptLexer.PROTECTED,
                TypeScriptLexer.READONLY);

        for (int i = 0; i < body.size() - 2; i++) {
            // Skip access modifiers / readonly
            int j = i;
            while (j < body.size() && modifierTypes.contains(body.get(j).getType())) j++;
            if (j >= body.size()) break;

            // Expect: ID EQUALS STRING_LITERAL
            if (body.get(j).getType() != TypeScriptLexer.ID) continue;
            String fieldName = body.get(j).getText();

            int k = j + 1;
            // Optional type annotation  `: string`  — skip past the colon and type name
            if (k < body.size() && body.get(k).getType() == TypeScriptLexer.COLON) {
                k++; // skip ':'
                while (k < body.size()
                        && body.get(k).getType() != TypeScriptLexer.EQUALS
                        && body.get(k).getType() != TypeScriptLexer.SEMICOLON) {
                    k++;
                }
            }

            if (k >= body.size() || body.get(k).getType() != TypeScriptLexer.EQUALS) continue;
            k++; // skip '='
            if (k >= body.size()) continue;

            Token valToken = body.get(k);
            if (valToken.getType() == TypeScriptLexer.STRING_SQ
                    || valToken.getType() == TypeScriptLexer.STRING_DQ) {
                String raw = valToken.getText();
                result.put(fieldName, raw.substring(1, raw.length() - 1));
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Interface/Class model extraction
    // -----------------------------------------------------------------------

    private Optional<TsModelInfo> extractModelClass(String name, List<Token> body,
                                                     String filePath, boolean isInterface) {
        List<TsFieldInfo> fields = new ArrayList<>();

        for (int i = 0; i < body.size() - 2; i++) {
            Token t = body.get(i);
            if (t.getType() != TypeScriptLexer.ID) continue;

            // Skip access modifiers
            String text = t.getText();
            if ("private".equals(text) || "public".equals(text) || "protected".equals(text)
                    || "readonly".equals(text) || "static".equals(text)) continue;

            // Pattern: ID [?] : TypeAnnotation [; or ,]
            int next = i + 1;
            boolean optional = false;
            if (next < body.size() && body.get(next).getType() == TypeScriptLexer.QUESTION) {
                optional = true;
                next++;
            }
            if (next >= body.size() || body.get(next).getType() != TypeScriptLexer.COLON) continue;
            next++;
            if (next >= body.size()) continue;

            // Collect type tokens until ; , } or =
            StringBuilder typeBuilder = new StringBuilder();
            int depth = 0;
            while (next < body.size()) {
                Token tt = body.get(next);
                if (depth == 0 && (tt.getType() == TypeScriptLexer.SEMICOLON
                        || tt.getType() == TypeScriptLexer.COMMA
                        || tt.getType() == TypeScriptLexer.EQUALS)) break;
                if (tt.getType() == TypeScriptLexer.LANGLE || tt.getType() == TypeScriptLexer.LBRACE
                        || tt.getType() == TypeScriptLexer.LPAREN) depth++;
                if (tt.getType() == TypeScriptLexer.RANGLE || tt.getType() == TypeScriptLexer.RBRACE
                        || tt.getType() == TypeScriptLexer.RPAREN) depth--;
                typeBuilder.append(tt.getText());
                next++;
            }

            String type = typeBuilder.toString().trim();
            if (!type.isEmpty() && !text.equals("constructor")) {
                fields.add(new TsFieldInfo(text, type, optional));
            }
            i = next;
        }

        if (fields.isEmpty()) return Optional.empty();
        TsModelInfo.Kind kind = isInterface ? TsModelInfo.Kind.INTERFACE : TsModelInfo.Kind.CLASS;
        return Optional.of(new TsModelInfo(name, filePath, kind, fields));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<String> extractInjectedServices(List<Token> body) {
        // constructor( [modifier] param: ServiceType , ... )
        List<String> result = new ArrayList<>();
        int constructorIdx = -1;
        for (int i = 0; i < body.size(); i++) {
            if (body.get(i).getType() == TypeScriptLexer.CONSTRUCTOR) {
                constructorIdx = i;
                break;
            }
        }
        if (constructorIdx < 0) return result;
        int parenStart = findNext(body, constructorIdx, TypeScriptLexer.LPAREN);
        if (parenStart < 0) return result;
        int parenEnd = findMatchingParen(body, parenStart);
        List<Token> params = body.subList(parenStart + 1, parenEnd);

        // Each param: [private|public|protected] [readonly] name: Type
        for (int i = 0; i < params.size(); i++) {
            Token t = params.get(i);
            // Skip modifiers
            if (t.getType() == TypeScriptLexer.PRIVATE || t.getType() == TypeScriptLexer.PUBLIC
                    || t.getType() == TypeScriptLexer.PROTECTED || t.getType() == TypeScriptLexer.READONLY) continue;
            if (t.getType() != TypeScriptLexer.ID) continue;
            // Look for : TypeName
            int j = i + 1;
            if (j < params.size() && params.get(j).getType() == TypeScriptLexer.QUESTION) j++;
            if (j < params.size() && params.get(j).getType() == TypeScriptLexer.COLON) {
                j++;
                if (j < params.size() && params.get(j).getType() == TypeScriptLexer.ID) {
                    result.add(params.get(j).getText());
                }
            }
        }
        return result;
    }

    private Optional<String> extractStringProp(List<Token> tokens, String propName) {
        for (int i = 0; i < tokens.size() - 2; i++) {
            if (tokens.get(i).getType() == TypeScriptLexer.ID
                    && propName.equals(tokens.get(i).getText())
                    && tokens.get(i + 1).getType() == TypeScriptLexer.COLON) {
                Token val = tokens.get(i + 2);
                if (val.getType() == TypeScriptLexer.STRING_SQ || val.getType() == TypeScriptLexer.STRING_DQ) {
                    String raw = val.getText();
                    return Optional.of(raw.substring(1, raw.length() - 1));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts and normalises the URL from HTTP-call argument tokens.
     *
     * Handles:
     *  - Plain string literals: 'api/v1/items'
     *  - Template literals:     `${this.base}/items/${id}`   → resolved via constants
     *  - String concatenation:  this.base + '/items/' + id   → resolved via constants
     */
    private String extractUrlFromArgs(List<Token> args, Map<String, String> constants) {
        if (args.isEmpty()) return "/unknown";
        Token first = args.get(0);
        if (first.getType() == TypeScriptLexer.STRING_DQ || first.getType() == TypeScriptLexer.STRING_SQ) {
            return first.getText().substring(1, first.getText().length() - 1);
        }
        if (first.getType() == TypeScriptLexer.TEMPLATE_STRING) {
            return normalizeTemplateLiteralUrl(first.getText(), constants);
        }
        // Possible string-concatenation: this.base + '/path' + ...
        // Collect tokens up to the first comma (separates URL from body argument)
        List<Token> urlTokens = new ArrayList<>();
        for (Token t : args) {
            if (t.getType() == TypeScriptLexer.COMMA) break;
            urlTokens.add(t);
        }
        if (!urlTokens.isEmpty()) {
            String concatenated = resolveStringConcatenation(urlTokens, constants);
            if (concatenated != null) return concatenated;
        }
        return "/" + first.getText() + " (dynamic)";
    }

    /**
     * Resolves a token sequence that may be a string concatenation expression.
     * Example: this.base + '/items/' + id  →  /api/v1/items/{id}
     * Returns null if the tokens don't look like a string concatenation.
     */
    private String resolveStringConcatenation(List<Token> tokens, Map<String, String> constants) {
        StringBuilder sb = new StringBuilder();
        boolean hasAtLeastOneStringPart = false;

        int i = 0;
        while (i < tokens.size()) {
            Token t = tokens.get(i);

            // Skip '+' operators ('+' is lexed as OTHER since we have no PLUS token)
            if (t.getType() == TypeScriptLexer.OTHER && "+".equals(t.getText())) {
                i++;
                continue;
            }

            // String literal segment
            if (t.getType() == TypeScriptLexer.STRING_SQ || t.getType() == TypeScriptLexer.STRING_DQ) {
                sb.append(t.getText(), 1, t.getText().length() - 1);
                hasAtLeastOneStringPart = true;
                i++;
                continue;
            }

            // Template literal segment
            if (t.getType() == TypeScriptLexer.TEMPLATE_STRING) {
                sb.append(normalizeTemplateLiteralUrl(t.getText(), constants));
                hasAtLeastOneStringPart = true;
                i++;
                continue;
            }

            // this.fieldName  →  look up in constants
            if (t.getType() == TypeScriptLexer.THIS
                    && i + 2 < tokens.size()
                    && tokens.get(i + 1).getType() == TypeScriptLexer.DOT
                    && tokens.get(i + 2).getType() == TypeScriptLexer.ID) {
                String fieldName = tokens.get(i + 2).getText();
                String resolved = constants.get(fieldName);
                if (resolved != null) {
                    sb.append(resolved);
                    hasAtLeastOneStringPart = true;
                } else {
                    sb.append("{").append(fieldName).append("}");
                }
                i += 3;
                continue;
            }

            // Standalone variable  →  treat as path placeholder
            if (t.getType() == TypeScriptLexer.ID) {
                sb.append("{").append(t.getText()).append("}");
                i++;
                continue;
            }

            i++;
        }

        return hasAtLeastOneStringPart ? sb.toString() : null;
    }

    /**
     * Converts a template literal to a normalised URL path.
     * Resolves ${this.fieldName} via the constants map and converts
     * remaining ${expr} to {expr} path placeholders.
     */
    private String normalizeTemplateLiteralUrl(String raw, Map<String, String> constants) {
        String content = raw.substring(1, raw.length() - 1); // strip backticks

        // Replace ${this.fieldName} with resolved constant or {fieldName}
        Pattern thisField = Pattern.compile("\\$\\{this\\.([^}]+)}");
        Matcher m = thisField.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String fieldName = m.group(1);
            String resolved = constants.get(fieldName);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    resolved != null ? resolved : "{" + fieldName + "}"));
        }
        m.appendTail(sb);

        // Replace remaining ${expr} → {expr}
        return sb.toString().replaceAll("\\$\\{([^}]+)}", "{$1}");
    }

    private String extractBodyTypeFromArgs(List<Token> args) {
        // Second argument (after the URL) is the body
        int commaCount = 0;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).getType() == TypeScriptLexer.COMMA) {
                commaCount++;
                if (commaCount == 1 && i + 1 < args.size()) {
                    return args.get(i + 1).getText();
                }
            }
        }
        return null;
    }

    private String findEnclosingMethodName(List<Token> tokens, int pos) {
        // Scan backwards for a method DECLARATION pattern: ID LPAREN
        // A declaration has the ID NOT preceded by a DOT (which would make it a call: obj.method()).
        for (int i = pos - 1; i >= 1; i--) {
            if (tokens.get(i).getType() == TypeScriptLexer.LPAREN
                    && tokens.get(i - 1).getType() == TypeScriptLexer.ID) {
                // Skip method calls  <expr>.ID(  and constructor calls  new ID(
                if (i >= 2) {
                    int prevType = tokens.get(i - 2).getType();
                    if (prevType == TypeScriptLexer.DOT || prevType == TypeScriptLexer.NEW) continue;
                }
                String candidate = tokens.get(i - 1).getText();
                if (!HTTP_METHODS.contains(candidate) && !"http".equals(candidate)) {
                    return candidate;
                }
            }
        }
        return "unknown";
    }

    private String extractTypeText(List<Token> typeTokens) {
        return typeTokens.stream().map(Token::getText).collect(Collectors.joining());
    }

    private boolean dotIdMatch(List<Token> tokens, int dotIndex, String name) {
        if (dotIndex >= tokens.size() - 1) return false;
        return tokens.get(dotIndex).getType() == TypeScriptLexer.DOT
                && tokens.get(dotIndex + 1).getType() == TypeScriptLexer.ID
                && name.equals(tokens.get(dotIndex + 1).getText());
    }

    private boolean lookBackForDecorator(List<Token> tokens, int classIndex, String decoratorName) {
        // Look backwards for @DecoratorName within a reasonable window
        for (int i = classIndex - 1; i >= 0 && i >= classIndex - 50; i--) {
            if (tokens.get(i).getType() == TypeScriptLexer.AT) {
                Token next = peek(tokens, i + 1);
                if (next != null && decoratorName.equals(next.getText())) return true;
            }
        }
        return false;
    }

    private Token peek(List<Token> tokens, int index) {
        return (index < tokens.size()) ? tokens.get(index) : null;
    }

    private int findNext(List<Token> tokens, int from, int tokenType) {
        for (int i = from + 1; i < tokens.size(); i++) {
            if (tokens.get(i).getType() == tokenType) return i;
            // Stop at class/interface boundaries to avoid false positives
            if (i > from + 200) break;
        }
        return -1;
    }

    private int findTokenType(List<Token> tokens, int from, int tokenType) {
        for (int i = from + 1; i < tokens.size() && i < from + 10; i++) {
            if (tokens.get(i).getType() == tokenType) return i;
        }
        return -1;
    }

    private int findMatchingBrace(List<Token> tokens, int openIndex) {
        return findMatchingClose(tokens, openIndex,
                TypeScriptLexer.LBRACE, TypeScriptLexer.RBRACE);
    }

    private int findMatchingParen(List<Token> tokens, int openIndex) {
        return findMatchingClose(tokens, openIndex,
                TypeScriptLexer.LPAREN, TypeScriptLexer.RPAREN);
    }

    private int findMatchingAngle(List<Token> tokens, int openIndex) {
        return findMatchingClose(tokens, openIndex,
                TypeScriptLexer.LANGLE, TypeScriptLexer.RANGLE);
    }

    private int findMatchingClose(List<Token> tokens, int openIndex, int openType, int closeType) {
        int depth = 1;
        for (int i = openIndex + 1; i < tokens.size(); i++) {
            int type = tokens.get(i).getType();
            if (type == openType) depth++;
            else if (type == closeType) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return tokens.size() - 1;
    }

    // -----------------------------------------------------------------------
    // File system
    // -----------------------------------------------------------------------

    private List<Path> collectTypeScriptFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".ts"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().endsWith(".spec.ts"))
                    .filter(p -> !p.toString().endsWith(".d.ts"))
                    .collect(Collectors.toList());
        }
    }

    private record FileResult(List<ComponentInfo> components,
                               List<ServiceInfo> services,
                               List<TsModelInfo> models) {}
}
