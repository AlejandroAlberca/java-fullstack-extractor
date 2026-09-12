package com.devmanchego.contextextractor.angular.webpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans the Angular project directory for webpack configuration files and
 * extracts string constants defined via {@code webpack.DefinePlugin}.
 *
 * <p>Only entries whose value is a literal string wrapped in
 * {@code JSON.stringify('...')} or {@code JSON.stringify("...")} are extracted.
 * Entries whose value is a runtime expression (e.g.
 * {@code JSON.stringify(environment.SERVER_API_URL)}) are silently skipped.
 *
 * <p>Example input in {@code webpack.custom.js}:
 * <pre>{@code
 * new webpack.DefinePlugin({
 *   API_ROOT_V5: JSON.stringify('api/v5/'),
 *   I18N_HASH: JSON.stringify(languagesHash.hash),   // skipped — not a literal
 * });
 * }</pre>
 *
 * <p>Result: {@code {"API_ROOT_V5" → "api/v5/"}}.
 */
public final class WebpackConstantReader {

    private static final Logger log = LoggerFactory.getLogger(WebpackConstantReader.class);

    /** Max directory depth to search for webpack config files. */
    private static final int MAX_DEPTH = 4;

    /**
     * Matches: {@code UPPER_IDENTIFIER: JSON.stringify('literal')} or double-quoted variant.
     * Group 1 = constant name, Group 2 = string value.
     */
    private static final Pattern DEFINE_LITERAL = Pattern.compile(
            "([A-Z_][A-Z0-9_]*)\\s*:\\s*JSON\\.stringify\\(\\s*['\"]([^'\"]*)['\"]\\s*\\)");

    private WebpackConstantReader() {}

    /**
     * Reads all reachable webpack config files under {@code angularProjectPath}
     * (depth ≤ {@value #MAX_DEPTH}) and returns the merged map of constant name → value.
     * Later files override earlier ones if the same constant appears more than once.
     *
     * @param angularProjectPath root of the Angular project
     * @return possibly-empty map of webpack {@code DefinePlugin} string constants
     */
    public static Map<String, String> read(Path angularProjectPath) {
        Map<String, String> constants = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(angularProjectPath, MAX_DEPTH)) {
            files.filter(WebpackConstantReader::isWebpackConfigFile)
                 .filter(Files::isRegularFile)
                 .forEach(file -> extractFromFile(file, constants));
        } catch (IOException e) {
            log.debug("WebpackConstantReader: could not scan project tree — {}", e.getMessage());
        }
        if (!constants.isEmpty()) {
            log.info("WebpackConstantReader: extracted {} webpack constant(s): {}",
                    constants.size(), constants.keySet());
        } else {
            log.debug("WebpackConstantReader: no DefinePlugin literal constants found.");
        }
        return constants;
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static boolean isWebpackConfigFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.startsWith("webpack") && name.endsWith(".js");
    }

    private static void extractFromFile(Path file, Map<String, String> target) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            log.debug("WebpackConstantReader: cannot read {} — {}", file.getFileName(), e.getMessage());
            return;
        }
        Matcher m = DEFINE_LITERAL.matcher(content);
        int found = 0;
        while (m.find()) {
            String name  = m.group(1);
            String value = m.group(2);
            target.put(name, value);
            log.debug("WebpackConstantReader: {} = '{}' ({})", name, value, file.getFileName());
            found++;
        }
        if (found > 0) {
            log.info("WebpackConstantReader: {} constant(s) from {}", found, file.getFileName());
        }
    }
}
