package com.devmanchego.contextextractor.render;

import java.text.Normalizer;
import java.util.HashMap;

import java.util.Map;

/**
 * Converts heading text into GitHub-Flavored Markdown anchor slugs.
 * Ensures uniqueness within a document by appending -1, -2, etc. when needed.
 */
public final class AnchorUtil {

    private final Map<String, Integer> seen = new HashMap<>();

    /** Produces a slug for use in cross-reference links: [text](#slug) */
    public String slug(String headingText) {
        String raw = toSlug(headingText);
        int count = seen.merge(raw, 1, Integer::sum);
        return count == 1 ? raw : raw + "-" + (count - 1);
    }

    /** Stateless version — does not track uniqueness. Use for known-unique headings. */
    public static String toSlug(String headingText) {
        if (headingText == null) return "";
        // Normalize unicode (NFD → strip combining marks)
        String normalized = Normalizer.normalize(headingText, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        // Lowercase, keep letters/digits/spaces/hyphens, convert spaces to hyphens
        return normalized.toLowerCase()
                .replaceAll("[^a-z0-9\\s\\-]", "")
                .trim()
                .replaceAll("\\s+", "-");
    }
}
