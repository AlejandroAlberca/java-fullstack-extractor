package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.common.FieldLabel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Associates each reactive-form control ({@code formControlName="x"}) in an Angular
 * component's template with its user-facing label, when one can be resolved.
 *
 * <p>The resolved label is either a <b>literal</b> string (plain template text) or an
 * <b>i18n key</b> (ngx-translate / transloco), captured as a {@link FieldLabel}. Key
 * resolution to an actual message is the caller's job (see
 * {@link com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog}); this class only
 * detects <i>which</i> key a field uses.
 *
 * <p>Three signals are tried, in order, for each control found in the template:
 * <ol>
 *   <li>{@code <label for="ID">...</label>} paired via the control's {@code id} attribute
 *       — the most reliable signal, works regardless of markup order.</li>
 *   <li>{@code aria-label="TEXT"} on the control element itself (always literal).</li>
 *   <li>The nearest preceding {@code <label>...</label>} in the template — covers the common
 *       pattern of a label placed immediately before its control with no {@code for}/{@code id}
 *       pairing.</li>
 * </ol>
 * If none of these resolve, the control is omitted from the result.
 *
 * <p>i18n keys are recognised in a label element via: the {@code translate}/{@code transloco}
 * string attribute ({@code <label translate="key">}), the property binding
 * ({@code [translate]="'key'"}), the bare directive ({@code <label translate>key</label>},
 * whose text content is the key), or a pipe in the content
 * ({@code {{ 'key' | translate }}}).
 *
 * <p>Regex-based, like {@link TemplateLabelExtractor}: Angular templates are not parsed by
 * either JVM strategy, so this scans the resolved template text directly. Dynamic control
 * names ({@code [formControlName]="var"}) are not resolved since the value isn't a literal.
 *
 * <p><b>Template-driven forms:</b> a {@code name="field"} attribute is also recognised as a
 * control identifier, but only on an element that also carries an {@code ngModel} binding
 * ({@code [(ngModel)]}, {@code [ngModel]}, or bare {@code ngModel}) — otherwise any unrelated
 * {@code name} attribute (e.g. a plain {@code <div name="section">}) would be mistaken for a
 * form field. Label resolution then proceeds identically to the {@code formControlName} case.
 */
public final class AngularFormFieldExtractor {

    // Only the static attribute form (formControlName="x") is matched. The bound form
    // ([formControlName]="expr") holds a JS expression, not a literal control name, and
    // is deliberately excluded rather than mistaking the expression text for a field.
    private static final Pattern CONTROL_NAME = Pattern.compile(
            "(?<!\\[)\\bformControlName\\s*=\\s*\"([^\"]+)\"");
    // Template-driven forms: name="field" is only treated as a control identifier when the
    // same element also carries an ngModel binding (checked separately against the enclosing tag).
    private static final Pattern NAME_ATTR = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern NG_MODEL_SIGNAL = Pattern.compile(
            "\\[\\(ngModel\\)]|\\[ngModel]|\\bngModel\\b");
    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ARIA_LABEL_ATTR = Pattern.compile("\\baria-label\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern LABEL_ELEMENT = Pattern.compile("<label\\b[^>]*>.*?</label>", Pattern.DOTALL);
    private static final Pattern INNER_TAGS = Pattern.compile("<[^>]+>");

    // i18n key signals within a <label> element
    private static final Pattern I18N_ATTR_STRING = Pattern.compile(
            "\\b(?:translate|transloco)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern I18N_ATTR_BINDING = Pattern.compile(
            "\\[(?:translate|transloco)]\\s*=\\s*\"\\s*'([^']+)'\\s*\"");
    private static final Pattern I18N_BARE_DIRECTIVE = Pattern.compile(
            "\\b(?:translate|transloco)\\b(?!\\s*=)(?!])");
    private static final Pattern I18N_PIPE = Pattern.compile(
            "\\{\\{\\s*['\"]([^'\"]+)['\"]\\s*\\|\\s*(?:translate|transloco)\\b");
    // @angular/localize: i18n="meaning|description@@customId" — only the part after @@ is the
    // translation id. Without @@ Angular generates a content hash instead, which cannot be mapped
    // back to a template location, so those are deliberately not matched here.
    private static final Pattern I18N_LOCALIZE_ATTR = Pattern.compile(
            "\\bi18n\\s*=\\s*\"[^\"]*@@([^\"]+)\"");

    private static final int BACKWARD_LOOKBACK_CHARS = 400;

    /**
     * @param componentFilePath path to the {@code .ts} component file
     * @return map of control name → resolved label, in template order. Controls whose
     *         label could not be resolved are omitted.
     */
    public Map<String, FieldLabel> extract(String componentFilePath) {
        Map<String, FieldLabel> labels = new LinkedHashMap<>();
        String templateText = TemplateResolver.resolveTemplateText(componentFilePath);
        if (templateText == null) return labels;

        Matcher controlMatch = CONTROL_NAME.matcher(templateText);
        while (controlMatch.find()) {
            String fieldName = controlMatch.group(1);
            if (labels.containsKey(fieldName)) continue;

            String tag = enclosingTag(templateText, controlMatch.start());
            if (tag == null) continue;

            FieldLabel label = resolveLabelFor(templateText, tag, controlMatch.start());
            if (label != null) {
                labels.put(fieldName, label);
            }
        }

        Matcher nameMatch = NAME_ATTR.matcher(templateText);
        while (nameMatch.find()) {
            String fieldName = nameMatch.group(1);
            if (labels.containsKey(fieldName)) continue;

            String tag = enclosingTag(templateText, nameMatch.start());
            if (tag == null || !NG_MODEL_SIGNAL.matcher(tag).find()) continue;

            FieldLabel label = resolveLabelFor(templateText, tag, nameMatch.start());
            if (label != null) {
                labels.put(fieldName, label);
            }
        }
        return labels;
    }

    private FieldLabel resolveLabelFor(String templateText, String controlTag, int controlStart) {
        Matcher idMatch = ID_ATTR.matcher(controlTag);
        if (idMatch.find()) {
            FieldLabel label = findLabelByFor(templateText, idMatch.group(1));
            if (label != null) return label;
        }

        Matcher ariaMatch = ARIA_LABEL_ATTR.matcher(controlTag);
        if (ariaMatch.find()) {
            return FieldLabel.ofLiteral(ariaMatch.group(1).trim());
        }

        return findNearestPrecedingLabel(templateText, controlStart);
    }

    private FieldLabel findLabelByFor(String templateText, String id) {
        Pattern forPattern = Pattern.compile(
                "<label\\b[^>]*\\bfor\\s*=\\s*\"" + Pattern.quote(id) + "\"[^>]*>.*?</label>", Pattern.DOTALL);
        Matcher m = forPattern.matcher(templateText);
        return m.find() ? parseLabelElement(m.group()) : null;
    }

    private FieldLabel findNearestPrecedingLabel(String templateText, int controlStart) {
        int windowStart = Math.max(0, controlStart - BACKWARD_LOOKBACK_CHARS);
        String window = templateText.substring(windowStart, controlStart);

        Matcher m = LABEL_ELEMENT.matcher(window);
        String lastLabel = null;
        while (m.find()) {
            lastLabel = m.group();
        }
        return lastLabel != null ? parseLabelElement(lastLabel) : null;
    }

    /**
     * Interprets a full {@code <label ...>...</label>} element as either an i18n key or a
     * literal string. Returns {@code null} only when the element yields no usable text.
     */
    private FieldLabel parseLabelElement(String labelHtml) {
        int openTagEnd = labelHtml.indexOf('>');
        String openTag = openTagEnd >= 0 ? labelHtml.substring(0, openTagEnd) : labelHtml;
        String content = openTagEnd >= 0
                ? labelHtml.substring(openTagEnd + 1, labelHtml.lastIndexOf("</label>"))
                : "";

        Matcher binding = I18N_ATTR_BINDING.matcher(openTag);
        if (binding.find()) return FieldLabel.ofKey(binding.group(1).trim());

        Matcher attr = I18N_ATTR_STRING.matcher(openTag);
        if (attr.find()) return FieldLabel.ofKey(attr.group(1).trim());

        Matcher localize = I18N_LOCALIZE_ATTR.matcher(openTag);
        if (localize.find()) {
            String key = localize.group(1).trim();
            String sourceText = cleanLabelText(content);
            return sourceText.isBlank() ? FieldLabel.ofKey(key) : FieldLabel.ofKeyWithSource(key, sourceText);
        }

        Matcher pipe = I18N_PIPE.matcher(content);
        if (pipe.find()) return FieldLabel.ofKey(pipe.group(1).trim());

        if (I18N_BARE_DIRECTIVE.matcher(openTag).find()) {
            String key = cleanLabelText(content);
            return key.isBlank() ? null : FieldLabel.ofKey(key);
        }

        String literal = cleanLabelText(content);
        return literal.isBlank() ? null : FieldLabel.ofLiteral(literal);
    }

    private String cleanLabelText(String rawContent) {
        String text = INNER_TAGS.matcher(rawContent).replaceAll(" ");
        text = text.replaceAll("\\{\\{.*?}}", "").trim();
        text = text.replaceAll("\\s+", " ");
        text = text.replaceAll("\\s*\\*\\s*$", ""); // trailing required-marker asterisk
        return text;
    }

    /** Extracts the full opening tag `<tag attr="..." ...>` that contains the match at {@code matchStart}. */
    private String enclosingTag(String templateText, int matchStart) {
        int tagStart = templateText.lastIndexOf('<', matchStart);
        if (tagStart < 0) return null;
        int tagEnd = templateText.indexOf('>', matchStart);
        if (tagEnd < 0) return null;
        return templateText.substring(tagStart, tagEnd + 1);
    }
}
