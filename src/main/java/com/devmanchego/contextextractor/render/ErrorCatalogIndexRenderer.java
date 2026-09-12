package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.exception.ExceptionInfo;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders the per-element index + detail documents for the exception catalog, mirroring
 * {@code ErrorCatalogRenderer}'s "Exception Details" section but split so an AI assistant
 * can load a single exception instead of the whole catalog.
 */
public final class ErrorCatalogIndexRenderer {

    private final ErrorCatalogRenderer inner = new ErrorCatalogRenderer();

    /** Stable per-exception slug, shared between the category index and its detail file name. */
    public String exceptionSlug(ExceptionInfo exc) {
        String raw = exc.getErrorCodeOrDefault() + "-" + exc.exceptionClassName();
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * Renders {@code index-spec-error-catalog.md}: a listing of every exception (grouped by
     * domain, same grouping as the full catalog) linking to its own detail document under
     * {@code detailed_exceptions/<slug>.md}.
     */
    public String renderCategoryIndex(List<ExceptionInfo> exceptions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Error Catalog Index\n\n");

        if (exceptions.isEmpty()) {
            sb.append("*No custom exceptions detected.*\n");
            return sb.toString();
        }

        long orphanCount = exceptions.stream().filter(ExceptionInfo::isOrphan).count();
        sb.append("- **Total custom exceptions:** ").append(exceptions.size()).append("\n");
        sb.append("- **Orphan (no handler):** ").append(orphanCount).append("\n\n");

        Map<String, List<ExceptionInfo>> byDomain = exceptions.stream()
                .collect(Collectors.groupingBy(ExceptionInfo::domain));

        byDomain.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    sb.append("## ").append(entry.getKey()).append("\n\n");
                    entry.getValue().stream()
                            .sorted(Comparator.comparing(ExceptionInfo::errorCode))
                            .forEach(exc -> {
                                String status = exc.httpStatus() > 0 ? String.valueOf(exc.httpStatus()) : "500";
                                sb.append("- [`").append(exc.getErrorCodeOrDefault()).append("`] ")
                                  .append("[").append(exc.exceptionClassName()).append("](./detailed_exceptions/")
                                  .append(exceptionSlug(exc)).append(".md) → HTTP `").append(status).append("`")
                                  .append(exc.isOrphan() ? " ⚠️ ORPHAN" : "").append("\n");
                            });
                    sb.append("\n");
                });

        return sb.toString();
    }

    /** Renders one exception as a standalone document for {@code detailed_exceptions/<slug>.md}. */
    public String renderExceptionDetail(ExceptionInfo exc) {
        return renderExceptionDetail(exc, "");
    }

    /**
     * @param relatedSection pre-rendered {@code ## Related} block to append (may be empty).
     */
    public String renderExceptionDetail(ExceptionInfo exc, String relatedSection) {
        StringBuilder sb = new StringBuilder();
        inner.renderExceptionDetail(sb, exc);
        if (relatedSection != null && !relatedSection.isBlank()) {
            sb.append("\n").append(relatedSection);
        }
        return sb.toString();
    }
}
