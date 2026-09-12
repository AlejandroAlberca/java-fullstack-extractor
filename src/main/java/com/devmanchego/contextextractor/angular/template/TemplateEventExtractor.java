package com.devmanchego.contextextractor.angular.template;

/**
 * @deprecated Use {@link AngularTemplateEventExtractor} instead.
 * This class is retained for backwards compatibility.
 */
@Deprecated(since = "1.1.0", forRemoval = true)
public final class TemplateEventExtractor extends AngularTemplateEventExtractor {

    /**
     * For backwards compatibility: expose the Event record type that external code may reference.
     */
    public record Event(String trigger, String description) {}
}
