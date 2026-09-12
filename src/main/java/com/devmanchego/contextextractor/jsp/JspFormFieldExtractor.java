package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.common.FieldLabel;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts form fields and their sections from a composed JSP view's markup tree (Phase 01),
 * mirroring the role {@code AngularFormFieldExtractor}/{@code ReactFormFieldExtractor}/
 * {@code VueFormFieldExtractor} play for their own frameworks.
 *
 * <p><b>Field identification:</b> every {@code <input>}, {@code <select>}, and
 * {@code <textarea>} carrying an {@code id} — the only identifier a jQuery handler can
 * reliably bind to ({@code $('#id')}), so it's the correlation key later phases (identifier
 * correlation) will join against.
 *
 * <p><b>Label resolution:</b> {@code <label for="id">...</label>} only — the sole mechanism
 * this framework's markup actually uses (unlike React/Vue, no aria-label or nearest-preceding-
 * label fallback is attempted here; inventing one without evidence it's needed would be
 * guessing, not extracting). A field with no matching {@code <label for>} is still emitted,
 * with a {@code null} label — see {@link #extract} — never silently dropped.
 *
 * <p><b>Sections:</b> heading elements ({@code <h1>}–{@code <h6>}) and Bootstrap-style
 * {@code .panel-heading} blocks, in document order — the structural landmarks a reader (or an
 * AI assistant) would use to understand what part of the page a field belongs to.
 */
public final class JspFormFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(JspFormFieldExtractor.class);

    private static final Set<String> FIELD_TAGS = Set.of("input", "select", "textarea");
    private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    private final JspFileParser parser = new JspFileParser();

    /**
     * @param componentFilePath absolute path to the composed JSP view (as recorded in
     *                          {@code ComponentInfo.getFilePath()} by route reconstruction)
     * @return field id → resolved label. Every {@code <input>/<select>/<textarea>} carrying an
     *         {@code id} is a key in this map, even when its value is {@code null} because no
     *         {@code <label for>} was found — a missing key means "not a field", a null value
     *         means "a field with no resolvable label"; the two are never conflated.
     */
    public Map<String, FieldLabel> extract(String componentFilePath) {
        JspPage page = JspPages.parseOrNull(parser, componentFilePath, log);
        if (page == null) return Map.of();

        Map<String, FieldLabel> labels = new LinkedHashMap<>();
        for (Element control : page.document().getAllElements()) {
            if (!FIELD_TAGS.contains(control.tagName().toLowerCase(java.util.Locale.ROOT))) continue;
            String id = control.attr("id");
            if (id.isBlank() || labels.containsKey(id)) continue;

            labels.put(id, resolveLabel(page.document(), id));
        }
        return labels;
    }

    /**
     * Heading and panel-header text, in document order, deduplicated by consecutive repeats
     * (a heading immediately followed by an identical one — common when a fragment's own
     * heading and a wrapping panel's heading say the same thing — collapses to one entry).
     */
    public List<String> extractSections(String componentFilePath) {
        JspPage page = JspPages.parseOrNull(parser, componentFilePath, log);
        if (page == null) return List.of();

        List<String> sections = new ArrayList<>();
        for (Element el : page.document().getAllElements()) {
            String tag = el.tagName().toLowerCase(java.util.Locale.ROOT);
            boolean isHeading = HEADING_TAGS.contains(tag);
            boolean isPanelHeading = el.hasClass("panel-heading");
            if (!isHeading && !isPanelHeading) continue;
            // A panel-heading that itself contains a heading element would otherwise be
            // recorded twice (once for the panel-heading div, once for the h3 inside it) —
            // skip the outer one when it wraps a heading, keeping only the more specific text.
            if (isPanelHeading && !el.select(String.join(",", HEADING_TAGS)).isEmpty()) continue;

            String text = el.ownText().isBlank() ? el.text() : el.ownText();
            text = text.strip();
            if (text.isBlank()) continue;
            if (!sections.isEmpty() && sections.get(sections.size() - 1).equals(text)) continue;
            sections.add(text);
        }
        return sections;
    }

    private FieldLabel resolveLabel(Document document, String id) {
        for (Element label : document.select("label[for]")) {
            if (id.equals(label.attr("for"))) {
                String text = cleanLabelText(label);
                return text.isBlank() ? null : FieldLabel.ofLiteral(text);
            }
        }
        return null;
    }

    /**
     * A label's full text, normalised: collapsed whitespace, and a trailing separator
     * ({@code ":"} or {@code "*"} — the observed conventions for "here comes the control" and
     * "this is required") stripped.
     *
     * <p>Deliberately does <em>not</em> try to strip a required-field marker's own text: in
     * this framework's target profile, a marker class such as {@code champ-obligatoire} wraps
     * the field's actual name (e.g. {@code <span class="champ-obligatoire">Pôle Acheteur</span>})
     * rather than a separate throwaway symbol — stripping that span would strip the label
     * itself. {@link JspFormValidationExtractor} reads the same marker class to derive
     * requiredness independently; this method only normalises punctuation.
     */
    private String cleanLabelText(Element label) {
        String text = label.text().replaceAll("\\s+", " ").strip();
        return text.replaceAll("[:*]\\s*$", "").strip();
    }
}
