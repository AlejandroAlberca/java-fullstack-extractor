package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.exception.ExceptionInfo;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders an exception catalog as Markdown for documentation.
 * Output: {@code api-spec-errors-catalog.md}
 *
 * Contains:
 * - Summary index table (all exceptions with codes, statuses, handlers)
 * - Per-exception details (origin, handler, throw locations, context, diagnostic hints)
 * - Orphan exception warnings (exceptions with no handler → 500 errors)
 */
public final class ErrorCatalogRenderer {

    public static Path errorCatalogOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-errors-catalog.md");
    }

    public String render(List<ExceptionInfo> exceptions) {
        if (exceptions == null || exceptions.isEmpty()) {
            return renderNoExceptions();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ERROR CATALOG — Application Exception Reference\n\n");

        renderSummary(sb, exceptions);
        sb.append("\n");

        renderIndexTable(sb, exceptions);
        sb.append("\n");

        renderDomainMap(sb, exceptions);
        sb.append("\n");

        renderExceptionDetails(sb, exceptions);
        sb.append("\n");

        renderOrphanWarnings(sb, exceptions);

        return sb.toString();
    }

    private String renderNoExceptions() {
        return """
                # ERROR CATALOG — Application Exception Reference

                ⚠️ **No custom exceptions detected.**

                - No classes found in `*.exception.*` packages.
                - All error handling delegated to framework defaults.

                **Recommendation:** Consider defining custom exceptions for domain-specific errors.
                """;
    }

    private void renderSummary(StringBuilder sb, List<ExceptionInfo> exceptions) {
        sb.append("## Summary\n\n");
        long orphanCount = exceptions.stream().filter(ExceptionInfo::isOrphan).count();
        long serverErrorCount = exceptions.stream().filter(ExceptionInfo::isServerError).count();

        sb.append("- **Total custom exceptions:** ").append(exceptions.size()).append("\n");
        sb.append("- **With handlers:** ").append(exceptions.size() - orphanCount).append("\n");
        sb.append("- **Orphan (no handler):** ").append(orphanCount).append("\n");
        sb.append("- **Server errors (5xx):** ").append(serverErrorCount).append("\n");
    }

    private void renderIndexTable(StringBuilder sb, List<ExceptionInfo> exceptions) {
        sb.append("## Exception Index\n\n");
        sb.append("| Code | Exception | HTTP Status | Handler | Notes |\n");
        sb.append("|------|-----------|-------------|---------|-------|\n");

        // Group by domain, then sort within domain
        exceptions.stream()
                .sorted(Comparator.comparing(ExceptionInfo::domain)
                        .thenComparing(ExceptionInfo::errorCode))
                .forEach(exc -> {
                    String code = exc.getErrorCodeOrDefault();
                    String exceptionName = exc.exceptionClassName();
                    String status = exc.httpStatus() > 0 ? String.valueOf(exc.httpStatus()) : "–";
                    String handler = exc.hasHandler() ? "✓" : "✗ ORPHAN";
                    String notes = exc.isOrphan() ? "⚠️ No handler — defaults to 500" : "";

                    sb.append("| `").append(code).append("` | ").append(exceptionName).append(" | ")
                            .append(status).append(" | ").append(handler).append(" | ").append(notes)
                            .append(" |\n");
                });
    }

    private void renderDomainMap(StringBuilder sb, List<ExceptionInfo> exceptions) {
        sb.append("## Domain Map\n\n");

        Map<String, List<ExceptionInfo>> byDomain = exceptions.stream()
                .collect(Collectors.groupingBy(ExceptionInfo::domain));

        byDomain.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String domain = entry.getKey();
                    List<ExceptionInfo> domainExceptions = entry.getValue();

                    sb.append("### ").append(domain).append(" (").append(domainExceptions.size())
                            .append(" exception").append(domainExceptions.size() != 1 ? "s" : "").append(")\n\n");

                    domainExceptions.stream()
                            .sorted(Comparator.comparing(ExceptionInfo::errorCode))
                            .forEach(exc -> {
                                String code = exc.getErrorCodeOrDefault();
                                String status = exc.httpStatus() > 0 ? String.valueOf(exc.httpStatus()) : "500";
                                sb.append("- [`").append(code).append("`] **").append(exc.exceptionClassName())
                                        .append("** → HTTP `").append(status).append("`\n");
                            });

                    sb.append("\n");
                });
    }

    private void renderExceptionDetails(StringBuilder sb, List<ExceptionInfo> exceptions) {
        sb.append("## Exception Details\n\n");

        // Group by domain
        Map<String, List<ExceptionInfo>> byDomain = exceptions.stream()
                .collect(Collectors.groupingBy(ExceptionInfo::domain));

        byDomain.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String domain = entry.getKey();
                    List<ExceptionInfo> domainExceptions = entry.getValue();

                    sb.append("### Domain: `").append(domain).append("`\n\n");

                    domainExceptions.stream()
                            .sorted(Comparator.comparing(ExceptionInfo::errorCode))
                            .forEach(exc -> renderExceptionDetail(sb, exc));

                    sb.append("\n");
                });
    }

    /** Package-private so {@link ErrorCatalogIndexRenderer} can render a single exception standalone. */
    void renderExceptionDetail(StringBuilder sb, ExceptionInfo exc) {
        String code = exc.getErrorCodeOrDefault();

        sb.append("### [").append(code).append("] ").append(exc.exceptionClassName());
        sb.append(" — ").append(truncateMessage(exc.messageTemplate(), 80)).append("\n\n");

        // YAML metadata block (parseable)
        sb.append("```yaml\n");
        sb.append("id: ").append(code).append("\n");
        sb.append("domain: ").append(exc.domain()).append("\n");
        sb.append("exception_type: ").append(exc.exceptionClassName()).append("\n");
        sb.append("http_status: ").append(exc.httpStatus() > 0 ? exc.httpStatus() : "500").append("\n");
        sb.append("origin_class: ").append(exc.originClass()).append("\n");
        sb.append("origin_method: ").append(exc.originMethod()).append("\n");
        sb.append("line: ").append(exc.lineNumber() > 0 ? exc.lineNumber() : "unknown").append("\n");
        sb.append("message: \"").append(escapeYamlString(exc.messageTemplate())).append("\"\n");

        // Search keys: exception type + message excerpt + class name
        sb.append("search_keys:\n");
        sb.append("  - \"").append(escapeYamlString(exc.messageTemplate())).append("\"\n");
        sb.append("  - \"").append(exc.exceptionClassName()).append("\"\n");
        sb.append("  - \"").append(exc.originClass().substring(exc.originClass().lastIndexOf('.') + 1))
                .append(".").append(exc.originMethod()).append("\"\n");

        // HTTP status info
        if (exc.httpStatus() > 0) {
            sb.append("http_status_name: ");
            if (exc.httpStatus() == 404) sb.append("\"Not Found\"\n");
            else if (exc.httpStatus() == 400) sb.append("\"Bad Request\"\n");
            else if (exc.httpStatus() == 409) sb.append("\"Conflict\"\n");
            else if (exc.httpStatus() >= 500) sb.append("\"Server Error\"\n");
            else sb.append("\"").append(exc.httpStatus()).append("\"\n");
        }

        // Handler status
        sb.append("has_handler: ").append(exc.hasHandler() ? "true" : "false").append("\n");
        if (exc.exceptionHandlerClass() != null) {
            sb.append("handler: \"").append(exc.exceptionHandlerClass()).append("\"\n");
        }

        sb.append("```\n\n");

        // Narrative explanation (human-readable)
        sb.append("**What:** ").append(exc.exceptionClassName()).append(" — ")
                .append(exc.messageTemplate()).append("\n\n");

        sb.append("**Where:** `").append(exc.originClass()).append(".").append(exc.originMethod())
                .append("()` — line ").append(exc.lineNumber() > 0 ? exc.lineNumber() : "?").append("\n\n");

        if (exc.httpStatus() > 0) {
            sb.append("**HTTP Response:** `").append(exc.httpStatus());
            if (exc.hasHandler()) sb.append("` (handled by `").append(exc.exceptionHandlerClass()).append("`");
            else sb.append("` (default, no handler configured)");
            sb.append("\n\n");
        }

        // Throw locations
        if (!exc.throwLocations().isEmpty()) {
            sb.append("**Thrown from:** ");
            String locations = String.join(" | ", exc.throwLocations().stream()
                    .limit(3)
                    .map(loc -> "`" + loc + "`")
                    .collect(Collectors.toList()));
            sb.append(locations);
            if (exc.throwLocations().size() > 3) {
                sb.append(" | (+").append(exc.throwLocations().size() - 3).append(" more)");
            }
            sb.append("\n\n");
        }

        // Diagnostic command
        sb.append("**Diagnostic:** ");
        String grepTarget = extractGrepTarget(exc.messageTemplate());
        sb.append("`grep -i \\\"").append(grepTarget).append("\\\" server.log`\n\n");

        // Stack trace anchor
        sb.append("**Stack anchor:** `at ").append(exc.originClass()).append(".")
                .append(exc.originMethod()).append("(").append(exc.originClass().substring(exc.originClass().lastIndexOf('.') + 1))
                .append(".java:").append(exc.lineNumber() > 0 ? exc.lineNumber() : "?").append(")`\n\n");

        // Domain reference
        sb.append("**Domain:** ").append(exc.domain()).append(" — see Domain Map\n\n");

        sb.append("---\n\n");
    }

    private String truncateMessage(String message, int maxLength) {
        if (message == null || message.length() <= maxLength) return message;
        return message.substring(0, maxLength) + "...";
    }

    private String escapeYamlString(String str) {
        if (str == null) return "";
        return str.replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String extractGrepTarget(String message) {
        if (message == null || message.isEmpty()) return "";
        // Extract first meaningful part (first 50 chars or up to first punctuation)
        int maxLen = Math.min(50, message.length());
        String target = message.substring(0, maxLen);
        // Remove trailing special chars
        return target.replaceAll("[.,:;!?]\\s*$", "");
    }

    private void renderOrphanWarnings(StringBuilder sb, List<ExceptionInfo> exceptions) {
        List<ExceptionInfo> orphans = exceptions.stream()
                .filter(ExceptionInfo::isOrphan)
                .collect(Collectors.toList());

        if (!orphans.isEmpty()) {
            sb.append("## ⚠️ Orphan Exceptions (No Handler)\n\n");
            sb.append("The following exceptions have **no `@ExceptionHandler`** and will surface as **HTTP 500** errors:\n\n");

            for (ExceptionInfo orphan : orphans) {
                sb.append("- **").append(orphan.getErrorCodeOrDefault()).append("** — `")
                        .append(orphan.exceptionClassName()).append("`\n");
                sb.append("  - Thrown from: `").append(orphan.originClass()).append("`\n");
                if (!orphan.throwLocations().isEmpty()) {
                    sb.append("  - Locations: ").append(String.join(", ", orphan.throwLocations())).append("\n");
                }
                sb.append("  - **Action:** Add `@ExceptionHandler` in `@RestControllerAdvice`\n\n");
            }
        }
    }
}
