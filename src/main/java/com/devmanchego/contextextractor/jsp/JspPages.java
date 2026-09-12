package com.devmanchego.contextextractor.jsp;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared "parse this page defensively" helper for the markup-extraction classes (field, label,
 * requiredness, section, authorisation) — each is invoked once per page with just a file path
 * string, the same signature {@code AngularFormFieldExtractor} and its siblings use, so each
 * independently locates the webapp root and (re-)composes the page. Not cached across calls:
 * matches the existing per-call, no-shared-state pattern those extractors already use, and
 * keeps each extractor class trivially independent to construct and test.
 */
final class JspPages {

    private JspPages() {}

    /**
     * @return the composed page, or {@code null} when the file doesn't exist, no
     *         {@code WEB-INF} ancestor could be located, or parsing failed — every case is
     *         logged at debug level and left for the caller to treat as "nothing extracted",
     *         never thrown.
     */
    static JspPage parseOrNull(JspFileParser parser, String componentFilePath, Logger log) {
        if (componentFilePath == null || componentFilePath.isBlank()) return null;
        try {
            Path file = Path.of(componentFilePath);
            if (!Files.isRegularFile(file)) {
                log.debug("Not a file, skipping: {}", componentFilePath);
                return null;
            }
            Path webappRoot = JspFileParser.locateWebappRoot(file);
            if (webappRoot == null) {
                log.debug("No WEB-INF ancestor found for {} — cannot compose the page.", componentFilePath);
                return null;
            }
            return parser.parse(file, webappRoot);
        } catch (Exception e) {
            log.debug("Could not parse {}: {}", componentFilePath, e.getMessage());
            return null;
        }
    }
}
