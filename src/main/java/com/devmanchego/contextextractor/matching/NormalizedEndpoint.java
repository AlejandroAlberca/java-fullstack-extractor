package com.devmanchego.contextextractor.matching;

import com.devmanchego.contextextractor.java.model.HttpVerb;

import java.util.Objects;

/**
 * Canonical endpoint representation used for matching.
 * Both the Angular HTTP call and the Java endpoint are converted to this form
 * before comparison (verb + path template with {param} placeholders).
 */
public record NormalizedEndpoint(HttpVerb verb, String pathTemplate) {

    public NormalizedEndpoint {
        Objects.requireNonNull(verb);
        Objects.requireNonNull(pathTemplate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NormalizedEndpoint that)) return false;
        return verb == that.verb && pathTemplate.equals(that.pathTemplate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(verb, pathTemplate);
    }
}
