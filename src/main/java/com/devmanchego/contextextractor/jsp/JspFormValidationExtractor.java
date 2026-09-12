package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.common.FormValidationExtractorStrategy;
import com.devmanchego.contextextractor.common.ValidatorInfo;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives requiredness from a composed JSP view's markup (Phase 01) — the one validation rule
 * this framework's markup can actually declare. Unlike a typed frontend (Angular's
 * {@code Validators.required}, a Zod/Yup schema), a jQuery form's other validation rules
 * — length, pattern, cross-field — live in imperative JavaScript, not the markup, and are out
 * of scope here (that's a JavaScript-analysis concern, not a markup one).
 *
 * <p>Two independent signals, either sufficient on its own:
 * <ul>
 *   <li>The HTML5 {@code required} attribute on the control itself.</li>
 *   <li>A CSS class on the control's {@code <label for="id">} (or a descendant of it) whose
 *       name suggests a required-field marker — {@code mandatory}, {@code required}, or
 *       {@code obligatoire} (the French convention observed in this framework's target
 *       profile), matched as a case-insensitive substring so {@code champ-obligatoire} and
 *       {@code mandatory-field} both match without hardcoding one application's exact class
 *       names.</li>
 * </ul>
 */
public final class JspFormValidationExtractor implements FormValidationExtractorStrategy {

    private static final Logger log = LoggerFactory.getLogger(JspFormValidationExtractor.class);

    private static final Set<String> FIELD_TAGS = Set.of("input", "select", "textarea");
    private static final Pattern REQUIRED_CLASS_HINT = Pattern.compile("(?i)mandatory|required|obligatoire");

    private final JspFileParser parser = new JspFileParser();

    @Override
    public Map<String, List<ValidatorInfo>> extract(String componentFilePath) {
        JspPage page = JspPages.parseOrNull(parser, componentFilePath, log);
        if (page == null) return Map.of();

        Map<String, List<ValidatorInfo>> result = new LinkedHashMap<>();
        for (Element control : page.document().getAllElements()) {
            if (!FIELD_TAGS.contains(control.tagName().toLowerCase(Locale.ROOT))) continue;
            String id = control.attr("id");
            if (id.isBlank() || result.containsKey(id)) continue;

            if (isRequired(page.document(), control, id)) {
                List<ValidatorInfo> validators = new ArrayList<>();
                validators.add(ValidatorInfo.simple("required"));
                result.put(id, validators);
            }
        }
        return result;
    }

    private boolean isRequired(Document document, Element control, String id) {
        if (control.hasAttr("required")) return true;

        for (Element label : document.select("label[for]")) {
            if (!id.equals(label.attr("for"))) continue;
            if (hasRequiredMarkerClass(label)) return true;
            for (Element descendant : label.getAllElements()) {
                if (hasRequiredMarkerClass(descendant)) return true;
            }
        }
        return false;
    }

    private boolean hasRequiredMarkerClass(Element el) {
        for (String cls : el.classNames()) {
            if (REQUIRED_CLASS_HINT.matcher(cls).find()) return true;
        }
        return false;
    }
}
