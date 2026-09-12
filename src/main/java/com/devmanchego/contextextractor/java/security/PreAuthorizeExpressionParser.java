package com.devmanchego.contextextractor.java.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decomposes a Spring Security {@code @PreAuthorize} SpEL expression into the set of
 * roles/permissions it requires, and reports whether the expression was fully understood.
 *
 * <p>Recognises three predicate families:
 * <ul>
 *   <li>{@code hasRole(...)} / {@code hasAnyRole(...)}</li>
 *   <li>{@code hasAuthority(...)} / {@code hasAnyAuthority(...)}</li>
 *   <li>{@code hasPermission(target, ..., permission)} — a custom {@code PermissionEvaluator}
 *       predicate. The last quoted argument is taken as the permission subject; a compound
 *       value such as {@code 'ACHAT|ADMINISTRATION'} is split into its constituent permissions.
 *       The target argument is matched with balanced-parenthesis scanning, not a naive regex,
 *       so a target expression that itself contains a call — {@code hasPermission(target.get(),
 *       'X')} — doesn't truncate the argument list at the target's own closing paren.</li>
 * </ul>
 *
 * <p>An expression built entirely from these predicates (optionally joined by {@code and}/
 * {@code or}/{@code &&}/{@code ||}/parentheses/negation) is <b>fully parsed</b>: every subject
 * it requires is captured. An expression the parser cannot fully decompose — an unrecognised
 * method call, a non-literal {@code hasPermission} permission argument, or one mixed with
 * recognised predicates — is <b>not</b> fully parsed. Callers must never treat a not-fully-
 * parsed expression as equivalent to "no roles required": the caller still holds a real
 * authorisation annotation, just one this parser could not fully explain, and reporting it
 * as public would assert the opposite of what the source code says.
 */
public final class PreAuthorizeExpressionParser {

    // Guarded by a negative lookbehind so "customHasRoleCheck(...)" doesn't match "hasRole"
    // as a substring of a longer identifier.
    private static final Pattern PREDICATE_NAME = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:has(?:Any)?Role|has(?:Any)?Authority|hasPermission)");
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]*)['\"]");
    private static final Pattern CONNECTIVE_WORDS = Pattern.compile("(?i)\\b(and|or|not)\\b");

    private PreAuthorizeExpressionParser() {}

    /**
     * @param roles       every role/permission subject the expression requires, in the order found
     * @param fullyParsed {@code true} only when the whole expression reduces to recognised
     *                    predicates and boolean connectives, with nothing left over
     */
    public record Result(Set<String> roles, boolean fullyParsed) {}

    public static Result parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return new Result(Set.of(), false);
        }

        Set<String> roles = new LinkedHashSet<>();
        StringBuilder remainder = new StringBuilder();
        Matcher nameMatcher = PREDICATE_NAME.matcher(expression);
        int cursor = 0;
        boolean anyMatch = false;
        boolean anyUnresolvedPermission = false;

        while (nameMatcher.find(cursor)) {
            int nameStart = nameMatcher.start();
            int nameEnd = nameMatcher.end();
            String predicate = nameMatcher.group();

            int openParen = skipWhitespace(expression, nameEnd);
            if (openParen < 0 || expression.charAt(openParen) != '(') {
                // Not actually a call — e.g. a bare reference with no argument list. Leave the
                // whole predicate name in the remainder; it isn't a predicate we could apply.
                remainder.append(expression, cursor, nameEnd);
                cursor = nameEnd;
                continue;
            }
            int closeParen = matchingClose(expression, openParen);
            if (closeParen < 0) {
                remainder.append(expression, cursor, nameEnd);
                cursor = nameEnd;
                continue;
            }

            anyMatch = true;
            remainder.append(expression, cursor, nameStart);
            String args = expression.substring(openParen + 1, closeParen);

            if (predicate.contains("Permission")) {
                List<String> quoted = quotedTokens(args);
                if (quoted.isEmpty()) {
                    // hasPermission(...) with no string-literal argument (e.g. a dynamic
                    // target and a variable permission) — nothing to extract.
                    anyUnresolvedPermission = true;
                } else {
                    String permission = quoted.get(quoted.size() - 1);
                    for (String token : permission.split("\\|")) {
                        String t = token.trim();
                        if (!t.isEmpty()) roles.add(t);
                    }
                }
            } else {
                roles.addAll(quotedTokens(args));
            }

            cursor = closeParen + 1;
        }
        remainder.append(expression.substring(cursor));

        String cleaned = CONNECTIVE_WORDS.matcher(remainder).replaceAll("")
                .replace("&&", "")
                .replace("||", "")
                .replaceAll("[()!]", "")
                .trim();

        boolean fullyParsed = anyMatch && !anyUnresolvedPermission && cleaned.isEmpty();
        return new Result(roles, fullyParsed);
    }

    /** First non-whitespace index at or after {@code from}, or -1 if the string ends first. */
    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i < s.length() ? i : -1;
    }

    /**
     * Index of the {@code )} that closes the {@code (} at {@code openParenIndex}, counting
     * nesting depth so an argument that itself contains a parenthesised call — e.g.
     * {@code hasPermission(target.get(), 'X')} — doesn't truncate the scan at the inner call's
     * own closing paren. Returns -1 for an unbalanced expression.
     */
    private static int matchingClose(String s, int openParenIndex) {
        int depth = 0;
        for (int i = openParenIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> quotedTokens(String args) {
        List<String> tokens = new ArrayList<>();
        Matcher qm = QUOTED.matcher(args);
        while (qm.find()) {
            tokens.add(qm.group(1));
        }
        return tokens;
    }
}
