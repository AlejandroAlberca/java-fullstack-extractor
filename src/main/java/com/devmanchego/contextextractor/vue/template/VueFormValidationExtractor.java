package com.devmanchego.contextextractor.vue.template;

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
 * Extracts form validation rules from Vue components (.vue files).
 *
 * <p><b>Supported patterns:</b>
 * <ul>
 *   <li>Vee-Validate: {@code rules="required|email" } or {@code v-model.lazy="form.email"}</li>
 *   <li>Vuelidate: {@code $v.form.name.$dirty && $v.form.name.$error}</li>
 *   <li>Inline validation in script: {@code rules: { name: ['required', 'minLength'] }}</li>
 * </ul>
 *
 * <p>Returns map of field name → list of validators.
 */
public final class VueFormValidationExtractor implements FormValidationExtractorStrategy {

    private static final Logger log = LoggerFactory.getLogger(VueFormValidationExtractor.class);

    // Vee-Validate rules in template: rules="required|email|min:3"
    private static final Pattern VEE_VALIDATE_RULES = Pattern.compile(
            "rules\\s*=\\s*['\"]([^'\"]+)['\"]");
    // Inline validation in script: name: ['required', 'min:5', 'email']
    private static final Pattern INLINE_RULES = Pattern.compile(
            "\\b(\\w+)\\s*:\\s*\\[([^\\]]+)\\]");
    // Extract individual rules: 'required', 'email', 'min:5'
    private static final Pattern RULE_PATTERN = Pattern.compile(
            "['\"]?([^'\":|\\s]+)(?::([^'\"\\|]+))?['\"]?");

    /**
     * Extracts form validation rules from a Vue component.
     *
     * @param componentFilePath path to the .vue component file
     * @return map of field name → list of validator descriptions
     */
    @Override
    public Map<String, List<ValidatorInfo>> extract(String componentFilePath) {
        Map<String, List<ValidatorInfo>> rules = new LinkedHashMap<>();
        try {
            Path file = Path.of(componentFilePath);
            if (!Files.isRegularFile(file)) return rules;
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Extract script section from .vue file
            String scriptContent = extractScriptSection(content);
            if (scriptContent != null) {
                extractInlineRules(scriptContent, rules);
            }

            // Extract from template rules attributes
            extractVeeValidateRules(content, rules);
        } catch (IOException e) {
            log.debug("Cannot extract form validation from {}: {}", componentFilePath, e.getMessage());
        }
        return rules;
    }

    private String extractScriptSection(String content) {
        Pattern scriptStart = Pattern.compile("<script[^>]*>");
        Matcher m = scriptStart.matcher(content);
        if (!m.find()) return null;

        int start = m.end();
        int end = content.indexOf("</script>", start);
        if (end < 0) return null;

        return content.substring(start, end);
    }

    private void extractInlineRules(String scriptContent, Map<String, List<ValidatorInfo>> rules) {
        Matcher m = INLINE_RULES.matcher(scriptContent);

        while (m.find()) {
            String fieldName = m.group(1);
            String rulesStr = m.group(2);  // e.g., "'required', 'min:5', 'email'"

            List<ValidatorInfo> validators = parseRulesList(rulesStr);
            if (!validators.isEmpty()) {
                rules.put(fieldName, validators);
            }
        }
    }

    private void extractVeeValidateRules(String content, Map<String, List<ValidatorInfo>> rules) {
        Matcher m = VEE_VALIDATE_RULES.matcher(content);

        // Vee-Validate uses pipe-separated rules: "required|email|min:5"
        // We need to find the associated field name from v-model
        while (m.find()) {
            String rulesStr = m.group(1);  // e.g., "required|email|min:5"
            List<ValidatorInfo> validators = parseVeeValidateRules(rulesStr);

            if (!validators.isEmpty()) {
                // Try to find the v-model field name before this rules attribute
                int rulePos = m.start();
                String fieldName = extractFieldNameNear(content, rulePos);

                if (fieldName != null) {
                    rules.put(fieldName, validators);
                }
            }
        }
    }

    private String extractFieldNameNear(String content, int rulePos) {
        // Look backwards for v-model="form.fieldName"
        int searchStart = Math.max(0, rulePos - 200);
        String context = content.substring(searchStart, rulePos);

        Pattern vModelPattern = Pattern.compile("v-model(?:\\.\\w+)?\\s*=\\s*['\"](?:form\\.)?([^'\"]+)['\"]");
        Matcher m = vModelPattern.matcher(context);

        if (m.find()) {
            return m.group(1).replaceAll("\\W", "");  // extract field name
        }

        return null;
    }

    private List<ValidatorInfo> parseRulesList(String rulesStr) {
        List<ValidatorInfo> validators = new ArrayList<>();

        // Split by comma: 'required', 'min:5', 'email'
        String[] parts = rulesStr.split(",");
        for (String part : parts) {
            part = part.trim().replaceAll("^['\"]|['\"]$", "");

            if (part.isEmpty()) continue;

            // Format: "required" or "min:5" or "pattern:regex"
            String[] ruleParts = part.split(":");
            String ruleName = ruleParts[0].trim();
            String ruleArg = ruleParts.length > 1 ? ruleParts[1].trim() : null;

            validators.add(ruleArg != null && !ruleArg.isEmpty()
                    ? ValidatorInfo.withArgs(ruleName, ruleArg)
                    : ValidatorInfo.simple(ruleName));
        }

        return validators;
    }

    private List<ValidatorInfo> parseVeeValidateRules(String rulesStr) {
        // Vee-Validate uses pipe separator: "required|email|min:5"
        List<ValidatorInfo> validators = new ArrayList<>();
        String[] parts = rulesStr.split("\\|");

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            if (part.contains(":")) {
                String[] ruleParts = part.split(":", 2);
                validators.add(ValidatorInfo.withArgs(ruleParts[0], ruleParts[1]));
            } else {
                validators.add(ValidatorInfo.simple(part));
            }
        }

        return validators;
    }
}
