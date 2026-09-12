package com.devmanchego.contextextractor.vue.template;

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
 * Extracts event bindings from Vue components (.vue files) and correlates them
 * with HTTP calls, producing "Business Trigger" interactions.
 *
 * <p><b>Business Trigger</b> = a Vue template event handler ({@code @click}, {@code @submit},
 * {@code @change}) whose implementation issues an HTTP request via fetch/axios.
 *
 * <p>Returns list of events like: "Button 'Delete' → DELETE /items/{id}"
 */
public final class VueTemplateEventExtractor implements TemplateEventExtractorStrategy {

    private static final Logger log = LoggerFactory.getLogger(VueTemplateEventExtractor.class);

    // Vue event handlers: @click="handler", @submit="handleSubmit", @change="handleChange", etc.
    private static final Pattern VUE_EVENT = Pattern.compile(
            "@(click|submit|change|blur|focus|keydown|keyup)\\s*=\\s*['\"]([^'\"]+)['\"]");
    // Extract label from nearby text: >Delete</button>, Save</button>, etc.
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            ">\\s*([A-Za-z\\s]{1,50}?)\\s*</");
    // Match HTTP calls in script methods
    private static final Pattern HTTP_CALL = Pattern.compile(
            "this\\.(\\w+)\\s*\\(|(?:this\\.)?\\$http\\.(\\w+)\\s*\\(|fetch\\s*\\(|axios\\.(\\w+)\\s*\\(");

    /**
     * Extracts business-trigger events from a Vue component.
     *
     * @param componentFilePath path to the .vue component file
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
        Matcher m = VUE_EVENT.matcher(content);

        while (m.find()) {
            String eventType = m.group(1);           // "click", "submit", etc.
            String handlerExpr = m.group(2).trim();  // e.g., "deleteItem" or "deleteItem(id)"

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
        // Remove method arguments: "deleteItem(id)" → "deleteItem"
        int openParen = handlerExpr.indexOf('(');
        return openParen > 0 ? handlerExpr.substring(0, openParen).trim() : handlerExpr;
    }

    private String resolveHttpCall(String content, String handlerName, Map<String, String> httpMethodDescriptors) {
        // Extract script section
        Pattern scriptStart = Pattern.compile("<script[^>]*>");
        Matcher scriptMatcher = scriptStart.matcher(content);
        if (!scriptMatcher.find()) return null;

        int scriptBegin = scriptMatcher.end();
        int scriptEnd = content.indexOf("</script>", scriptBegin);
        if (scriptEnd < 0) return null;

        String scriptContent = content.substring(scriptBegin, scriptEnd);

        // Find the method definition: methodName() { ... } or methodName: function() { ... }
        Pattern methodDef = Pattern.compile(
                "(?:async\\s+)?" + Pattern.quote(handlerName)
                + "\\s*(?:\\(|:)\\s*(?:function\\s*)?\\(");

        Matcher defMatcher = methodDef.matcher(scriptContent);
        if (!defMatcher.find()) return null;

        int methodStart = scriptContent.lastIndexOf('{', defMatcher.end());
        if (methodStart < 0) return null;

        int methodEnd = findMatchingBrace(scriptContent, methodStart);
        if (methodEnd < 0) return null;

        String methodBody = scriptContent.substring(methodStart + 1, methodEnd);

        // Check if method body contains HTTP calls
        Matcher httpMatcher = HTTP_CALL.matcher(methodBody);
        while (httpMatcher.find()) {
            String methodName = httpMatcher.group(1);  // this.method
            if (methodName == null) methodName = httpMatcher.group(2);  // $http.method
            if (methodName == null) methodName = httpMatcher.group(3);  // fetch
            if (methodName == null) methodName = httpMatcher.group(4);  // axios.method

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
        if ("submit".equals(eventType)) {
            return "Form submit → " + httpDescriptor;
        }

        String action = switch (eventType) {
            case "click" -> "Button";
            case "change" -> "Change";
            case "blur" -> "Blur";
            case "focus" -> "Focus";
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
