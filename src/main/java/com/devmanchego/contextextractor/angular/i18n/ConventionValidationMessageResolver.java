package com.devmanchego.contextextractor.angular.i18n;

import com.devmanchego.contextextractor.common.ValidationMessage;
import com.devmanchego.contextextractor.common.ValidatorInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a validator's user-facing error message purely by naming convention, without
 * reading the component's template: for a control named {@code field} with a validator whose
 * runtime error key is {@code validator}, the candidate i18n key is {@code errors.field.validator}
 * (e.g. {@code errors.email.required}).
 *
 * <p>This is a best-effort shortcut, not a confirmed correlation: if the guessed key has no
 * entry in the catalog, that does not necessarily mean the project has a missing translation —
 * it may simply not follow this convention, or the message may be a hardcoded literal in the
 * template. Either way the result is {@link com.devmanchego.contextextractor.common.ValidationMessageStatus#NOT_FOUND},
 * distinct from a key that was actually observed in the template but is absent from the catalog
 * (which callers should classify as {@code KEY_UNRESOLVED} once template correlation exists).
 *
 * <p>Projects that use a different convention (e.g. {@code validation.field.validator}, or no
 * prefix at all) will see every validator resolve to {@code NOT_FOUND} here — a future template
 * correlation step (reading the actual {@code *ngIf}/{@code mat-error} block) is the fallback
 * for those, not a change to this class.
 */
public final class ConventionValidationMessageResolver {

    private static final String KEY_PREFIX = "errors";

    /**
     * @param formRules map of control name → validators, as produced by
     *                  {@code FormValidationExtractorStrategy.extract}
     * @param catalog   the project's i18n catalog to resolve candidate keys against
     * @return map of control name → (validator name → resolution outcome), one entry per
     *         validator passed in, in the same order
     */
    public Map<String, Map<String, ValidationMessage>> resolve(Map<String, List<ValidatorInfo>> formRules,
                                                                AngularI18nCatalog catalog) {
        Map<String, Map<String, ValidationMessage>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<ValidatorInfo>> entry : formRules.entrySet()) {
            String field = entry.getKey();
            Map<String, ValidationMessage> perValidator = new LinkedHashMap<>();
            for (ValidatorInfo validator : entry.getValue()) {
                perValidator.put(validator.name(), resolveOne(field, validator.name(), catalog));
            }
            result.put(field, perValidator);
        }
        return result;
    }

    private ValidationMessage resolveOne(String field, String validatorName, AngularI18nCatalog catalog) {
        String key = KEY_PREFIX + "." + field + "." + validatorName;
        return catalog.resolve(key)
                .map(text -> ValidationMessage.resolved(text, key))
                .orElseGet(ValidationMessage::notFound);
    }
}
