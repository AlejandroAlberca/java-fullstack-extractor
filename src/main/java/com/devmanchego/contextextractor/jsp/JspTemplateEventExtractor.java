package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.common.TemplateEventExtractorStrategy;

import java.util.List;
import java.util.Map;

/**
 * JSP + jQuery business triggers: an element event bound in the view's own bundle closure
 * ({@code $('#btn').on('click', …)}, {@code page.btnId.click(page.save)}) whose handler reaches an
 * HTTP request — directly, or one hop through a function it invokes. See
 * {@link DomIdentifierCorrelator} for the correlation rules.
 *
 * <p>{@code httpMethodDescriptors} is not consulted: it is keyed by function name across every
 * extracted service, and jQuery function names ({@code init}, {@code save}) repeat from page to
 * page. The requests are resolved from this view's own bundle closure instead.
 */
public final class JspTemplateEventExtractor implements TemplateEventExtractorStrategy {

    private final DomIdentifierCorrelator correlator = new DomIdentifierCorrelator();

    @Override
    public List<Event> extract(String componentFilePath, Map<String, String> httpMethodDescriptors) {
        try {
            return correlator.correlate(componentFilePath).events();
        } catch (Exception e) {
            return List.of();
        }
    }
}
