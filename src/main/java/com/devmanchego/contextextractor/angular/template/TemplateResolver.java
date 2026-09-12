package com.devmanchego.contextextractor.angular.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the HTML template text of an Angular {@code @Component}, whether declared
 * inline ({@code template: `...`}) or referenced externally ({@code templateUrl: '...'}).
 *
 * <p>Shared by {@link TemplateLabelExtractor} and {@link AngularFormFieldExtractor} — both
 * need the raw template text before running their own regex scans over it.
 */
public final class TemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(TemplateResolver.class);

    private static final Pattern COMPONENT_DECORATOR = Pattern.compile("@Component\\s*\\(");
    private static final Pattern TEMPLATE_URL = Pattern.compile("templateUrl\\s*:\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern TEMPLATE_INLINE_KEY = Pattern.compile("\\btemplate\\b\\s*:");

    private TemplateResolver() {}

    public static String resolveTemplateText(String tsFilePath) {
        try {
            Path tsFile = Path.of(tsFilePath);
            if (!Files.isRegularFile(tsFile)) return null;
            String content = Files.readString(tsFile, StandardCharsets.UTF_8);

            Matcher dec = COMPONENT_DECORATOR.matcher(content);
            if (!dec.find()) return null;
            int argStart = dec.end() - 1; // index of '('
            int argEnd = matchingParen(content, argStart);
            if (argEnd < 0) return null;
            String args = content.substring(argStart + 1, argEnd);

            Matcher urlMatch = TEMPLATE_URL.matcher(args);
            if (urlMatch.find()) {
                Path htmlFile = tsFile.getParent().resolve(urlMatch.group(1)).normalize();
                if (Files.isRegularFile(htmlFile)) {
                    return Files.readString(htmlFile, StandardCharsets.UTF_8);
                }
                return null;
            }

            return extractInlineTemplate(args);
        } catch (IOException e) {
            log.debug("Cannot read template for {}: {}", tsFilePath, e.getMessage());
            return null;
        }
    }

    private static String extractInlineTemplate(String decoratorArgs) {
        Matcher keyMatch = TEMPLATE_INLINE_KEY.matcher(decoratorArgs);
        if (!keyMatch.find()) return null;

        int i = keyMatch.end();
        while (i < decoratorArgs.length() && Character.isWhitespace(decoratorArgs.charAt(i))) i++;
        if (i >= decoratorArgs.length()) return null;

        char quote = decoratorArgs.charAt(i);
        if (quote != '`' && quote != '\'' && quote != '"') return null;

        int end = findClosingQuote(decoratorArgs, i, quote);
        if (end < 0) return null;
        return decoratorArgs.substring(i + 1, end);
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(s, i);
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int skipString(String s, int start) {
        char quote = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return i;
        }
        return s.length() - 1;
    }

    private static int findClosingQuote(String s, int start, char quote) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return i;
        }
        return -1;
    }
}
