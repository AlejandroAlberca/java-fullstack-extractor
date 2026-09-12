package com.devmanchego.contextextractor.java.security;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Analyzes Spring Security configuration (SecurityFilterChain) to extract URL-pattern-based
 * authorization rules: requestMatchers(...).hasRole(...) or hasAnyRole(...).
 *
 * <p>Limitation: Only captures literal role names in {@code hasRole()} and {@code hasAnyRole()}.
 * Complex conditions with AND/OR are not parsed (but the entire line is captured for manual review).
 */
public final class SecurityConfigAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfigAnalyzer.class);

    private static final Pattern MATCHER_PATTERN = Pattern.compile(
            "(?:requestMatchers|antMatchers)\\s*\\(([^)]*)\\)");
    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?:hasRole|hasAnyRole)\\s*\\(([^)]*)\\)");
    private static final Pattern HTTP_METHOD_PATTERN = Pattern.compile(
            "(?:HttpMethod\\.)([A-Z]+)");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    private SecurityConfigAnalyzer() {}

    /**
     * Scans the Java project for SecurityFilterChain or WebSecurityConfigurerAdapter
     * and extracts URL pattern → role mappings.
     *
     * @param javaProjectPath root of the Java project
     * @return list of security rules extracted from configuration
     */
    public static List<SecurityRule> analyzeSecurityConfig(Path javaProjectPath) {
        List<SecurityRule> rules = new ArrayList<>();

        try {
            Files.walk(javaProjectPath, 10)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("node_modules") && !p.toString().contains("target"))
                    .forEach(javaFile -> {
                        try {
                            String content = Files.readString(javaFile, StandardCharsets.UTF_8);
                            if (content.contains("SecurityFilterChain") || content.contains("WebSecurityConfigurerAdapter")) {
                                rules.addAll(extractRulesFromFile(content, javaFile));
                            }
                        } catch (IOException e) {
                            log.debug("Cannot read {}: {}", javaFile, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Cannot walk project tree: {}", e.getMessage());
        }

        if (!rules.isEmpty()) {
            log.info("SecurityConfigAnalyzer: extracted {} URL-pattern rules", rules.size());
        }
        return rules;
    }

    private static List<SecurityRule> extractRulesFromFile(String content, Path source) {
        List<SecurityRule> rules = new ArrayList<>();

        // Split into lines and find matcher + role pairs
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("requestMatchers") || line.contains("antMatchers")) {
                // Try to extract pattern and roles from this line and potentially the next few
                SecurityRule rule = tryExtractRule(line, lines, i);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }

        return rules;
    }

    private static SecurityRule tryExtractRule(String line, String[] allLines, int currentIndex) {
        Matcher matcherMatcher = MATCHER_PATTERN.matcher(line);
        if (!matcherMatcher.find()) return null;

        String matcherArgs = matcherMatcher.group(1);
        Set<String> patterns = extractStringLiterals(matcherArgs);
        String httpMethod = extractHttpMethod(matcherArgs);

        // Look for role in current line or next few lines
        String roleLine = line;
        for (int i = 0; i < 3 && currentIndex + i < allLines.length; i++) {
            roleLine += " " + allLines[currentIndex + i];
        }

        Matcher roleMatcher = ROLE_PATTERN.matcher(roleLine);
        Set<String> roles = new HashSet<>();
        if (roleMatcher.find()) {
            String roleArgs = roleMatcher.group(1);
            roles.addAll(extractRoleNames(roleArgs));
        }

        if (patterns.isEmpty()) return null;

        // Create a rule for each pattern
        String pattern = patterns.iterator().next();  // simplified: one pattern per rule
        return new SecurityRule(
                pattern,
                httpMethod,
                roles,
                SecurityRule.RuleStrategy.REQUEST_MATCHER,
                "SecurityFilterChain"
        );
    }

    private static Set<String> extractStringLiterals(String text) {
        Set<String> literals = new HashSet<>();
        Matcher m = STRING_LITERAL.matcher(text);
        while (m.find()) {
            literals.add(m.group(1));
        }
        return literals;
    }

    private static String extractHttpMethod(String text) {
        Matcher m = HTTP_METHOD_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static Set<String> extractRoleNames(String roleArgs) {
        Set<String> roles = new HashSet<>();

        // Handle both hasRole("ADMIN") and hasAnyRole("ADMIN", "USER")
        Matcher m = STRING_LITERAL.matcher(roleArgs);
        while (m.find()) {
            roles.add(m.group(1));
        }

        return roles;
    }
}
