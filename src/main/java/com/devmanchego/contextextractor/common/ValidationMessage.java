package com.devmanchego.contextextractor.common;

/**
 * The user-facing error message associated with one form validator, plus the outcome of
 * trying to find it (see {@link ValidationMessageStatus}).
 *
 * <p>Not produced yet by any extractor as of this contract's introduction — this type
 * exists so the future message-resolution step (correlating a validator with its template
 * error block and, when i18n is involved, the translation catalog) has a fixed shape to
 * return, without requiring another change to {@link FormValidationExtractorStrategy}.
 *
 * <p>Exactly one of {@code text} / {@code i18nKey} is meaningful per status:
 * {@link ValidationMessageStatus#RESOLVED} carries both; {@link ValidationMessageStatus#KEY_UNRESOLVED}
 * carries only {@code i18nKey}; {@link ValidationMessageStatus#LITERAL} carries only {@code text};
 * {@link ValidationMessageStatus#NOT_FOUND} carries neither.
 */
public record ValidationMessage(ValidationMessageStatus status, String text, String i18nKey) {

    public static ValidationMessage resolved(String text, String i18nKey) {
        return new ValidationMessage(ValidationMessageStatus.RESOLVED, text, i18nKey);
    }

    public static ValidationMessage keyUnresolved(String i18nKey) {
        return new ValidationMessage(ValidationMessageStatus.KEY_UNRESOLVED, null, i18nKey);
    }

    public static ValidationMessage literal(String text) {
        return new ValidationMessage(ValidationMessageStatus.LITERAL, text, null);
    }

    public static ValidationMessage notFound() {
        return new ValidationMessage(ValidationMessageStatus.NOT_FOUND, null, null);
    }
}
