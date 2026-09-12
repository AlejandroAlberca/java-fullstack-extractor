package com.devmanchego.contextextractor.react.template;

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
 * Extracts form validation rules from React components using Zod and Yup schemas.
 *
 * <p><b>Supported patterns:</b>
 * <ul>
 *   <li>Zod: {@code z.object({ name: z.string().min(3).email() })}</li>
 *   <li>Yup: {@code yup.object({ email: yup.string().required().email() })}</li>
 *   <li>React Hook Form: {@code useForm({ resolver: zodResolver(schema) })}</li>
 *   <li>Formik: {@code <Formik validationSchema={yup.object({...})} />}</li>
 * </ul>
 *
 * <p>Returns map of field name → list of validators (e.g., "required", "min(5)", "email").
 */
public final class ReactFormValidationExtractor implements FormValidationExtractorStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReactFormValidationExtractor.class);

    // Match schema objects: z.object({ ... }) or yup.object({ ... })
    private static final Pattern SCHEMA_PATTERN = Pattern.compile(
            "(?:z|yup)\\.object\\s*\\(");
    // Match field definitions: fieldName: z.string()... or fieldName: yup.string()...
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "\\b(\\w+)\\s*:\\s*(?:z|yup)\\.(\\w+)\\(\\)");
    // Match validator methods: .required(), .min(5), .email(), etc.
    private static final Pattern VALIDATOR_PATTERN = Pattern.compile(
            "\\.(\\w+)(?:\\(([^)]*)\\))?");
    // Base type methods to skip (not validators)
    private static final Pattern BASE_TYPE = Pattern.compile(
            "^(string|number|boolean|date|object|array|null|optional|nullable|"
            + "default|parse|transform|refine|pipe|catch|lazy|coerce)$");

    /**
     * Extracts form validation rules from a React component.
     *
     * @param componentFilePath path to the .tsx/.jsx component file
     * @return map of field name → list of validator descriptions
     */
    @Override
    public Map<String, List<ValidatorInfo>> extract(String componentFilePath) {
        Map<String, List<ValidatorInfo>> rules = new LinkedHashMap<>();
        try {
            Path file = Path.of(componentFilePath);
            if (!Files.isRegularFile(file)) return rules;
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Find all schema objects in the file
            Matcher schemaMatcher = SCHEMA_PATTERN.matcher(content);
            while (schemaMatcher.find()) {
                int schemaStart = schemaMatcher.end() - 1;
                int schemaEnd = findMatchingBrace(content, schemaStart);
                if (schemaEnd > 0) {
                    String schemaBody = content.substring(schemaStart + 1, schemaEnd);
                    extractFieldsFromSchema(schemaBody, rules);
                }
            }
        } catch (IOException e) {
            log.debug("Cannot extract form validation from {}: {}", componentFilePath, e.getMessage());
        }
        return rules;
    }

    private void extractFieldsFromSchema(String schemaBody, Map<String, List<ValidatorInfo>> rules) {
        Matcher fieldMatcher = FIELD_PATTERN.matcher(schemaBody);

        while (fieldMatcher.find()) {
            String fieldName = fieldMatcher.group(1);
            int fieldStart = fieldMatcher.end();

            // Extract validators until we hit a comma at the same depth
            int depth = 0;
            int i = fieldStart;
            while (i < schemaBody.length()) {
                char c = schemaBody.charAt(i);

                // Skip strings
                if (c == '\'' || c == '"' || c == '`') {
                    i = skipString(schemaBody, i);
                    if (i < schemaBody.length()) i++;
                    continue;
                }

                // Track depth for nested calls
                if (c == '(' || c == '{' || c == '[') depth++;
                else if (c == ')' || c == '}' || c == ']') depth--;

                // Stop at comma at depth 0
                if (c == ',' && depth == 0) break;

                i++;
            }

            if (i > fieldStart) {
                String validatorChain = schemaBody.substring(fieldStart, i);
                List<ValidatorInfo> validators = parseValidators(validatorChain);
                if (!validators.isEmpty()) {
                    rules.put(fieldName, validators);
                }
            }
        }
    }

    private List<ValidatorInfo> parseValidators(String chain) {
        List<ValidatorInfo> validators = new ArrayList<>();
        Matcher m = VALIDATOR_PATTERN.matcher(chain);

        while (m.find()) {
            String methodName = m.group(1);
            String methodArgs = m.group(2);

            // Skip base type methods
            if (BASE_TYPE.matcher(methodName).matches()) continue;

            ValidatorInfo validator = buildValidatorInfo(methodName, methodArgs);
            if (validator != null) {
                validators.add(validator);
            }
        }

        return validators;
    }

    private ValidatorInfo buildValidatorInfo(String name, String args) {
        if (args == null || args.trim().isEmpty()) {
            return ValidatorInfo.simple(name);
        }

        String clean = args.trim().replaceAll("^['\"]|['\"]$", "").split(",")[0].trim();
        return clean.isEmpty() ? ValidatorInfo.simple(name) : ValidatorInfo.withArgs(name, clean);
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
}
