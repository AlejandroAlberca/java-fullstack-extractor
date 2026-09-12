package com.devmanchego.contextextractor.common;
import com.devmanchego.contextextractor.jsp.JspTemplateEventExtractor;

import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.angular.template.AngularTemplateEventExtractor;
import com.devmanchego.contextextractor.react.template.ReactTemplateEventExtractor;
import com.devmanchego.contextextractor.vue.template.VueTemplateEventExtractor;

/**
 * Factory for creating framework-specific template event extractors.
 *
 * <p>Dispatches to the appropriate extractor implementation based on the detected framework.
 */
public final class TemplateEventExtractorFactory {

    private TemplateEventExtractorFactory() {}

    /**
     * Creates a template event extractor for the given framework.
     *
     * @param framework the detected frontend framework
     * @return extractor instance; never null
     */
    public static TemplateEventExtractorStrategy createFor(FrontendFramework framework) {
        return switch (framework) {
            case ANGULAR -> new AngularTemplateEventExtractor();
            case REACT -> new ReactTemplateEventExtractor();
            case VUE2, VUE3 -> new VueTemplateEventExtractor();
            case NEXTJS, NUXT -> new ReactTemplateEventExtractor(); // Next.js uses React patterns
            // JSP + jQuery: element events in the view's own bundle closure (Phase 06).
            case JSP_JQUERY -> new JspTemplateEventExtractor();
            case UNKNOWN -> new ReactTemplateEventExtractor();      // Default fallback
        };
    }
}
