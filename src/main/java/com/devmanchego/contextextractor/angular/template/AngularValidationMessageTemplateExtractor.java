package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog;
import com.devmanchego.contextextractor.common.ValidationMessage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Correlates a form validator with its error-message element in the template, by scanning for
 * {@code *ngIf} guards that check the validator and reading the content of the element they
 * guard.
 *
 * <p><b>Recognised error-check spellings</b> (mirroring {@code AngularFormValidationExtractor}'s
 * own assumption that the reactive {@code FormGroup} property is named {@code form}, optionally
 * {@code this.form}):
 * <ul>
 *   <li>{@code (this.)?form.get('field').hasError('validator')}</li>
 *   <li>{@code (this.)?form.get('field').errors?.validator} (or {@code ?.['validator']})</li>
 *   <li>{@code (this.)?form.controls.field.hasError('validator')} / {@code .errors?.validator}</li>
 *   <li>{@code (this.)?form.controls['field'].hasError('validator')} / {@code .errors?.validator}</li>
 *   <li>{@code field.hasError('validator')} / {@code field.errors?.validator} — the common
 *       convenience-getter pattern, where {@code field} is used bare and must equal the actual
 *       control name for the correlation to succeed.</li>
 * </ul>
 *
 * <p><b>Message content</b>: the element's inner text is read as an i18n key when it contains
 * an ngx-translate/transloco pipe ({@code {{ 'key' | translate }}}), resolved against the given
 * catalog into {@link com.devmanchego.contextextractor.common.ValidationMessageStatus#RESOLVED}
 * or {@link com.devmanchego.contextextractor.common.ValidationMessageStatus#KEY_UNRESOLVED};
 * otherwise the cleaned text is used as a
 * {@link com.devmanchego.contextextractor.common.ValidationMessageStatus#LITERAL} message.
 *
 * <p><b>Limitations</b>: self-closing error elements, elements whose bare identifier isn't the
 * literal control name, and non-standard {@code *ngIf} expressions (e.g. helper methods like
 * {@code isFieldInvalid('email', 'required')}) are not correlated — regex-based, like every other
 * template scanner in this package, not a full template parser.
 */
public final class AngularValidationMessageTemplateExtractor {

    private static final Pattern NG_IF_ATTR = Pattern.compile("\\*ngIf\\s*=\\s*\"([^\"]*)\"");

    private static final String CONTROL_ACCESSOR =
            "(?:(?:this\\.)?form\\.get\\(\\s*['\"]\\w+['\"]\\s*\\)"
            + "|(?:this\\.)?form\\.controls(?:\\.\\w+|\\[['\"]\\w+['\"]\\])"
            + "|\\w+)";

    // group(1) = control accessor expression, group(2) = validator name
    private static final Pattern HAS_ERROR_CHECK = Pattern.compile(
            "(" + CONTROL_ACCESSOR + ")\\.hasError\\(\\s*['\"](\\w+)['\"]\\s*\\)");
    private static final Pattern ERRORS_PROPERTY_CHECK = Pattern.compile(
            "(" + CONTROL_ACCESSOR + ")\\.errors\\?\\.?\\[?['\"]?(\\w+)['\"]?\\]?");

    private static final Pattern ACCESSOR_FORM_GET = Pattern.compile("(?:this\\.)?form\\.get\\(\\s*['\"](\\w+)['\"]");
    private static final Pattern ACCESSOR_CONTROLS_DOT = Pattern.compile("(?:this\\.)?form\\.controls\\.(\\w+)");
    private static final Pattern ACCESSOR_CONTROLS_BRACKET =
            Pattern.compile("(?:this\\.)?form\\.controls\\[['\"](\\w+)['\"]\\]");

    private static final Pattern I18N_PIPE = Pattern.compile(
            "\\{\\{\\s*['\"]([^'\"]+)['\"]\\s*\\|\\s*(?:translate|transloco)\\b");
    private static final Pattern INNER_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern TAG_NAME = Pattern.compile("^<([a-zA-Z][\\w-]*)");

    public Map<String, Map<String, ValidationMessage>> extract(String componentFilePath, AngularI18nCatalog catalog) {
        Map<String, Map<String, ValidationMessage>> result = new LinkedHashMap<>();
        String templateText = TemplateResolver.resolveTemplateText(componentFilePath);
        if (templateText == null) return result;

        Matcher ngIf = NG_IF_ATTR.matcher(templateText);
        while (ngIf.find()) {
            FieldValidatorPair pair = extractFieldValidator(ngIf.group(1));
            if (pair == null) continue;

            String innerContent = enclosingElementContent(templateText, ngIf.start());
            if (innerContent == null) continue; // self-closing or malformed — nothing to read

            ValidationMessage message = parseMessage(innerContent, catalog);
            if (message == null) continue; // no usable text found

            result.computeIfAbsent(pair.field(), k -> new LinkedHashMap<>()).put(pair.validator(), message);
        }
        return result;
    }

    private record FieldValidatorPair(String field, String validator) {}

    // -----------------------------------------------------------------------
    // *ngIf expression parsing
    // -----------------------------------------------------------------------

    private FieldValidatorPair extractFieldValidator(String ngIfExpression) {
        Matcher m = HAS_ERROR_CHECK.matcher(ngIfExpression);
        if (m.find()) {
            String field = extractFieldFromAccessor(m.group(1));
            if (field != null) return new FieldValidatorPair(field, m.group(2));
        }
        m = ERRORS_PROPERTY_CHECK.matcher(ngIfExpression);
        if (m.find()) {
            String field = extractFieldFromAccessor(m.group(1));
            if (field != null) return new FieldValidatorPair(field, m.group(2));
        }
        return null;
    }

    private String extractFieldFromAccessor(String accessor) {
        Matcher m = ACCESSOR_FORM_GET.matcher(accessor);
        if (m.find()) return m.group(1);
        m = ACCESSOR_CONTROLS_DOT.matcher(accessor);
        if (m.find()) return m.group(1);
        m = ACCESSOR_CONTROLS_BRACKET.matcher(accessor);
        if (m.find()) return m.group(1);
        if (accessor.matches("\\w+")) return accessor; // bare getter-style identifier
        return null;
    }

    // -----------------------------------------------------------------------
    // Enclosing element resolution
    // -----------------------------------------------------------------------

    /**
     * Finds the element containing the {@code *ngIf} match and returns its inner HTML content
     * (everything between the opening tag's {@code '>'} and the matching closing tag), tracking
     * nested elements of the same tag name. Returns {@code null} for self-closing elements
     * (nothing to correlate) or malformed markup.
     */
    private String enclosingElementContent(String templateText, int ngIfMatchStart) {
        int tagStart = templateText.lastIndexOf('<', ngIfMatchStart);
        if (tagStart < 0) return null;

        Matcher tagNameMatcher = TAG_NAME.matcher(templateText.substring(tagStart));
        if (!tagNameMatcher.find()) return null;
        String tagName = tagNameMatcher.group(1);

        int openTagEnd = findTagEnd(templateText, tagStart);
        if (openTagEnd < 0) return null;
        if (templateText.charAt(openTagEnd - 1) == '/') return null; // self-closing

        Pattern openOrClose = Pattern.compile("</?" + Pattern.quote(tagName) + "\\b[^>]*>");
        Matcher m = openOrClose.matcher(templateText);
        m.region(openTagEnd + 1, templateText.length());

        int depth = 1;
        while (m.find()) {
            String tag = m.group();
            if (tag.startsWith("</")) {
                if (--depth == 0) return templateText.substring(openTagEnd + 1, m.start());
            } else if (!tag.endsWith("/>")) {
                depth++;
            }
        }
        return null; // no matching closing tag found
    }

    /** Index of the {@code '>'} that closes the opening tag starting at {@code tagStart}, skipping quoted attribute values. */
    private int findTagEnd(String templateText, int tagStart) {
        for (int i = tagStart; i < templateText.length(); i++) {
            char c = templateText.charAt(i);
            if (c == '\'' || c == '"') { i = skipString(templateText, i); continue; }
            if (c == '>') return i;
        }
        return -1;
    }

    private int skipString(String s, int start) {
        char quote = s.charAt(start);
        for (int i = start + 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == quote) return i;
        }
        return s.length() - 1;
    }

    // -----------------------------------------------------------------------
    // Message content parsing
    // -----------------------------------------------------------------------

    private ValidationMessage parseMessage(String innerContent, AngularI18nCatalog catalog) {
        Matcher pipe = I18N_PIPE.matcher(innerContent);
        if (pipe.find()) {
            String key = pipe.group(1).trim();
            return catalog.resolve(key)
                    .map(text -> ValidationMessage.resolved(text, key))
                    .orElseGet(() -> ValidationMessage.keyUnresolved(key));
        }
        String literal = cleanText(innerContent);
        return literal.isBlank() ? null : ValidationMessage.literal(literal);
    }

    private String cleanText(String rawContent) {
        String text = INNER_TAGS.matcher(rawContent).replaceAll(" ");
        text = text.replaceAll("\\{\\{.*?}}", "").trim();
        text = text.replaceAll("\\s+", " ");
        return text;
    }
}
