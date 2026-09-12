package com.devmanchego.contextextractor.react.template;

import com.devmanchego.contextextractor.common.FieldLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Associates each form field in a React component's JSX with its user-facing {@code <label>},
 * mirroring {@code AngularFormFieldExtractor}'s approach and three-signal resolution order.
 *
 * <p><b>Field identification</b> (either spelling, JSX has no framework-enforced convention):
 * <ul>
 *   <li>{@code register("field")} — react-hook-form, matching the same field name
 *       {@link ReactFormValidationExtractor} reads from a Zod/Yup schema key.</li>
 *   <li>{@code name="field"} (static attribute only — {@code name={fieldVar}} isn't a literal
 *       and is left unresolved, same philosophy as Angular's {@code [formControlName]="var"}).</li>
 * </ul>
 *
 * <p><b>Label resolution</b>, tried in order per field:
 * <ol>
 *   <li>{@code <label htmlFor="ID">...</label>} paired via the control's {@code id}.</li>
 *   <li>{@code aria-label="TEXT"} on the control element itself.</li>
 *   <li>The nearest preceding {@code <label>...</label>}.</li>
 * </ol>
 *
 * <p><b>No i18n key detection</b>: unlike Angular (ngx-translate/transloco/@angular/localize),
 * React has no single dominant i18n convention this codebase resolves against, so every label
 * here is a literal. A label whose content is a JSX expression ({@code {t('key')}}) rather than
 * plain text yields no usable literal and the field is skipped — this class does not guess at
 * an i18n key it cannot resolve.
 *
 * <p>Regex-based: JSX is not parsed by either JVM strategy, so this scans the raw {@code .tsx}/
 * {@code .jsx} source directly.
 */
public final class ReactFormFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(ReactFormFieldExtractor.class);

    private static final Pattern REGISTER_CALL = Pattern.compile(
            "\\bregister\\(\\s*[\"'`](\\w+)[\"'`]");
    private static final Pattern NAME_ATTR = Pattern.compile("\\bname\\s*=\\s*[\"'`](\\w+)[\"'`]");
    private static final Pattern HTML_FOR_ATTR = Pattern.compile("\\bhtmlFor\\s*=\\s*[\"'`]([^\"'`]+)[\"'`]");
    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*[\"'`]([^\"'`]+)[\"'`]");
    private static final Pattern ARIA_LABEL_ATTR = Pattern.compile("\\baria-label\\s*=\\s*[\"'`]([^\"'`]+)[\"'`]");
    private static final Pattern LABEL_ELEMENT = Pattern.compile("<label\\b[^>]*>.*?</label>", Pattern.DOTALL);
    private static final Pattern INNER_TAGS = Pattern.compile("<[^>]+>");
    // JSX expression container: {expr}. Non-nested — a label's text is not expected to hold
    // deeply nested braces, and this is regex-based like the rest of this package.
    private static final Pattern JSX_EXPRESSION = Pattern.compile("\\{[^{}]*}");

    private static final int BACKWARD_LOOKBACK_CHARS = 400;

    /**
     * @param componentFilePath path to the {@code .tsx}/{@code .jsx} component file
     * @return map of field name → resolved literal label, in source order. Fields whose label
     *         could not be resolved to a literal are omitted.
     */
    public Map<String, FieldLabel> extract(String componentFilePath) {
        Map<String, FieldLabel> labels = new LinkedHashMap<>();
        String content = readFile(componentFilePath);
        if (content == null) return labels;

        collectFieldMatches(content, REGISTER_CALL, labels);
        collectFieldMatches(content, NAME_ATTR, labels);
        return labels;
    }

    private void collectFieldMatches(String content, Pattern fieldPattern, Map<String, FieldLabel> labels) {
        Matcher fieldMatch = fieldPattern.matcher(content);
        while (fieldMatch.find()) {
            String fieldName = fieldMatch.group(1);
            if (labels.containsKey(fieldName)) continue;

            String tag = enclosingTag(content, fieldMatch.start());
            if (tag == null) continue;

            FieldLabel label = resolveLabelFor(content, tag, fieldMatch.start());
            if (label != null) {
                labels.put(fieldName, label);
            }
        }
    }

    private String readFile(String componentFilePath) {
        try {
            Path file = Path.of(componentFilePath);
            if (!Files.isRegularFile(file)) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Cannot read {}: {}", componentFilePath, e.getMessage());
            return null;
        }
    }

    private FieldLabel resolveLabelFor(String content, String controlTag, int controlStart) {
        Matcher idMatch = ID_ATTR.matcher(controlTag);
        if (idMatch.find()) {
            FieldLabel label = findLabelByFor(content, idMatch.group(1));
            if (label != null) return label;
        }

        Matcher ariaMatch = ARIA_LABEL_ATTR.matcher(controlTag);
        if (ariaMatch.find()) {
            return FieldLabel.ofLiteral(ariaMatch.group(1).trim());
        }

        return findNearestPrecedingLabel(content, controlStart);
    }

    private FieldLabel findLabelByFor(String content, String id) {
        Pattern forPattern = Pattern.compile(
                "<label\\b[^>]*\\bhtmlFor\\s*=\\s*[\"'`]" + Pattern.quote(id) + "[\"'`][^>]*>.*?</label>",
                Pattern.DOTALL);
        Matcher m = forPattern.matcher(content);
        return m.find() ? parseLabelElement(m.group()) : null;
    }

    private FieldLabel findNearestPrecedingLabel(String content, int controlStart) {
        int windowStart = Math.max(0, controlStart - BACKWARD_LOOKBACK_CHARS);
        String window = content.substring(windowStart, controlStart);

        Matcher m = LABEL_ELEMENT.matcher(window);
        String lastLabel = null;
        while (m.find()) {
            lastLabel = m.group();
        }
        return lastLabel != null ? parseLabelElement(lastLabel) : null;
    }

    /** No i18n signal is recognised — a JSX expression as content yields no usable literal. */
    private FieldLabel parseLabelElement(String labelHtml) {
        int openTagEnd = labelHtml.indexOf('>');
        String rawContent = openTagEnd >= 0
                ? labelHtml.substring(openTagEnd + 1, labelHtml.lastIndexOf("</label>"))
                : "";

        String literal = cleanLabelText(rawContent);
        return literal.isBlank() ? null : FieldLabel.ofLiteral(literal);
    }

    private String cleanLabelText(String rawContent) {
        String text = INNER_TAGS.matcher(rawContent).replaceAll(" ");
        text = JSX_EXPRESSION.matcher(text).replaceAll("");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    /** Extracts the full opening tag `<tag attr="..." ...>` that contains the match at {@code matchStart}. */
    private String enclosingTag(String content, int matchStart) {
        int tagStart = content.lastIndexOf('<', matchStart);
        if (tagStart < 0) return null;
        int tagEnd = content.indexOf('>', matchStart);
        if (tagEnd < 0) return null;
        return content.substring(tagStart, tagEnd + 1);
    }
}
