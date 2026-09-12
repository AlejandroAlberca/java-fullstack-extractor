package com.devmanchego.contextextractor.common;

import java.util.List;
import java.util.Map;

/**
 * Framework-agnostic strategy for extracting template event bindings (business triggers)
 * from components.
 *
 * <p>Each framework (Angular, React, Vue, etc.) implements this interface to identify
 * UI event bindings that trigger HTTP calls, producing documentation-friendly descriptions
 * of the application's user workflows.
 *
 * <p><b>Business Trigger</b> = a UI event binding (button click, form submit, etc.) whose
 * handler ultimately issues an HTTP request via an injected service.
 */
public interface TemplateEventExtractorStrategy {

    /**
     * Event record: trigger type + human-friendly description.
     * Example: trigger="click", description="Button 'Delete' → DELETE /api/v1/accounts/{id}"
     */
    record Event(String trigger, String description) {}

    /**
     * Extracts business-trigger events from a component.
     *
     * @param componentFilePath     absolute path to the component source file
     * @param httpMethodDescriptors service method name → {@code "VERB /url"} descriptor.
     *                              Used to identify which handlers trigger network calls.
     * @return distinct business-trigger events in document order. Empty list if no
     *         triggers found or framework does not support this feature.
     */
    List<Event> extract(String componentFilePath, Map<String, String> httpMethodDescriptors);
}
