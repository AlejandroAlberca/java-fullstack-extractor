package com.devmanchego.contextextractor.common;

import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.angular.template.AngularFormValidationExtractor;
import com.devmanchego.contextextractor.jsp.JspFormValidationExtractor;
import com.devmanchego.contextextractor.react.template.ReactFormValidationExtractor;
import com.devmanchego.contextextractor.vue.template.VueFormValidationExtractor;

/**
 * Factory for creating framework-specific form validation extractors.
 *
 * <p>Dispatches to the appropriate extractor implementation based on the detected framework.
 */
public final class FormValidationExtractorFactory {

    private FormValidationExtractorFactory() {}

    /**
     * Creates a form validation extractor for the given framework.
     *
     * @param framework the detected frontend framework
     * @return extractor instance; never null
     */
    public static FormValidationExtractorStrategy createFor(FrontendFramework framework) {
        return switch (framework) {
            case ANGULAR -> new AngularFormValidationExtractor();
            case REACT -> new ReactFormValidationExtractor();
            case VUE2, VUE3 -> new VueFormValidationExtractor();
            case NEXTJS, NUXT -> new ReactFormValidationExtractor(); // Next.js uses React patterns
            case JSP_JQUERY -> new JspFormValidationExtractor();
            case UNKNOWN -> new ReactFormValidationExtractor();      // Default fallback
        };
    }
}
