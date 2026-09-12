package com.devmanchego.contextextractor.common;

import com.devmanchego.contextextractor.frontend.FrontendFramework;
import com.devmanchego.contextextractor.jsp.JspFormValidationExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 00 wired both "strategy factory" dispatch points for {@link FrontendFramework#JSP_JQUERY}
 * to an honest no-op, since no extractor existed yet. Phase 02 built the real requiredness
 * extractor for form validation — this factory must now route to it, not the placeholder.
 * Template-event extraction (business triggers) stays a no-op: it needs JavaScript analysis,
 * which is Phase 03's job, not Phase 02's markup-only one.
 */
class JspJQueryFactoryWiringTest {

    @Test
    void formValidationExtractorFactory_routesJspJQueryToTheRealExtractor() {
        FormValidationExtractorStrategy strategy = FormValidationExtractorFactory.createFor(FrontendFramework.JSP_JQUERY);

        assertInstanceOf(JspFormValidationExtractor.class, strategy);
    }

    @Test
    void templateEventExtractorFactory_routesJspJQueryToTheRealExtractor() {
        TemplateEventExtractorStrategy strategy = TemplateEventExtractorFactory.createFor(FrontendFramework.JSP_JQUERY);

        assertInstanceOf(com.devmanchego.contextextractor.jsp.JspTemplateEventExtractor.class, strategy);
        assertTrue(strategy.extract("anything.jsp", java.util.Map.of()).isEmpty());
    }

    @Test
    void noOpTemplateEventExtractor_neverThrows() {
        assertDoesNotThrow(() -> new NoOpTemplateEventExtractor().extract(null, null));
    }
}
