package com.devmanchego.contextextractor.common;

import java.util.List;
import java.util.Map;

/**
 * Placeholder strategy for a framework whose template-event extraction hasn't been built yet
 * (currently: JSP + jQuery — see {@code java-fullstack-extractor} implementation phases 01–06).
 * Returns an empty result, which {@link TemplateEventExtractorStrategy}'s own contract already
 * documents as the legitimate outcome for "framework does not support this feature" — this is
 * that case made explicit, rather than borrowing an unrelated framework's extractor and risking
 * a false match.
 */
public final class NoOpTemplateEventExtractor implements TemplateEventExtractorStrategy {
    @Override
    public List<Event> extract(String componentFilePath, Map<String, String> httpMethodDescriptors) {
        return List.of();
    }
}
