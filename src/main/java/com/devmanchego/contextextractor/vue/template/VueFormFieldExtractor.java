package com.devmanchego.contextextractor.vue.template;

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
 * Associates each {@code v-model}-bound field in a Vue component's template with its
 * user-facing {@code <label>}, mirroring {@code AngularFormFieldExtractor}'s approach and
 * three-signal resolution order.
 *
 * <p><b>Field identification</b>: {@code v-model="form.field"} (any modifier, e.g.
 * {@code v-model.lazy}), stripping a literal {@code form.} prefix if present — the same
 * convention {@link VueFormValidationExtractor#extractFieldNameNear} already uses. This must
 * stay in lockstep with that class: a different convention here would silently break the join
 * between a field's label and its validators in the same table row. Computed/bracket access
 * ({@code v-model="form[fieldVar]"}) is not a literal path and is left unresolved.
 *
 * <p><b>Label resolution</b>, tried in order per field:
 * <ol>
 *   <li>{@code <label for="ID">...</label>} paired via the control's {@code id}.</li>
 *   <li>{@code aria-label="TEXT"} on the control element itself.</li>
 *   <li>The nearest preceding {@code <label>...</label>}.</li>
 * </ol>
 *
 * <p><b>No i18n key detection</b>: unlike Angular, Vue has no single dominant i18n convention
 * this codebase resolves against, so every label here is a literal. A label whose content is an
 * interpolation ({@code {{ $t('key') }}}) rather than plain text yields no usable literal and
 * the field is skipped.
 *
 * <p>Regex-based: Vue single-file components are not parsed by either JVM strategy, so this
 * scans the raw {@code .vue} source directly.
 */
public final class VueFormFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(VueFormFieldExtractor.class);

    private static final Pattern V_MODEL_ATTR = Pattern.compile(
            "v-model(?:\\.\\w+)?\\s*=\\s*[\"'](?:form\\.)?([^\"']+)[\"']");
    private static final Pattern LITERAL_FIELD_PATH = Pattern.compile("[\\w.]+");
    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ARIA_LABEL_ATTR = Pattern.compile("\\baria-label\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern LABEL_ELEMENT = Pattern.compile("<label\\b[^>]*>.*?</label>", Pattern.DOTALL);
    private static final Pattern INNER_TAGS = Pattern.compile("<[^>]+>");
    // Vue interpolation: {{ expr }}. Not a literal — a field label is skipped if this is all its content is.
    private static final Pattern INTERPOLATION = Pattern.compile("\\{\\{.*?}}", Pattern.DOTALL);

    private static final int BACKWARD_LOOKBACK_CHARS = 400;

    /**
     * @param componentFilePath path to the {@code .vue} component file
     * @return map of field name → resolved literal label, in template order. Fields whose label
     *         could not be resolved to a literal are omitted.
     */
    public Map<String, FieldLabel> extract(String componentFilePath) {
        Map<String, FieldLabel> labels = new LinkedHashMap<>();
        String content = readFile(componentFilePath);
        if (content == null) return labels;

        Matcher vModelMatch = V_MODEL_ATTR.matcher(content);
        while (vModelMatch.find()) {
            String fieldPath = vModelMatch.group(1);
            if (!LITERAL_FIELD_PATH.matcher(fieldPath).matches()) continue;
            if (labels.containsKey(fieldPath)) continue;

            String tag = enclosingTag(content, vModelMatch.start());
            if (tag == null) continue;

            FieldLabel label = resolveLabelFor(content, tag, vModelMatch.start());
            if (label != null) {
                labels.put(fieldPath, label);
            }
        }
        return labels;
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
                "<label\\b[^>]*\\bfor\\s*=\\s*[\"']" + Pattern.quote(id) + "[\"'][^>]*>.*?</label>",
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

    /** No i18n signal is recognised — an interpolation as content yields no usable literal. */
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
        text = INTERPOLATION.matcher(text).replaceAll("");
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
