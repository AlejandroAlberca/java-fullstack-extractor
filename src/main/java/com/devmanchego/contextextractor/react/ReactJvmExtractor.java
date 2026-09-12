package com.devmanchego.contextextractor.react;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * JVM-native extractor for React / Next.js projects (Strategy B fallback).
 *
 * Scans TypeScript and TSX source files using line-based regex patterns to find:
 *  - Service/API/hook files by naming convention
 *  - HTTP calls via axios, fetch, useQuery, useMutation, or custom http wrappers
 *  - TypeScript interface and type declarations for model extraction
 *
 * Returns an {@link AngularProject} using the shared intermediate model.
 */
public final class ReactJvmExtractor {

    private static final Logger log = LoggerFactory.getLogger(ReactJvmExtractor.class);

    // Patterns that identify service / API / hook files
    private static final Pattern SERVICE_FILE = Pattern.compile(
            "(?i)(.*\\.service\\.[jt]sx?|.*Api\\.[jt]sx?|.*Client\\.[jt]sx?|.*Repository\\.[jt]sx?|use[A-Z].*\\.[jt]sx?)$");
    private static final Set<String> SERVICE_DIRS = Set.of("services", "api", "hooks", "lib");

    // HTTP call patterns (line-level, quoted URL in first arg)
    // useQuery/useMutation are intentionally excluded — they delegate to service functions and yield no URL
    private static final List<HttpPattern> HTTP_PATTERNS = List.of(
            new HttpPattern(Pattern.compile("axios\\.(get|post|put|delete|patch)\\s*(?:<[^>]*>)?\\s*\\((['\"`])([^'\"` ]+)\\2"), 1, 3),
            new HttpPattern(Pattern.compile("http\\.(get|post|put|delete|patch)\\s*(?:<[^>]*>)?\\s*\\((['\"`])([^'\"` ]+)\\2"), 1, 3),
            new HttpPattern(Pattern.compile("fetch\\((['\"`])([^'\"` ]+)\\1"), null, 2)
    );

    // Patterns to track exported function names
    private static final Pattern EXPORT_FUNCTION_PATTERN = Pattern.compile(
            "^\\s*export\\s+(?:async\\s+)?(?:function|const)\\s+(\\w+)");
    private static final Pattern FUNCTION_START_PATTERN = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:async\\s+)?(?:function|const)\\s+(\\w+)");

    // Variable reference or template literal as first arg: axios.get(BASE_URL) / axios.get(`${BASE_URL}/sub`)
    private static final Pattern HTTP_VAR_PATTERN = Pattern.compile(
            "(?:axios|http)\\.(get|post|put|delete|patch)\\s*(?:<[^>]*>)?\\s*\\((`[^`]*`|\\w+)");
    private static final Pattern CONST_DECL = Pattern.compile(
            "(?:const|let|var)\\s+(\\w+)\\s*=\\s*(['\"`])([^'\"` ]+)\\2");

    // Interface / type extraction
    private static final Pattern INTERFACE_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?interface\\s+(\\w+)");
    private static final Pattern TYPE_ALIAS_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?type\\s+(\\w+)\\s*=");
    private static final Pattern FIELD_PATTERN = Pattern.compile("^\\s+(\\w+)(\\?)?\\s*:\\s*(.+?)\\s*[;,]?$");

    private final Path projectRoot;

    public ReactJvmExtractor(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public AngularProject extract() throws IOException {
        List<Path> files = collectFiles();
        log.info("ReactJvmExtractor: scanning {} TypeScript/TSX files.", files.size());

        List<ServiceInfo> services  = new ArrayList<>();
        List<TsModelInfo> models    = new ArrayList<>();

        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file).stream()
                        .map(String::stripTrailing)
                        .toList();
            } catch (IOException e) {
                log.warn("Cannot read {}: {} — skipping.", file, e.getMessage());
                continue;
            }

            String filePath = file.toAbsolutePath().toString();
            if (isServiceFile(file)) {
                extractHttpCalls(filePath, lines).ifPresent(services::add);
            }
            models.addAll(extractModels(filePath, lines));
        }

        return new AngularProject(AngularProject.ParsingStrategy.JVM_ANTLR,
                List.of(), services, models);
    }

    // -----------------------------------------------------------------------
    // HTTP extraction
    // -----------------------------------------------------------------------

    private Optional<ServiceInfo> extractHttpCalls(String filePath, List<String> lines) {
        Map<String, String> constants = extractConstants(lines);
        List<HttpCallInfo> calls = new ArrayList<>();
        String fileName = Path.of(filePath).getFileName().toString();
        String className = fileNameToClassName(fileName);

        String currentFunction = "unknown";
        int braceDepth = 0;

        for (String line : lines) {
            // Track exported function names
            Matcher funcMatcher = FUNCTION_START_PATTERN.matcher(line);
            if (funcMatcher.find()) {
                currentFunction = funcMatcher.group(1);
                braceDepth = 0;
            }

            // Track brace depth to know when we leave a function
            braceDepth += countChar(line, '{') - countChar(line, '}');

            boolean matched = false;

            for (HttpPattern hp : HTTP_PATTERNS) {
                Matcher m = hp.pattern.matcher(line);
                if (!m.find()) continue;

                HttpVerb verb = hp.verbGroup != null
                        ? verbFromString(m.group(hp.verbGroup))
                        : detectFetchVerb(line);
                String url = (hp.urlGroup != null && hp.urlGroup <= m.groupCount())
                        ? m.group(hp.urlGroup)
                        : "/unknown";

                // If the URL is a template expression, resolve constants
                if (url.contains("${")) {
                    String resolved = resolveUrlArg("`" + url + "`", constants);
                    if (resolved != null) url = resolved;
                }

                calls.add(new HttpCallInfo(currentFunction, verb, url, "any", null));
                matched = true;
                break;
            }

            if (!matched) {
                Matcher vm = HTTP_VAR_PATTERN.matcher(line);
                if (vm.find()) {
                    HttpVerb verb = verbFromString(vm.group(1));
                    String url = resolveUrlArg(vm.group(2), constants);
                    if (url != null) {
                        calls.add(new HttpCallInfo(currentFunction, verb, url, "any", null));
                    }
                }
            }
        }

        if (calls.isEmpty()) return Optional.empty();
        return Optional.of(new ServiceInfo(className, filePath, calls));
    }

    private static Map<String, String> extractConstants(List<String> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = CONST_DECL.matcher(line);
            if (m.find()) map.put(m.group(1), m.group(3));
        }
        return map;
    }

    private static String resolveUrlArg(String raw, Map<String, String> constants) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.startsWith("`") && raw.endsWith("`")) {
            String inner = raw.substring(1, raw.length() - 1);
            inner = Pattern.compile("\\$\\{(\\w+)}").matcher(inner).replaceAll(mr -> {
                String key = mr.group(1);
                return constants.getOrDefault(key, "{" + key + "}");
            });
            inner = inner.replaceAll("\\$\\{[^}]+}", "{param}");
            return inner.isEmpty() ? null : inner;
        }
        return constants.get(raw);
    }

    private HttpVerb verbFromString(String v) {
        try { return HttpVerb.valueOf(v.toUpperCase()); } catch (Exception e) { return HttpVerb.GET; }
    }

    private HttpVerb detectFetchVerb(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("\"post\"") || lower.contains("'post'")) return HttpVerb.POST;
        if (lower.contains("\"put\"")  || lower.contains("'put'"))  return HttpVerb.PUT;
        if (lower.contains("\"delete\"") || lower.contains("'delete'")) return HttpVerb.DELETE;
        if (lower.contains("\"patch\"") || lower.contains("'patch'")) return HttpVerb.PATCH;
        return HttpVerb.GET;
    }

    // -----------------------------------------------------------------------
    // Model extraction (interface / type)
    // -----------------------------------------------------------------------

    private List<TsModelInfo> extractModels(String filePath, List<String> lines) {
        List<TsModelInfo> result = new ArrayList<>();
        String currentName = null;
        TsModelInfo.Kind currentKind = null;
        List<TsFieldInfo> currentFields = new ArrayList<>();
        int braceDepth = 0;

        for (String line : lines) {
            if (currentName == null) {
                Matcher im = INTERFACE_PATTERN.matcher(line);
                if (im.find()) {
                    currentName = im.group(1);
                    currentKind = TsModelInfo.Kind.INTERFACE;
                    currentFields = new ArrayList<>();
                    braceDepth = 0;
                    // fall through to count the opening brace on this same line
                }
                Matcher tm = TYPE_ALIAS_PATTERN.matcher(line);
                if (tm.find() && line.contains("{")) {
                    currentName = tm.group(1);
                    currentKind = TsModelInfo.Kind.CLASS;
                    currentFields = new ArrayList<>();
                    braceDepth = 0;
                }
            }

            if (currentName != null) {
                braceDepth += countChar(line, '{') - countChar(line, '}');

                Matcher fm = FIELD_PATTERN.matcher(line);
                if (fm.matches() && braceDepth > 0) {
                    boolean optional = "?".equals(fm.group(2));
                    currentFields.add(new TsFieldInfo(fm.group(1), fm.group(3), optional));
                }

                if (braceDepth <= 0 && (line.contains("}") || line.trim().equals("}"))) {
                    if (!currentFields.isEmpty()) {
                        result.add(new TsModelInfo(currentName, filePath, currentKind, currentFields));
                    }
                    currentName = null;
                    currentKind = null;
                    currentFields = new ArrayList<>();
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // File collection
    // -----------------------------------------------------------------------

    private List<Path> collectFiles() throws IOException {
        if (!Files.isDirectory(projectRoot)) return List.of();
        try (var stream = Files.walk(projectRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString();
                        return s.endsWith(".ts") || s.endsWith(".tsx") || s.endsWith(".js") || s.endsWith(".jsx");
                    })
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().endsWith(".spec.ts") && !p.toString().endsWith(".spec.js"))
                    .filter(p -> !p.toString().endsWith(".d.ts"))
                    .collect(Collectors.toList());
        }
    }

    private boolean isServiceFile(Path file) {
        String name = file.getFileName().toString();
        if (SERVICE_FILE.matcher(file.toString()).matches()) return true;
        // Also check if any ancestor directory is in SERVICE_DIRS
        for (Path part : file) {
            if (SERVICE_DIRS.contains(part.toString().toLowerCase())) return true;
        }
        return false;
    }

    private static String fileNameToClassName(String fileName) {
        String base = fileName.replaceAll("\\.(tsx?|jsx?)$", "");
        // Convert kebab-case or snake_case to PascalCase
        String[] parts = base.split("[\\-_.]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    private record HttpPattern(Pattern pattern, Integer verbGroup, Integer urlGroup) {}
}
