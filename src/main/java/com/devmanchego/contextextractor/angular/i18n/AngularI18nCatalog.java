package com.devmanchego.contextextractor.angular.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Loads ngx-translate / transloco JSON translation catalogs from an Angular project and
 * resolves i18n keys to their message text for a single primary locale.
 *
 * <p><b>Discovery:</b> scans the project tree for {@code *.json} files whose parent directory
 * is named {@code i18n} (case-insensitive), excluding {@code node_modules} and build output.
 * This matches the conventional layout {@code src/assets/i18n/<locale>.json}.
 *
 * <p><b>Primary locale:</b> catalogs are grouped by filename stem (the locale, e.g. {@code en},
 * {@code es}, {@code en-US}). One locale is chosen deterministically — English variants are
 * preferred, otherwise the alphabetically first — since the generated documentation shows a
 * single human-readable message per field, not every translation.
 *
 * <p><b>Key resolution:</b> nested JSON objects are flattened into dot-paths
 * ({@code {"employee":{"email":"Email"}}} → {@code employee.email}), and flat dotted keys are
 * kept verbatim, so {@code resolve("employee.email")} works for both layouts.
 *
 * <p><b>Limitations:</b> transloco <i>scoped</i> catalogs (subfolder per scope) are merged flat
 * without a scope prefix, so a scoped key {@code scope.key} only resolves if the JSON itself is
 * nested that way. ICU plurals / interpolation placeholders are returned as-is.
 */
public final class AngularI18nCatalog {

    private static final Logger log = LoggerFactory.getLogger(AngularI18nCatalog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> PREFERRED_LOCALES = List.of("en", "en-us", "en-gb", "en_us");
    private static final int MAX_WALK_DEPTH = 12;

    private final String primaryLocale;
    private final Map<String, String> messages; // flattened dot-path key → message

    private AngularI18nCatalog(String primaryLocale, Map<String, String> messages) {
        this.primaryLocale = primaryLocale;
        this.messages = messages;
    }

    /** An empty catalog that resolves nothing — used when no i18n files are present. */
    public static AngularI18nCatalog empty() {
        return new AngularI18nCatalog(null, Map.of());
    }

    /**
     * Builds a catalog by scanning {@code projectRoot}, trying ngx-translate/transloco JSON
     * first and falling back to {@code @angular/localize} XLIFF (see {@link XliffCatalogLoader})
     * when no usable JSON catalog is present. JSON takes precedence so a project already
     * supported keeps behaving identically; only projects with no JSON catalog reach the XLIFF
     * path. Never throws: on any I/O problem it logs and returns whatever was loaded so far
     * (possibly empty).
     */
    public static AngularI18nCatalog load(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return empty();

        Map<String, List<Path>> byLocale = discoverCatalogFiles(projectRoot);
        if (!byLocale.isEmpty()) {
            String primary = pickPrimaryLocale(byLocale.keySet());
            Map<String, String> flat = new LinkedHashMap<>();
            for (Path file : byLocale.get(primary)) {
                try {
                    JsonNode root = MAPPER.readTree(Files.readString(file));
                    flatten("", root, flat);
                } catch (IOException e) {
                    log.debug("Cannot read i18n catalog {}: {}", file, e.getMessage());
                }
            }
            if (!flat.isEmpty()) {
                log.info("AngularI18nCatalog: locale '{}', {} key(s) from {} file(s).",
                        primary, flat.size(), byLocale.get(primary).size());
                return new AngularI18nCatalog(primary, flat);
            }
        }

        return XliffCatalogLoader.load(projectRoot)
                .map(result -> new AngularI18nCatalog(result.locale(), result.messages()))
                .orElseGet(AngularI18nCatalog::empty);
    }

    /** @return the resolved message for {@code key}, or empty if the key is unknown. */
    public Optional<String> resolve(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(messages.get(key));
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public String primaryLocale() {
        return primaryLocale;
    }

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    private static Map<String, List<Path>> discoverCatalogFiles(Path projectRoot) {
        Map<String, List<Path>> byLocale = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(projectRoot, MAX_WALK_DEPTH)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .filter(p -> {
                    Path parent = p.getParent();
                    return parent != null && parent.getFileName() != null
                            && parent.getFileName().toString().equalsIgnoreCase("i18n");
                })
                .filter(p -> !isUnderExcludedDir(projectRoot, p))
                .forEach(p -> {
                    String locale = stripExtension(p.getFileName().toString());
                    byLocale.computeIfAbsent(locale, k -> new ArrayList<>()).add(p);
                });
        } catch (IOException e) {
            log.debug("Cannot walk project for i18n files: {}", e.getMessage());
        }
        return byLocale;
    }

    private static boolean isUnderExcludedDir(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path segment : rel) {
            String name = segment.toString();
            if (name.equals("node_modules") || name.equals("dist") || name.equals(".angular")) {
                return true;
            }
        }
        return false;
    }

    /** Package-visible so {@link XliffCatalogLoader} applies the same locale-choice policy. */
    static String pickPrimaryLocale(java.util.Set<String> locales) {
        for (String preferred : PREFERRED_LOCALES) {
            for (String locale : locales) {
                if (locale.equalsIgnoreCase(preferred)) return locale;
            }
        }
        return locales.stream().min(Comparator.naturalOrder()).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Flattening
    // -----------------------------------------------------------------------

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flatten(key, entry.getValue(), out);
            });
        } else if (node.isValueNode()) {
            // Keep the deepest occurrence deterministic: first write wins is fine since keys are unique per file.
            out.putIfAbsent(prefix, node.asText());
        }
        // Arrays are not valid translation values in ngx-translate/transloco; ignored.
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
