package com.devmanchego.contextextractor.common;

/**
 * Outcome of trying to associate a form validator with its user-facing error message.
 *
 * <p>Kept as an explicit enum (rather than collapsing straight to a display string) so
 * callers can distinguish causes that call for different fixes: {@link #KEY_UNRESOLVED}
 * means the i18n key was found but the catalog is missing it (a translation gap);
 * {@link #NOT_FOUND} means no error block could be correlated to the validator at all
 * (an extraction gap). Conflating the two would make it impossible to measure or triage
 * coverage across a project.
 */
public enum ValidationMessageStatus {
    /** An i18n key was found in the template and resolved against the catalog. */
    RESOLVED,
    /** An i18n key was found in the template, but it has no entry in the catalog. */
    KEY_UNRESOLVED,
    /** A hardcoded (non-i18n) message text was found in the template. */
    LITERAL,
    /** No error block could be correlated to this validator. */
    NOT_FOUND
}
