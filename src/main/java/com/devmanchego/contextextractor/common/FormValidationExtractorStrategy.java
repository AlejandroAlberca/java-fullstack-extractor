package com.devmanchego.contextextractor.common;

import java.util.List;
import java.util.Map;

/**
 * Framework-agnostic strategy for extracting form validation rules from components.
 *
 * <p>Each framework (Angular, React, Vue, etc.) implements this interface to parse
 * form control declarations and extract validation rules in a framework-specific way.
 *
 * <p>Return value is a map of control name → list of structured validators. {@code name}
 * on each {@link ValidatorInfo} is the canonical, argument-free key ("required", "minlength")
 * used to correlate with a template's error-message block; {@code rawText} is the
 * pre-formatted display string ("minLength(3)") renderers show today.
 */
public interface FormValidationExtractorStrategy {

    /**
     * Extracts form validation rules from a component file.
     *
     * @param componentFilePath absolute path to the component source file (.ts, .tsx, .vue, etc.)
     * @return map of control name → list of validators. Empty map if no form found or
     *         framework does not support this feature.
     */
    Map<String, List<ValidatorInfo>> extract(String componentFilePath);
}
