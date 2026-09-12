package com.devmanchego.contextextractor.angular.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads {@code @angular/localize} XLIFF translation catalogs (the {@code ng extract-i18n}
 * output) and flattens them to the same {@code key → message} shape {@link AngularI18nCatalog}
 * uses for ngx-translate JSON.
 *
 * <p><b>Formats:</b> both XLIFF 1.2 ({@code <trans-unit id><source>/<target>}) and XLIFF 2.0
 * ({@code <unit id><segment><source>/<target>}) are recognised, matched by element local name so
 * namespace prefixes do not matter. A unit's {@code <target>} wins when present and non-blank;
 * otherwise the {@code <source>} text is used, so an untranslated extraction file still yields
 * readable messages.
 *
 * <p><b>Keys:</b> the {@code id} attribute is used verbatim. In practice this is only useful for
 * units whose id was set explicitly in the template via {@code i18n="@@myId"} — Angular otherwise
 * generates a content hash that cannot be mapped back to a template location without
 * reimplementing its hashing, so those units load into the catalog but nothing looks them up.
 *
 * <p><b>Inline markup:</b> placeholder elements ({@code <x/>}, {@code <ph>}) carry no text and so
 * contribute nothing; the surrounding text is preserved with whitespace collapsed.
 *
 * <p>Never throws: an unreadable or malformed file is logged and skipped.
 */
final class XliffCatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(XliffCatalogLoader.class);

    private static final int MAX_WALK_DEPTH = 12;
    private static final List<String> XLIFF_EXTENSIONS = List.of(".xlf", ".xlf2", ".xliff");
    /** Used when a file declares no language at all and its name carries no locale suffix. */
    private static final String UNKNOWN_LOCALE = "source";

    private XliffCatalogLoader() {}

    /** One parsed catalog file. */
    record ParsedFile(String locale, Map<String, String> messages) {}

    /** The chosen primary locale and its merged messages; empty when no XLIFF file was found. */
    record Result(String locale, Map<String, String> messages) {}

    static Optional<Result> load(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Optional.empty();

        List<Path> files = discoverXliffFiles(projectRoot);
        if (files.isEmpty()) return Optional.empty();

        Map<String, List<ParsedFile>> byLocale = new LinkedHashMap<>();
        for (Path file : files) {
            parseFile(file).ifPresent(parsed ->
                    byLocale.computeIfAbsent(parsed.locale(), k -> new ArrayList<>()).add(parsed));
        }
        if (byLocale.isEmpty()) return Optional.empty();

        String primary = AngularI18nCatalog.pickPrimaryLocale(byLocale.keySet());
        Map<String, String> merged = new LinkedHashMap<>();
        for (ParsedFile parsed : byLocale.get(primary)) {
            parsed.messages().forEach(merged::putIfAbsent);
        }
        if (merged.isEmpty()) return Optional.empty();

        log.info("XliffCatalogLoader: locale '{}', {} unit(s) from {} file(s).",
                primary, merged.size(), byLocale.get(primary).size());
        return Optional.of(new Result(primary, merged));
    }

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    private static List<Path> discoverXliffFiles(Path projectRoot) {
        try (Stream<Path> walk = Files.walk(projectRoot, MAX_WALK_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> hasXliffExtension(p.getFileName().toString()))
                    .filter(p -> !isUnderExcludedDir(projectRoot, p))
                    .toList();
        } catch (IOException e) {
            log.debug("Cannot walk project for XLIFF files: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean hasXliffExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return XLIFF_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static boolean isUnderExcludedDir(Path root, Path file) {
        for (Path segment : root.relativize(file)) {
            String name = segment.toString();
            if (name.equals("node_modules") || name.equals("dist") || name.equals(".angular")) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    private static Optional<ParsedFile> parseFile(Path file) {
        try {
            Document doc = newSecureBuilder().parse(file.toFile());
            doc.getDocumentElement().normalize();

            Map<String, String> messages = new LinkedHashMap<>();
            for (Element unit : elementsByLocalName(doc.getDocumentElement(), "trans-unit", "unit")) {
                String id = unit.getAttribute("id");
                if (id == null || id.isBlank()) continue;

                String text = firstNonBlankText(unit, "target")
                        .or(() -> firstNonBlankText(unit, "source"))
                        .orElse(null);
                if (text != null) messages.putIfAbsent(id, text);
            }
            if (messages.isEmpty()) return Optional.empty();

            return Optional.of(new ParsedFile(detectLocale(doc, file), messages));
        } catch (Exception e) {
            log.debug("Cannot parse XLIFF file {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private static DocumentBuilder newSecureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Harden against XXE: these files come from an arbitrary scanned project.
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(dbf, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(dbf, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        return dbf.newDocumentBuilder();
    }

    /** Not every JAXP implementation knows every hardening feature; a missing one is not fatal. */
    private static void trySetFeature(DocumentBuilderFactory dbf, String feature, boolean value) {
        try {
            dbf.setFeature(feature, value);
        } catch (ParserConfigurationException e) {
            log.debug("XML parser does not support feature {} — continuing.", feature);
        }
    }

    /**
     * Locale precedence: the translated language if the file declares one, else the source
     * language, else a locale suffix in the file name ({@code messages.es.xlf} → {@code es}).
     */
    private static String detectLocale(Document doc, Path file) {
        Element root = doc.getDocumentElement();
        String trgLang = root.getAttribute("trgLang");           // XLIFF 2.0
        if (!trgLang.isBlank()) return trgLang;

        List<Element> fileElements = elementsByLocalName(root, "file");
        if (!fileElements.isEmpty()) {
            String targetLanguage = fileElements.get(0).getAttribute("target-language");  // XLIFF 1.2
            if (!targetLanguage.isBlank()) return targetLanguage;
            String sourceLanguage = fileElements.get(0).getAttribute("source-language");
            if (!sourceLanguage.isBlank()) return sourceLanguage;
        }

        String srcLang = root.getAttribute("srcLang");           // XLIFF 2.0
        if (!srcLang.isBlank()) return srcLang;

        return localeFromFileName(file.getFileName().toString());
    }

    /** {@code messages.es.xlf} → {@code es}; {@code messages.xlf} → {@link #UNKNOWN_LOCALE}. */
    private static String localeFromFileName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        String stem = lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
        int localeDot = stem.lastIndexOf('.');
        return localeDot > 0 ? stem.substring(localeDot + 1) : UNKNOWN_LOCALE;
    }

    // -----------------------------------------------------------------------
    // DOM helpers (local-name based, so namespace prefixes are irrelevant)
    // -----------------------------------------------------------------------

    private static Optional<String> firstNonBlankText(Element unit, String localName) {
        for (Element el : elementsByLocalName(unit, localName)) {
            String text = collapseWhitespace(textOf(el));
            if (!text.isBlank()) return Optional.of(text);
        }
        return Optional.empty();
    }

    private static List<Element> elementsByLocalName(Element root, String... localNames) {
        List<Element> found = new ArrayList<>();
        collectByLocalName(root, List.of(localNames), found);
        return found;
    }

    private static void collectByLocalName(Node node, List<String> localNames, List<Element> out) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (localNames.contains(localName(child))) {
                out.add((Element) child);
            }
            collectByLocalName(child, localNames, out);
        }
    }

    private static String localName(Node node) {
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** Concatenates all descendant text, so placeholder elements simply contribute nothing. */
    private static String textOf(Node node) {
        StringBuilder sb = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(child.getNodeValue());
                case Node.ELEMENT_NODE -> sb.append(textOf(child));
                default -> { /* comments, PIs — ignored */ }
            }
        }
        return sb.toString();
    }

    private static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
