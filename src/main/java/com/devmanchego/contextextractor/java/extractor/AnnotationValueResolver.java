package com.devmanchego.contextextractor.java.extractor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Resolves Java annotation string values that may contain constant references
 * or string-concatenation expressions.
 *
 * <p>Handles:
 * <ul>
 *   <li>Simple string literals: {@code "value"} → {@code value}</li>
 *   <li>String concatenation: {@code A + "/path"} → resolved recursively</li>
 *   <li>Class-qualified constant: {@code WebConstants.API_V1_ROOT_URL} → looked up in map</li>
 *   <li>Unqualified constant: {@code API_ROOT} → looked up in map</li>
 * </ul>
 *
 * <p>Constants are pre-collected from all {@link CompilationUnit}s via
 * {@link #collectConstants(List)} and stored as:
 * <ul>
 *   <li>{@code ClassName.FIELD_NAME} → value</li>
 *   <li>{@code FIELD_NAME} → value (short-form fallback)</li>
 * </ul>
 */
public final class AnnotationValueResolver {

    private static final Logger log = LoggerFactory.getLogger(AnnotationValueResolver.class);

    private AnnotationValueResolver() {}

    // -----------------------------------------------------------------------
    // Constant collection (pre-pass over all CUs)
    // -----------------------------------------------------------------------

    /**
     * Scans all compilation units for {@code public static final String} fields
     * and returns a map of {@code ClassName.FIELD} → string value.
     *
     * Non-literal initialisers (computed expressions) are skipped — only direct
     * string literals are recorded, since those are the common case for API root constants.
     */
    public static Map<String, String> collectConstants(List<CompilationUnit> cus) {
        Map<String, String> result = new LinkedHashMap<>();
        for (CompilationUnit cu : cus) {
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getNameAsString();
                cls.findAll(FieldDeclaration.class).forEach(fd -> {
                    if (!fd.isStatic() || !fd.isFinal()) return;
                    if (!"String".equals(fd.getElementType().asString())) return;
                    fd.getVariables().forEach(var -> {
                        var.getInitializer().ifPresent(init -> {
                            String literal = extractStringLiteral(init.toString());
                            if (literal != null) {
                                String qualifiedKey = className + "." + var.getNameAsString();
                                String shortKey     = var.getNameAsString();
                                result.putIfAbsent(qualifiedKey, literal);
                                result.putIfAbsent(shortKey, literal);
                                log.debug("Collected Java constant: {} = \"{}\"", qualifiedKey, literal);
                            }
                        });
                    });
                });
            });
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Expression resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves a raw annotation value expression to a plain string.
     *
     * @param expr      Raw expression text as returned by JavaParser (e.g.
     *                  {@code WebConstants.API_V1_ROOT_URL + "/users"})
     * @param constants Constants map built by {@link #collectConstants(List)}
     * @return Resolved string, or the raw expression in braces if unresolvable
     */
    public static String resolve(String expr, Map<String, String> constants) {
        if (expr == null) return "";
        expr = expr.trim();

        // Simple string literal
        String literal = extractStringLiteral(expr);
        if (literal != null) return literal;

        // Concatenation: split on '+' respecting string boundaries
        List<String> parts = splitByPlus(expr);
        if (parts.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                sb.append(resolve(part.trim(), constants));
            }
            return sb.toString();
        }

        // Constant reference: ClassName.FIELD_NAME or just FIELD_NAME
        if (constants.containsKey(expr)) return constants.get(expr);

        // Short-form fallback: use only the field name after the last dot
        int lastDot = expr.lastIndexOf('.');
        if (lastDot >= 0) {
            String shortKey = expr.substring(lastDot + 1);
            if (constants.containsKey(shortKey)) return constants.get(shortKey);
        }

        // Unresolvable — emit as a named placeholder to make it visible in output
        log.debug("Could not resolve Java annotation expression: {}", expr);
        return "{" + expr + "}";
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the unquoted content of a Java string literal, or {@code null}
     * if the input is not a simple string literal.
     */
    static String extractStringLiteral(String s) {
        String t = s.trim();
        if (t.length() < 2 || t.charAt(0) != '"') return null;
        // Walk forward to find the closing quote, respecting escape sequences.
        // The closing quote must be the very last character; anything after it
        // means this is a concatenation expression, not a simple literal.
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\') { i++; continue; }  // skip escaped character
            if (c == '"') {
                if (i != t.length() - 1) return null;  // more content after closing quote
                return t.substring(1, i)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\n", "\n")
                        .replace("\\t", "\t");
            }
        }
        return null;  // no closing quote found
    }

    /**
     * Splits a Java expression by top-level {@code +} operators, respecting
     * string-literal boundaries so that {@code "a" + "b"} → ["\"a\"", "\"b\""]
     * and {@code "a+b" + c} → ["\"a+b\"", "c"].
     */
    static List<String> splitByPlus(String expr) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < expr.length()) {
                    current.append(c).append(expr.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                    current.append(c);
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inString = true;
                    current.append(c);
                } else if (c == '+') {
                    String part = current.toString().trim();
                    if (!part.isEmpty()) parts.add(part);
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) parts.add(last);
        return parts;
    }
}
