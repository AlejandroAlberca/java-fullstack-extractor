package com.devmanchego.contextextractor.frontend;

import com.devmanchego.contextextractor.angular.model.RouteNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts static redirects declared in Next.js ({@code next.config.js/ts/mjs},
 * {@code redirects()}) and Nuxt ({@code nuxt.config.ts/js}, {@code routeRules}).
 *
 * Text-level scanner (no JS/TS AST), matching the graceful-degradation contract of
 * {@link com.devmanchego.contextextractor.angular.jvmparser.JvmRouteExtractor}: only the
 * canonical literal-array / literal-object form is understood. Dynamic redirects built from
 * variables, spreads, environment checks, or function calls are silently skipped rather than
 * guessed at.
 *
 * Out of scope: {@code rewrites()} (proxy/internal remapping, not a user-visible navigation
 * change) and conditional/dynamic redirect logic — both left for a future pass if needed.
 */
public final class ConfigRedirectExtractor {

    private static final Logger log = LoggerFactory.getLogger(ConfigRedirectExtractor.class);

    // Next.js: source: '...'  ...  destination: '...'  (bounded window so we don't cross
    // into a neighboring object in the array).
    private static final Pattern NEXT_REDIRECT_ENTRY = Pattern.compile(
            "source\\s*:\\s*['\"`]([^'\"`]+)['\"`][\\s\\S]{0,200}?destination\\s*:\\s*['\"`]([^'\"`]+)['\"`]");

    // Nuxt: '/path': { redirect: '...' }  or  '/path': { redirect: { to: '...' } }
    private static final Pattern NUXT_REDIRECT_ENTRY = Pattern.compile(
            "['\"`]([^'\"`]+)['\"`]\\s*:\\s*\\{[^{}]*?redirect\\s*:\\s*(?:['\"`]([^'\"`]+)['\"`]|\\{[^{}]*?to\\s*:\\s*['\"`]([^'\"`]+)['\"`][^{}]*?\\})");

    private static final String[] NEXT_CONFIG_NAMES = {
            "next.config.js", "next.config.mjs", "next.config.ts", "next.config.cjs"
    };
    private static final String[] NUXT_CONFIG_NAMES = {
            "nuxt.config.ts", "nuxt.config.js"
    };

    private final Path frontendRoot;
    private final FrontendFramework framework;

    public ConfigRedirectExtractor(Path frontendRoot, FrontendFramework framework) {
        this.frontendRoot = frontendRoot;
        this.framework = framework;
    }

    public List<RouteNode> extract() throws IOException {
        return switch (framework) {
            case NEXTJS -> extractNextRedirects();
            case NUXT -> extractNuxtRedirects();
            default -> Collections.emptyList();
        };
    }

    // -----------------------------------------------------------------------
    // Next.js — redirects()
    // -----------------------------------------------------------------------

    private List<RouteNode> extractNextRedirects() throws IOException {
        String content = readFirstExisting(NEXT_CONFIG_NAMES);
        if (content == null) return Collections.emptyList();

        String block = extractFunctionBlock(content, "redirects");
        if (block == null) {
            log.debug("No redirects() function found in Next.js config.");
            return Collections.emptyList();
        }

        List<RouteNode> result = new ArrayList<>();
        Matcher m = NEXT_REDIRECT_ENTRY.matcher(block);
        while (m.find()) {
            String source = normalizePath(m.group(1));
            String destination = m.group(2);
            result.add(new RouteNode(source, null, null, destination, false, Collections.emptyList()));
        }
        log.info("ConfigRedirectExtractor: {} redirect(s) found in Next.js config.", result.size());
        return result;
    }

    // -----------------------------------------------------------------------
    // Nuxt — routeRules
    // -----------------------------------------------------------------------

    private List<RouteNode> extractNuxtRedirects() throws IOException {
        String content = readFirstExisting(NUXT_CONFIG_NAMES);
        if (content == null) return Collections.emptyList();

        String block = extractObjectBlock(content, "routeRules");
        if (block == null) {
            log.debug("No routeRules block found in Nuxt config.");
            return Collections.emptyList();
        }

        List<RouteNode> result = new ArrayList<>();
        Matcher m = NUXT_REDIRECT_ENTRY.matcher(block);
        while (m.find()) {
            String source = normalizePath(m.group(1));
            String destination = m.group(2) != null ? m.group(2) : m.group(3);
            if (destination == null) continue;
            result.add(new RouteNode(source, null, null, destination, false, Collections.emptyList()));
        }
        log.info("ConfigRedirectExtractor: {} redirect(s) found in Nuxt config.", result.size());
        return result;
    }

    // -----------------------------------------------------------------------
    // Shared text-level helpers
    // -----------------------------------------------------------------------

    private String readFirstExisting(String[] candidateNames) throws IOException {
        for (String name : candidateNames) {
            Path candidate = frontendRoot.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Finds {@code functionName(...) { ... }} (any arg list, optionally {@code async}) and
     * returns the text between its outermost braces. Returns {@code null} if not found or
     * unbalanced.
     */
    private String extractFunctionBlock(String content, String functionName) {
        Pattern header = Pattern.compile("\\b" + Pattern.quote(functionName) + "\\s*\\([^)]*\\)\\s*\\{");
        Matcher m = header.matcher(content);
        if (!m.find()) return null;
        int braceStart = m.end() - 1; // index of the opening '{'
        return extractBalancedBlock(content, braceStart);
    }

    /**
     * Finds {@code identifierName: { ... }} and returns the text between its outermost braces.
     */
    private String extractObjectBlock(String content, String identifierName) {
        Pattern header = Pattern.compile("\\b" + Pattern.quote(identifierName) + "\\s*:\\s*\\{");
        Matcher m = header.matcher(content);
        if (!m.find()) return null;
        int braceStart = m.end() - 1;
        return extractBalancedBlock(content, braceStart);
    }

    /**
     * Given the index of an opening '{', returns the substring strictly between it and its
     * matching closing '}' (simple depth counter — does not account for braces inside string
     * literals, matching the lightweight-scanner contract used elsewhere in this codebase).
     */
    private String extractBalancedBlock(String content, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(openBraceIndex + 1, i);
                }
            }
        }
        return null; // unbalanced — malformed or truncated file
    }

    /** Strips a leading slash so the value matches the RouteNode.path convention ("users", not "/users"). */
    private String normalizePath(String source) {
        return source.startsWith("/") ? source.substring(1) : source;
    }
}
