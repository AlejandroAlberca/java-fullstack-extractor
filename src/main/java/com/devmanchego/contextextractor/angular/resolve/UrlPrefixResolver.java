package com.devmanchego.contextextractor.angular.resolve;

import com.devmanchego.contextextractor.angular.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves dynamic URL prefixes in Angular HTTP call templates.
 *
 * <p>URLs extracted from TypeScript may contain unresolved expression tokens such as:
 * <pre>
 *   /{applicationConfigService}{getEndpointFor}{API_ROOT_V5}account
 * </pre>
 * These tokens represent a TypeScript expression that the parser could not evaluate
 * statically. This resolver applies two layers to remove as many of them as possible:
 *
 * <ol>
 *   <li><b>Webpack constants (Layer 1):</b> replaces individual {@code {CONST_NAME}} tokens
 *       whose names appear as {@code webpack.DefinePlugin} string literals.
 *       Example: {@code {API_ROOT_V5}} → {@code api/v5/}</li>
 *   <li><b>User prefix map (Layer 2):</b> replaces leading multi-token prefixes using
 *       explicit key=value mappings provided via the {@code --url-prefix-map} CLI flag.
 *       Keys may be written in human-readable form ({@code svc.method(CONST)}) or token
 *       form ({@code {svc}{method}{CONST}}); both are normalized internally.</li>
 * </ol>
 *
 * <p>URLs that still contain {@code {token}} blocks after both layers are added to the
 * {@code warnings} list passed to {@link #applyToProject} and appear in Section 7 of
 * the output document.
 */
public final class UrlPrefixResolver {

    private static final Logger log = LoggerFactory.getLogger(UrlPrefixResolver.class);

    /** Matches one or more consecutive {@code {token}} blocks at the start of a string. */
    private static final Pattern LEADING_TOKENS = Pattern.compile("^(\\{[^}]+\\})+");
    /** Matches any {@code {token}} block anywhere in a string. */
    private static final Pattern ANY_TOKEN = Pattern.compile("\\{[^}]+\\}");

    private final Map<String, String> webpackConstants; // plain name → value
    private final Map<String, String> prefixMap;         // normalized token-prefix → replacement

    /**
     * @param webpackConstants constants from {@code webpack.DefinePlugin}; keys are plain
     *                         names without braces (e.g. {@code "API_ROOT_V5"})
     * @param userPrefixMap    user-supplied prefix map; keys may be in human-readable
     *                         ({@code svc.method(CONST)}) or token ({@code {svc}{method}}) form
     */
    public UrlPrefixResolver(Map<String, String> webpackConstants, Map<String, String> userPrefixMap) {
        this.webpackConstants = Map.copyOf(webpackConstants);
        Map<String, String> normalized = new LinkedHashMap<>();
        userPrefixMap.forEach((key, value) -> normalized.put(normalizePatternKey(key), value));
        this.prefixMap = Collections.unmodifiableMap(normalized);
        if (!prefixMap.isEmpty()) {
            log.info("UrlPrefixResolver: {} user prefix mapping(s) loaded.", prefixMap.size());
        }
    }

    /**
     * Returns {@code true} if both the webpack constants map and the user prefix map are empty,
     * meaning the resolver would be a no-op and can be skipped.
     */
    public boolean isEmpty() {
        return webpackConstants.isEmpty() && prefixMap.isEmpty();
    }

    /**
     * Applies URL resolution to every HTTP call in the project.
     * Produces a new {@link AngularProject}; the original is not mutated.
     *
     * @param project  source Angular project
     * @param warnings list to which unresolved-URL warning messages are appended
     * @return new project with resolved URL templates
     */
    public AngularProject applyToProject(AngularProject project, List<String> warnings) {
        List<ServiceInfo> resolved = project.getServices().stream()
                .map(svc -> applyToService(svc, warnings))
                .collect(Collectors.toList());
        return new AngularProject(project.getStrategy(), project.getFramework(),
                project.getComponents(), resolved, project.getModels(), project.getRoutes());
    }

    // -----------------------------------------------------------------------
    // Resolution layers
    // -----------------------------------------------------------------------

    /**
     * Layer 1: substitutes individual {@code {CONST_NAME}} tokens that match
     * known webpack {@code DefinePlugin} constants. Normalizes any double-slash
     * sequences produced when a constant value already contains a path separator.
     */
    String applyWebpackConstants(String url) {
        if (webpackConstants.isEmpty() || !url.contains("{")) return url;
        StringBuilder sb = new StringBuilder(url);
        for (Map.Entry<String, String> e : webpackConstants.entrySet()) {
            String token = "{" + e.getKey() + "}";
            int idx;
            while ((idx = sb.indexOf(token)) >= 0) {
                sb.replace(idx, idx + token.length(), e.getValue());
            }
        }
        // Collapse accidental double-slashes (e.g. path ends with / and value starts with /)
        return sb.toString().replaceAll("/{2,}", "/");
    }

    /**
     * Layer 2: replaces the leading {@code {token}...{token}} sequence of the URL using
     * the user prefix map. The leading {@code /} (if present) is skipped when extracting
     * the token sequence; the replacement value is expected to supply the correct base path.
     * Tries the longest matching prefix first, then progressively shorter ones.
     */
    String applyPrefixMap(String url) {
        if (prefixMap.isEmpty() || !url.contains("{")) return url;

        // Skip optional leading '/' to find the first {token}
        int firstBrace = url.indexOf('{');
        if (firstBrace < 0) return url;
        String rest = url.substring(firstBrace);

        Matcher m = LEADING_TOKENS.matcher(rest);
        if (!m.find()) return url;

        String leadingTokens = m.group(0);
        String suffix = rest.substring(leadingTokens.length());

        // Try exact match, then drop the last {token} block iteratively
        String current = leadingTokens;
        while (!current.isEmpty()) {
            String replacement = prefixMap.get(current);
            if (replacement != null) {
                String resolved = normalizePath(replacement + suffix);
                log.debug("UrlPrefixResolver: '{}' → '{}'", current, replacement);
                return resolved;
            }
            int lastOpen = current.lastIndexOf('{');
            if (lastOpen <= 0) break;
            current = current.substring(0, lastOpen);
        }
        return url;
    }

    // -----------------------------------------------------------------------
    // Normalization helpers
    // -----------------------------------------------------------------------

    /**
     * Normalizes a user-supplied prefix map key to token form.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "applicationConfigService.getEndpointFor(API_ROOT_V5)"}
     *       → {@code "{applicationConfigService}{getEndpointFor}{API_ROOT_V5}"}</li>
     *   <li>{@code "{svc}{method}{CONST}"} → returned unchanged</li>
     * </ul>
     */
    static String normalizePatternKey(String key) {
        if (key == null || key.isBlank()) return "";
        String trimmed = key.trim();
        if (trimmed.startsWith("{")) return trimmed; // already in token form
        // Split on separators: dot, open/close paren, comma, whitespace
        return Arrays.stream(trimmed.split("[.()',\\s]+"))
                .filter(s -> !s.isEmpty())
                .map(s -> "{" + s + "}")
                .collect(Collectors.joining());
    }

    /**
     * Parses a single {@code "key=value"} entry from the CLI {@code --url-prefix-map} argument.
     * Returns an empty optional if the entry is malformed.
     */
    public static Optional<Map.Entry<String, String>> parseMapEntry(String entry) {
        if (entry == null) return Optional.empty();
        int eq = entry.indexOf('=');
        if (eq <= 0 || eq == entry.length() - 1) {
            log.warn("UrlPrefixResolver: ignoring malformed --url-prefix-map entry '{}' "
                    + "(expected 'pattern=replacement')", entry);
            return Optional.empty();
        }
        String key   = entry.substring(0, eq).trim();
        String value = entry.substring(eq + 1).trim();
        return Optional.of(Map.entry(key, value));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private ServiceInfo applyToService(ServiceInfo svc, List<String> warnings) {
        List<HttpCallInfo> resolvedCalls = svc.getHttpCalls().stream()
                .map(call -> applyToCall(call, svc.getClassName(), warnings))
                .collect(Collectors.toList());
        return new ServiceInfo(svc.getClassName(), svc.getFilePath(), resolvedCalls);
    }

    private HttpCallInfo applyToCall(HttpCallInfo call, String serviceName,
                                     List<String> warnings) {
        String url = call.getUrlTemplate();
        if (!ANY_TOKEN.matcher(url).find()) return call; // fast path: no tokens at all

        // Try user prefix map first (on original URL with constant names still intact).
        // If no match, apply webpack constant substitution, then try prefix map again
        // (for keys written in post-webpack form, e.g. "{svc}{method}").
        String resolved = applyPrefixMap(url);
        if (resolved.equals(url)) {
            resolved = applyWebpackConstants(url);
            resolved = applyPrefixMap(resolved);
        }

        if (ANY_TOKEN.matcher(resolved).find()) {
            // Still has unresolved tokens after both layers
            String hint = buildHint(resolved);
            String warn = "Unresolved dynamic URL in `" + serviceName
                    + "#" + call.getMethodName() + "`: `" + resolved + "`"
                    + " — add `--url-prefix-map \"" + hint + "=<base-url>\"`"
                    + " to resolve this pattern.";
            log.warn(warn);
            warnings.add(warn);
        } else if (!resolved.equals(url)) {
            log.info("URL resolved: `{}` → `{}`", url, resolved);
        }

        return resolved.equals(url) ? call
                : new HttpCallInfo(call.getMethodName(), call.getHttpVerb(), resolved,
                                   call.getResponseType(), call.getBodyType());
    }

    /**
     * Extracts the leading token block from {@code url} to suggest the right
     * {@code --url-prefix-map} key to the user.
     */
    private static String buildHint(String url) {
        int firstBrace = url.indexOf('{');
        if (firstBrace < 0) return url;
        Matcher m = LEADING_TOKENS.matcher(url.substring(firstBrace));
        return m.find() ? m.group(0) : url;
    }

    private static String normalizePath(String raw) {
        String p = raw.replaceAll("/{2,}", "/");
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }
}
