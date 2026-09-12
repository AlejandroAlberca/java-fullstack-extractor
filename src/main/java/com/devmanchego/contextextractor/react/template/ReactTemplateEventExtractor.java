package com.devmanchego.contextextractor.react.template;

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
 * Extracts event bindings from React components (JSX/TSX) and correlates them
 * with HTTP calls, producing "Business Trigger" interactions.
 *
 * <p><b>Business Trigger</b> = a JSX event handler (onClick, onSubmit, onChange)
 * whose implementation issues an HTTP request via fetch/axios/api client.
 *
 * <p>Returns list of events like: "Button 'Delete' → DELETE /items/{id}"
 */
public final class ReactTemplateEventExtractor implements TemplateEventExtractorStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReactTemplateEventExtractor.class);

    // Match JSX event handlers: onClick={...}, onSubmit={...}, onChange={...}, etc.
    private static final Pattern EVENT_HANDLER = Pattern.compile(
            "on(Click|Submit|Change|Blur|Focus|KeyDown|KeyUp)\\s*=\\s*\\{\\s*([^}]+)\\s*\\}");
    // Extract label from nearby text: >Delete</button>, Save</button>, etc.
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            ">\\s*([A-Za-z\\s]{1,50}?)\\s*</");
    // Match HTTP calls: api.delete(...), fetch(...), axios.post(...), await fetch(...), etc.
    private static final Pattern HTTP_CALL = Pattern.compile(
            "(?:await\\s+)?(?:this\\.)?\\w+\\.(\\w+)\\s*\\(|(?:await\\s+)?(fetch)\\s*\\(|(?:await\\s+)?axios\\.(\\w+)\\s*\\(");

    /**
     * Extracts business-trigger events from a React component.
     *
     * @param componentFilePath path to the .tsx/.jsx component file
     * @param httpMethodDescriptors map of method name → "VERB /url" descriptor
     * @return list of business trigger events
     */
    @Override
    public List<Event> extract(String componentFilePath, Map<String, String> httpMethodDescriptors) {
        List<Event> events = new ArrayList<>();
        if (httpMethodDescriptors == null || httpMethodDescriptors.isEmpty()) return events;

        try {
            Path file = Path.of(componentFilePath);
            if (!Files.isRegularFile(file)) return events;
            String content = Files.readString(file, StandardCharsets.UTF_8);

            events.addAll(extractEventHandlers(content, httpMethodDescriptors));
        } catch (IOException e) {
            log.debug("Cannot extract events from {}: {}", componentFilePath, e.getMessage());
        }
        return events;
    }

    private List<Event> extractEventHandlers(String content, Map<String, String> httpMethodDescriptors) {
        List<Event> events = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = EVENT_HANDLER.matcher(content);

        while (m.find()) {
            String eventType = m.group(1);           // "Click", "Submit", etc.
            String handlerExpr = m.group(2).trim();  // e.g., "deleteItem" or "() => deleteItem(id)"

            String handlerName = extractHandlerName(handlerExpr);
            if (handlerName.isEmpty()) continue;

            String httpDescriptor = resolveHttpCall(content, handlerName, httpMethodDescriptors);
            if (httpDescriptor == null) continue;

            String label = extractLabel(content, m.start());
            String description = describe(eventType, label, handlerName, httpDescriptor);

            // Deduplicate by handler + HTTP method
            String dedupeKey = handlerName + "::" + httpDescriptor;
            if (seen.add(dedupeKey)) {
                events.add(new Event(handlerName, description));
            }
        }
        return events;
    }

    private String extractHandlerName(String handlerExpr) {
        // Direct reference: onClick={deleteItem}
        if (!handlerExpr.contains("=>") && !handlerExpr.contains("(")) {
            return handlerExpr;
        }

        // Arrow function: onClick={() => deleteItem(id)}
        Pattern arrow = Pattern.compile("=>\\s*(\\w+)\\s*\\(");
        Matcher m = arrow.matcher(handlerExpr);
        if (m.find()) return m.group(1);

        // Fallback: extract first identifier
        Pattern ident = Pattern.compile("\\b([a-zA-Z_]\\w*)\\b");
        Matcher im = ident.matcher(handlerExpr);
        if (im.find()) return im.group(1);

        return "";
    }

    private String resolveHttpCall(String content, String handlerName, Map<String, String> httpMethodDescriptors) {
        // Find function definition: const name = (...) => { ... } or async name(...) { ... }
        Pattern defPattern = Pattern.compile(
                "(?:const|let|var|async)?\\s+" + Pattern.quote(handlerName)
                + "\\s*(?:=|:)\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*=>?\\s*\\{");

        Matcher defMatcher = defPattern.matcher(content);
        if (!defMatcher.find()) return null;

        int braceStart = content.lastIndexOf('{', defMatcher.end());
        if (braceStart < 0) return null;

        int braceEnd = findMatchingBrace(content, braceStart);
        if (braceEnd < 0) return null;

        String body = content.substring(braceStart + 1, braceEnd);

        // Check if body contains an HTTP call
        Matcher callMatcher = HTTP_CALL.matcher(body);
        while (callMatcher.find()) {
            // Group 1: api.method → "method"
            // Group 2: fetch → "fetch"
            // Group 3: axios.method → "method"
            String methodName = callMatcher.group(1);
            if (methodName == null) methodName = callMatcher.group(2);
            if (methodName == null) methodName = callMatcher.group(3);

            if (methodName != null) {
                String descriptor = httpMethodDescriptors.get(methodName);
                if (descriptor != null) return descriptor;
            }
        }

        return null;
    }

    private String extractLabel(String content, int eventPos) {
        int tagStart = content.lastIndexOf('<', eventPos);
        if (tagStart < 0) return "";

        int closeTag = content.indexOf('>', eventPos);
        if (closeTag < 0) return "";

        int searchEnd = Math.min(content.length(), closeTag + 150);
        Matcher m = LABEL_PATTERN.matcher(content);
        m.region(closeTag, searchEnd);

        if (m.find()) {
            String text = m.group(1).trim();
            return text.length() <= 50 ? text : "";
        }
        return "";
    }

    private String describe(String eventType, String label, String methodName, String httpDescriptor) {
        if ("Submit".equals(eventType)) {
            return "Form submit → " + httpDescriptor;
        }

        String action = switch (eventType) {
            case "Click" -> "Button";
            case "Change" -> "Change";
            case "Blur" -> "Blur";
            case "Focus" -> "Focus";
            default -> capitalize(eventType);
        };

        String subject = label.isEmpty() ? methodName : label;
        return action + " '" + subject + "' → " + httpDescriptor;
    }

    private int findMatchingBrace(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(s, i);
                continue;
            }
            if (c == '{' || c == '(') depth++;
            else if ((c == '}' || c == ')') && --depth == 0) return i;
        }
        return -1;
    }

    private int skipString(String s, int start) {
        char quote = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') i++;
            else if (s.charAt(i) == quote) return i;
        }
        return s.length() - 1;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
