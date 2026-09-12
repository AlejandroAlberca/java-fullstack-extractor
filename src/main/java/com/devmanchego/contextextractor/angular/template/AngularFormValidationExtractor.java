package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.common.FormValidationExtractorStrategy;
import com.devmanchego.contextextractor.common.ValidatorInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts form control validation rules from Angular forms — reactive first, falling back
 * to template-driven when no reactive {@code FormGroup} is found.
 *
 * <p><b>Reactive forms</b> (primary path): scans the component's {@code .ts} file for
 * {@code FormBuilder.group({...})} declarations and parses the {@code Validators.*(...)} calls
 * associated with each control.
 *
 * <p><b>Template-driven forms</b> (fallback, only tried when no reactive {@code FormGroup} is
 * found): scans the template for elements bound with {@code ngModel} — in any of its three
 * spellings, {@code [(ngModel)]}, {@code [ngModel]}, or bare {@code ngModel} — that also carry a
 * {@code name="field"} attribute (required by Angular's {@code NgForm} to register a standalone
 * control), and reads the HTML5 validation attributes on that same element: {@code required},
 * {@code email} (boolean), and {@code minlength}/{@code maxlength}/{@code pattern}/{@code min}/
 * {@code max} (valued, in either the plain {@code minlength="3"} or bound {@code [minlength]="3"}
 * form). These attribute names are already Angular's runtime error keys — no normalization is
 * needed, unlike the reactive {@code minLength}/{@code maxLength} camelCase quirk.
 *
 * <p>The result is a map of control name → list of validator descriptions (e.g. "required",
 * "minLength(3)", "email") suitable for rendering in documentation.
 *
 * <p><b>Limitations:</b> Custom validators, conditional validators, and programmatically added
 * validators are not resolved — only literal {@code Validators.*} calls (reactive) or literal
 * HTML5 attributes (template-driven). A component that mixes both styles (a reactive
 * {@code FormGroup} for one form, {@code ngModel} elsewhere for something unrelated) only
 * surfaces the reactive fields — the reactive path, once matched, is exclusive.
 */
public class AngularFormValidationExtractor implements FormValidationExtractorStrategy {

    private static final Logger log = LoggerFactory.getLogger(AngularFormValidationExtractor.class);

    // FormBuilder.group({ ... })
    private static final Pattern FORM_GROUP = Pattern.compile(
            "(?:this\\.)?form\\s*=\\s*this\\.fb\\.group\\s*\\(\\s*\\{");
    // Control declaration: fieldName: [initialValue, [validators...]] or fieldName: [init, Validator.xxx]
    // Capture field name and everything inside the brackets
    private static final Pattern CONTROL_DEF = Pattern.compile(
            "(\\w+)\\s*:\\s*\\[([^\\]]*)\\]");
    // Individual validator: Validators.required, Validators.minLength(3), etc.
    private static final Pattern VALIDATOR = Pattern.compile(
            "Validators\\.(\\w+)(?:\\(([^)]*)\\))?");

    // -----------------------------------------------------------------------
    // Template-driven forms
    // -----------------------------------------------------------------------

    private static final Pattern NAME_ATTR = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern NG_MODEL_SIGNAL = Pattern.compile(
            "\\[\\(ngModel\\)]|\\[ngModel]|\\bngModel\\b");
    // Boolean attributes: only a bare token (whitespace/tag-boundary on both sides), never a
    // quoted attribute value like type="email" or name="email".
    private static final Pattern REQUIRED_ATTR = Pattern.compile("(?<=[\\s<])required(?=[\\s>/])");
    private static final Pattern EMAIL_ATTR = Pattern.compile("(?<=[\\s<])email(?=[\\s>/])");
    // Valued attributes: plain (minlength="3") or bound ([minlength]="3").
    private static final Pattern MINLENGTH_ATTR = Pattern.compile("\\[?\\bminlength]?\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MAXLENGTH_ATTR = Pattern.compile("\\[?\\bmaxlength]?\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_ATTR = Pattern.compile("\\[?\\bpattern]?\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MIN_ATTR = Pattern.compile("\\[?\\bmin]?\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MAX_ATTR = Pattern.compile("\\[?\\bmax]?\\s*=\\s*\"([^\"]+)\"");

    public record Rule(String fieldName, List<ValidatorInfo> validators) {}

    /**
     * Angular's built-in validators expose their runtime error key in lower-case, without
     * camelCase, for two of them ({@code Validators.minLength} → {@code control.errors.minlength},
     * {@code Validators.maxLength} → {@code .maxlength}). Every other built-in and any custom
     * validator function is used verbatim as its own error key. Normalizing only these two here
     * — instead of at correlation time — keeps {@link ValidatorInfo#name()} equal to the literal
     * key a template's {@code errors?.x} check would use.
     */
    private static final Map<String, String> RUNTIME_ERROR_KEY_OVERRIDES = Map.of(
            "minLength", "minlength",
            "maxLength", "maxlength");

    /**
     * Extracts form validation rules from a component's TypeScript file.
     *
     * @param componentFilePath path to the {@code .ts} component file
     * @return map of control name → list of validators
     */
    @Override
    public Map<String, List<ValidatorInfo>> extract(String componentFilePath) {
        Map<String, List<ValidatorInfo>> rules = new LinkedHashMap<>();
        try {
            Path tsFile = Path.of(componentFilePath);
            if (!Files.isRegularFile(tsFile)) return rules;
            String content = Files.readString(tsFile, StandardCharsets.UTF_8);

            Matcher formMatcher = FORM_GROUP.matcher(content);
            if (formMatcher.find()) {
                int groupStart = formMatcher.end();
                int groupEnd = matchingBrace(content, groupStart - 1);
                if (groupEnd < 0) return rules;

                String groupBody = content.substring(groupStart, groupEnd);
                extractControlRules(groupBody, rules);
            } else {
                extractTemplateDrivenRules(componentFilePath, rules);
            }
        } catch (IOException e) {
            log.debug("Cannot extract form validation from {}: {}", componentFilePath, e.getMessage());
        }
        return rules;
    }

    private void extractControlRules(String groupBody, Map<String, List<ValidatorInfo>> rules) {
        Matcher controlMatcher = CONTROL_DEF.matcher(groupBody);
        while (controlMatcher.find()) {
            String fieldName = controlMatcher.group(1);
            String content = controlMatcher.group(2);  // e.g., "'', [Validators.required]" or "'', Validators.email"

            // If there's a nested array [validators...], extract from there; otherwise from the whole content
            String validatorsStr;
            int arrayStart = content.indexOf('[');
            if (arrayStart >= 0) {
                int arrayEnd = content.lastIndexOf(']');
                if (arrayEnd > arrayStart) {
                    validatorsStr = content.substring(arrayStart + 1, arrayEnd);
                } else {
                    validatorsStr = content;
                }
            } else {
                validatorsStr = content;
            }

            List<ValidatorInfo> validators = parseValidators(validatorsStr);
            if (!validators.isEmpty()) {
                rules.put(fieldName, validators);
            }
        }
    }

    /**
     * Scans the template for {@code name="field"} attributes on an {@code ngModel}-bound
     * element and reads the HTML5 validation attributes on that same element.
     */
    private void extractTemplateDrivenRules(String componentFilePath, Map<String, List<ValidatorInfo>> rules) {
        String templateText = TemplateResolver.resolveTemplateText(componentFilePath);
        if (templateText == null) return;

        Matcher nameMatch = NAME_ATTR.matcher(templateText);
        while (nameMatch.find()) {
            String fieldName = nameMatch.group(1);
            if (rules.containsKey(fieldName)) continue;

            String tag = enclosingTag(templateText, nameMatch.start());
            if (tag == null || !NG_MODEL_SIGNAL.matcher(tag).find()) continue;

            List<ValidatorInfo> validators = parseTemplateDrivenValidators(tag);
            if (!validators.isEmpty()) {
                rules.put(fieldName, validators);
            }
        }
    }

    private List<ValidatorInfo> parseTemplateDrivenValidators(String tag) {
        List<ValidatorInfo> validators = new ArrayList<>();
        if (REQUIRED_ATTR.matcher(tag).find()) validators.add(ValidatorInfo.simple("required"));
        if (EMAIL_ATTR.matcher(tag).find()) validators.add(ValidatorInfo.simple("email"));
        addValuedValidator(tag, MINLENGTH_ATTR, "minlength", validators);
        addValuedValidator(tag, MAXLENGTH_ATTR, "maxlength", validators);
        addValuedValidator(tag, PATTERN_ATTR, "pattern", validators);
        addValuedValidator(tag, MIN_ATTR, "min", validators);
        addValuedValidator(tag, MAX_ATTR, "max", validators);
        return validators;
    }

    private void addValuedValidator(String tag, Pattern attrPattern, String name, List<ValidatorInfo> validators) {
        Matcher m = attrPattern.matcher(tag);
        if (m.find()) {
            validators.add(ValidatorInfo.withArgs(name, m.group(1).trim()));
        }
    }

    /** Extracts the full opening tag {@code <tag attr="..." ...>} that contains the match at {@code matchStart}. */
    private String enclosingTag(String templateText, int matchStart) {
        int tagStart = templateText.lastIndexOf('<', matchStart);
        if (tagStart < 0) return null;
        int tagEnd = templateText.indexOf('>', matchStart);
        if (tagEnd < 0) return null;
        return templateText.substring(tagStart, tagEnd + 1);
    }

    private List<ValidatorInfo> parseValidators(String validatorsStr) {
        List<ValidatorInfo> validators = new ArrayList<>();
        Matcher m = VALIDATOR.matcher(validatorsStr);
        while (m.find()) {
            String methodName = m.group(1);
            String args = m.group(2);
            String errorKey = RUNTIME_ERROR_KEY_OVERRIDES.getOrDefault(methodName, methodName);

            validators.add(buildValidatorInfo(methodName, errorKey, args));
        }
        return validators;
    }

    /**
     * @param methodName display name as written ({@code Validators.minLength} → "minLength"),
     *                   used only for {@link ValidatorInfo#rawText()} so existing documentation
     *                   output is unchanged
     * @param errorKey   the runtime error key ({@link ValidatorInfo#name()}), possibly normalized
     */
    private ValidatorInfo buildValidatorInfo(String methodName, String errorKey, String args) {
        if (args == null || args.isEmpty()) {
            return new ValidatorInfo(errorKey, null, methodName);
        }
        // Clean up the arguments: remove quotes, trim whitespace
        String cleanArgs = args.trim().replaceAll("^['\"]|['\"]$", "");
        return new ValidatorInfo(errorKey, cleanArgs, methodName + "(" + cleanArgs + ")");
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
}
