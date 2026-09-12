package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.common.TemplateEventExtractorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts event bindings from Angular component templates and correlates them
 * with the HTTP calls they ultimately trigger, producing "Business Trigger"
 * interactions.
 *
 * <p><b>Business Trigger</b> = a template event ({@code (click)}, {@code (ngSubmit)},
 * {@code (change)}, {@code (selectionChange)}) whose handler method issues an HTTP
 * request via an injected service.
 *
 * <p>Correlation chain, resolved entirely from the component's own {@code .ts} file
 * (so it is independent of which parsing strategy produced the model):
 * <pre>
 *   (click)="deleteAccount(id)"        →  template event binding
 *   deleteAccount(id) { this.accountService.deleteAccount(id) ... }  →  method body
 *   AccountService.deleteAccount → DELETE /api/v1/accounts/{id}      →  HTTP descriptor
 * </pre>
 *
 * The caller supplies {@code httpMethodDescriptors}: a map of Angular service method
 * name → {@code "VERB /url"} (built from the extracted {@code ServiceInfo} list). A
 * handler is a business trigger when its body invokes {@code this.<field>.<method>(…)}
 * for a {@code <method>} present in that map.
 *
 * <p><b>Ignores</b> (to avoid noise): tooltips, hover effects, CSS classes, and pure
 * client-side validation that never reaches the network.
 */
public class AngularTemplateEventExtractor implements TemplateEventExtractorStrategy {

    private static final Logger log = LoggerFactory.getLogger(AngularTemplateEventExtractor.class);

    // Event bindings: (click)="method(args)" / (ngSubmit)="onSubmit()" ...
    private static final Pattern EVENT_BINDING = Pattern.compile(
            "\\((click|change|ngSubmit|selectionChange)\\)\\s*=\\s*['\"]([^'\"]+)['\"]");
    // Humanized label from nearby text: ">Button Label<"
    private static final Pattern NEARBY_TEXT = Pattern.compile(">\\s*([\\w\\s'\"#×.-]{1,60}?)\\s*</");
    // Service call inside a handler body: this.<field>.<method>(
    private static final Pattern SERVICE_CALL = Pattern.compile("this\\.\\w+\\.(\\w+)\\s*\\(");

    /**
     * Extracts business-trigger events from a component.
     *
     * @param componentFilePath     path to the {@code .ts} component file
     * @param httpMethodDescriptors service method name → {@code "VERB /url"} descriptor
     * @return distinct business-trigger events, in template order
     */
    @Override
    public List<Event> extract(String componentFilePath, Map<String, String> httpMethodDescriptors) {
        List<Event> events = new ArrayList<>();
        if (httpMethodDescriptors == null || httpMethodDescriptors.isEmpty()) return events;

        try {
            Path tsFile = Path.of(componentFilePath);
            if (!Files.isRegularFile(tsFile)) return events;
            String content = Files.readString(tsFile, StandardCharsets.UTF_8);

            String templateText = resolveTemplate(content, tsFile);
            if (templateText == null) return events;

            events.addAll(extractEventBindings(content, templateText, httpMethodDescriptors));
        } catch (IOException e) {
            log.debug("Cannot extract events from {}: {}", componentFilePath, e.getMessage());
        }
        return events;
    }

    // -----------------------------------------------------------------------
    // Event binding extraction + correlation
    // -----------------------------------------------------------------------

    private List<Event> extractEventBindings(String content, String templateText,
                                             Map<String, String> httpMethodDescriptors) {
        List<Event> events = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = EVENT_BINDING.matcher(templateText);

        while (m.find()) {
            String eventType = m.group(1);
            String handlerExpr = m.group(2);          // e.g. "deleteAccount(account.id)"
            String methodName = extractMethodName(handlerExpr);
            if (methodName.isEmpty()) continue;

            // Resolve which HTTP call (if any) this handler triggers.
            String httpDescriptor = resolveHttpTrigger(content, methodName, httpMethodDescriptors);
            if (httpDescriptor == null) continue;     // not a business trigger — ignored

            String label = findNearbyLabel(templateText, m.start());
            String description = describe(eventType, label, methodName, httpDescriptor);

            if (seen.add(description)) {
                events.add(new Event(methodName, description));
            }
        }
        return events;
    }

    /**
     * Returns the HTTP descriptor triggered by {@code handlerMethod}, or {@code null}
     * if the method's body issues no known HTTP service call. Scans only the handler's
     * own body (brace-matched) so calls belonging to other methods are not attributed.
     */
    private String resolveHttpTrigger(String content, String handlerMethod,
                                      Map<String, String> httpMethodDescriptors) {
        String body = extractMethodBody(content, handlerMethod);
        if (body == null) return null;

        Matcher call = SERVICE_CALL.matcher(body);
        while (call.find()) {
            String serviceMethod = call.group(1);
            String descriptor = httpMethodDescriptors.get(serviceMethod);
            if (descriptor != null) return descriptor;
        }
        return null;
    }

    /**
     * Extracts the brace-delimited body of {@code methodName} declared in the class,
     * or {@code null} if not found. Matches a method signature (not a call site) by
     * requiring the {@code (params) {} } shape following the name.
     */
    private String extractMethodBody(String content, String methodName) {
        Pattern sig = Pattern.compile(
                "(?<![\\w.$])" + Pattern.quote(methodName)
                + "\\s*\\([^)]*\\)\\s*(?::\\s*[\\w<>\\[\\]| ]+\\s*)?\\{");
        Matcher m = sig.matcher(content);
        if (!m.find()) return null;
        int braceOpen = content.indexOf('{', m.start());
        if (braceOpen < 0) return null;
        int braceClose = matchingBrace(content, braceOpen);
        if (braceClose < 0) return null;
        return content.substring(braceOpen + 1, braceClose);
    }

    private String extractMethodName(String handlerExpr) {
        int openParen = handlerExpr.indexOf('(');
        String name = openParen < 0 ? handlerExpr : handlerExpr.substring(0, openParen);
        return name.trim();
    }

    private String describe(String eventType, String label, String methodName, String httpDescriptor) {
        if ("ngSubmit".equals(eventType)) {
            return "Form submit → " + httpDescriptor;
        }
        String action = switch (eventType) {
            case "click" -> "Button";
            case "change" -> "Change";
            case "selectionChange" -> "Selection";
            default -> capitalize(eventType);
        };
        String subject = label.isEmpty() ? methodName : label;
        return action + " '" + subject + "' → " + httpDescriptor;
    }

    private String findNearbyLabel(String templateText, int eventPos) {
        int tagStart = templateText.lastIndexOf('<', eventPos);
        if (tagStart < 0) return "";
        int closeTag = templateText.indexOf('>', eventPos);
        if (closeTag < 0) return "";

        int searchEnd = Math.min(templateText.length(), closeTag + 200);
        Matcher m = NEARBY_TEXT.matcher(templateText);
        m.region(closeTag, searchEnd);
        if (m.find()) {
            String text = m.group(1).trim();
            return text.length() <= 60 ? text : "";
        }
        return "";
    }

    // -----------------------------------------------------------------------
    // Template resolution (inline `template` or external `templateUrl`)
    // -----------------------------------------------------------------------

    private String resolveTemplate(String content, Path tsFile) throws IOException {
        Matcher decMatcher = Pattern.compile("@Component\\s*\\(").matcher(content);
        if (!decMatcher.find()) return null;

        int argStart = decMatcher.end() - 1;
        int argEnd = matchingParen(content, argStart);
        if (argEnd < 0) return null;
        String args = content.substring(argStart + 1, argEnd);

        Matcher urlMatcher = Pattern.compile("templateUrl\\s*:\\s*['\"`]([^'\"`]+)['\"`]").matcher(args);
        if (urlMatcher.find()) {
            Path htmlFile = tsFile.getParent().resolve(urlMatcher.group(1)).normalize();
            return Files.isRegularFile(htmlFile) ? Files.readString(htmlFile, StandardCharsets.UTF_8) : null;
        }
        return extractInlineTemplate(args);
    }

    private String extractInlineTemplate(String decoratorArgs) {
        Matcher m = Pattern.compile("\\btemplate\\b\\s*:").matcher(decoratorArgs);
        if (!m.find()) return null;

        int i = m.end();
        while (i < decoratorArgs.length() && Character.isWhitespace(decoratorArgs.charAt(i))) i++;
        if (i >= decoratorArgs.length()) return null;

        char quote = decoratorArgs.charAt(i);
        if (quote != '`' && quote != '\'' && quote != '"') return null;

        int end = findClosingQuote(decoratorArgs, i, quote);
        return end >= 0 ? decoratorArgs.substring(i + 1, end) : null;
    }

    // -----------------------------------------------------------------------
    // Character-level scanning helpers (string-aware)
    // -----------------------------------------------------------------------

    private int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') { i = skipString(s, i); continue; }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') { i = skipString(s, i); continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private int skipString(String s, int start) {
        char quote = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return i;
        }
        return s.length() - 1;
    }

    private int findClosingQuote(String s, int start, char quote) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return i;
        }
        return -1;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
