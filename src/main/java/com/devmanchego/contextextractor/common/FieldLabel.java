package com.devmanchego.contextextractor.common;

/**
 * A form-field label resolved by a framework-specific extractor (Angular, React, Vue, ...):
 * at least one of {@code literal} or {@code i18nKey} is non-null.
 *
 * <p>Both are set for {@code @angular/localize}, where the template holds the source message
 * inline ({@code <label i18n="@@id">Email</label>}): the key resolves against a translation
 * catalog, and the inline text remains available as a fallback when it does not. Every other
 * i18n spelling (ngx-translate/transloco) carries only the key; plain frameworks with no i18n
 * key detection at all (React, Vue) carry only the literal.
 */
public record FieldLabel(String literal, String i18nKey) {
    public static FieldLabel ofLiteral(String text) { return new FieldLabel(text, null); }
    public static FieldLabel ofKey(String key) { return new FieldLabel(null, key); }
    /** A key whose source text is written inline in the template (@angular/localize). */
    public static FieldLabel ofKeyWithSource(String key, String sourceText) {
        return new FieldLabel(sourceText, key);
    }
    public boolean hasI18nKey() { return i18nKey != null; }
}
