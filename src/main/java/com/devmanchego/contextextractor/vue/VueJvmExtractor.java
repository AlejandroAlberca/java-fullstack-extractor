package com.devmanchego.contextextractor.vue;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * JVM-native extractor for Vue 2 / Vue 3 / Nuxt projects (Strategy B fallback).
 *
 * Scans {@code .vue} files (extracting their {@code <script>} blocks) and
 * TypeScript composable / service files to find HTTP calls and TS model
 * declarations.
 *
 * Returns an {@link AngularProject} using the shared intermediate model.
 */
public final class VueJvmExtractor {

    private static final Logger log = LoggerFactory.getLogger(VueJvmExtractor.class);

    private static final Set<String> COMPOSABLE_DIRS = Set.of("composables", "services", "api", "hooks", "lib");

    private static final Pattern COMPOSABLE_FILE =
            Pattern.compile("(?i)(use[A-Z].*\\.[jt]sx?|.*\\.service\\.[jt]sx?|.*Api\\.[jt]sx?|.*Client\\.[jt]sx?)$");

    // HTTP patterns: quoted URL directly in first arg
    // useQuery/useMutation are intentionally excluded — they delegate to service functions and yield no URL
    private static final List<HttpCallPattern> HTTP_PATTERNS = List.of(
            new HttpCallPattern(Pattern.compile("(?:axios|\\$axios)\\.(get|post|put|delete|patch)\\s*(?:<[^>]*>)?\\s*\\((['\"`])([^'\"` ]+)\\2"), 1, 3),
            new HttpCallPattern(Pattern.compile("(?:http|\\$http)\\.(get|post|put|delete|patch)\\s*(?:<[^>]*>)?\\s*\\((['\"`])([^'\"` ]+)\\2"), 1, 3),
            new HttpCallPattern(Pattern.compile("(?:this\\.\\$axios|this\\.\\$http)\\.(get|post|put|delete|patch)\\s*\\((['\"`])([^'\"` ]+)\\2"), 1, 3),
            new HttpCallPattern(Pattern.compile("fetch\\((['\"`])([^'\"` ]+)\\1"), null, 2)
    );

    // Patterns for variable reference as first arg: axios.get(BASE_URL, ...) or axios.get(`${BASE_URL}/...`)
    private static final Pattern HTTP_VAR_PATTERN = Pattern.compile(
            "(?:axios|\\$axios|http|\\$http|this\\.\\$axios|this\\.\\$http)\\.(get|post|put|delete|patch)\\s*(?:<[^>]*>)?\\s*\\((`[^`]*`|\\w+)");
    private static final Pattern CONST_DECL = Pattern.compile(
            "(?:const|let|var)\\s+(\\w+)\\s*=\\s*(['\"`])([^'\"` ]+)\\2");

    // Patterns to track exported function/composable names
    private static final Pattern FUNCTION_START_PATTERN = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:async\\s+)?(?:function|const)\\s+(\\w+)");
    private static final Pattern EXPORT_CONST_PATTERN = Pattern.compile(
            "^\\s*export\\s+const\\s+(\\w+)\\s*=\\s*(?:\\(|async)");

    private static final Pattern INTERFACE_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?interface\\s+(\\w+)");
    private static final Pattern FIELD_PATTERN = Pattern.compile("^\\s+(\\w+)(\\?)?\\s*:\\s*(.+?)\\s*[;,]?$");

    private final Path projectRoot;

    public VueJvmExtractor(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public AngularProject extract() throws IOException {
        List<Path> vueFiles = collectVueFiles();
        List<Path> tsFiles  = collectComposableFiles();
        log.info("VueJvmExtractor: scanning {} .vue files and {} composable .ts files.",
                vueFiles.size(), tsFiles.size());

        List<ServiceInfo> services = new ArrayList<>();
        List<TsModelInfo> models   = new ArrayList<>();

        for (Path file : vueFiles) {
            String raw;
            try { raw = Files.readString(file); } catch (IOException e) {
                log.warn("Cannot read {}: {} — skipping.", file, e.getMessage()); continue;
            }
            VueFilePreprocessor.extract(raw).ifPresent(block -> {
                String filePath = file.toAbsolutePath().toString();
                List<String> lines = Arrays.stream(block.source().split("\n", -1))
                        .map(String::stripTrailing).toList();
                extractHttpCalls(filePath, lines).ifPresent(services::add);
                models.addAll(extractModels(filePath, lines));
            });
        }

        for (Path file : tsFiles) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file).stream().map(String::stripTrailing).toList();
            } catch (IOException e) {
                log.warn("Cannot read {}: {} — skipping.", file, e.getMessage()); continue;
            }
            String filePath = file.toAbsolutePath().toString();
            extractHttpCalls(filePath, lines).ifPresent(services::add);
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
        String className = fileNameToClassName(Path.of(filePath).getFileName().toString());

        String currentFunction = "unknown";
        int braceDepth = 0;

        for (String line : lines) {
            // Track exported function/composable names
            Matcher funcMatcher = FUNCTION_START_PATTERN.matcher(line);
            if (funcMatcher.find()) {
                currentFunction = funcMatcher.group(1);
                braceDepth = 0;
            }

            // Track brace depth to know when we leave a function
            braceDepth += countChar(line, '{') - countChar(line, '}');

            boolean matched = false;

            // Try quoted-URL patterns first
            for (HttpCallPattern hp : HTTP_PATTERNS) {
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
                // Try variable-reference pattern: axios.get(BASE_URL) or axios.get(`${BASE_URL}/path`)
                Matcher vm = HTTP_VAR_PATTERN.matcher(line);
                if (vm.find()) {
                    HttpVerb verb = verbFromString(vm.group(1));
                    String rawArg = vm.group(2);
                    String url = resolveUrlArg(rawArg, constants);
                    if (url != null) {
                        calls.add(new HttpCallInfo(currentFunction, verb, url, "any", null));
                    }
                }
            }
        }

        if (calls.isEmpty()) return Optional.empty();
        return Optional.of(new ServiceInfo(className, filePath, calls));
    }

    /** Scans lines for const/let/var VARNAME = 'value' declarations. */
    private static Map<String, String> extractConstants(List<String> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = CONST_DECL.matcher(line);
            if (m.find()) map.put(m.group(1), m.group(3));
        }
        return map;
    }

    /**
     * Resolves a raw first-argument token to a URL string.
     * Handles: plain identifier (BASE_URL), template literal (`${BASE_URL}/sub`).
     */
    private static String resolveUrlArg(String raw, Map<String, String> constants) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.startsWith("`") && raw.endsWith("`")) {
            // Template literal: replace ${VAR} with constant value, ${param} with {param}
            String inner = raw.substring(1, raw.length() - 1);
            inner = Pattern.compile("\\$\\{(\\w+)}").matcher(inner).replaceAll(mr -> {
                String key = mr.group(1);
                return constants.getOrDefault(key, "{" + key + "}");
            });
            // Normalize remaining template expressions to path params
            inner = inner.replaceAll("\\$\\{[^}]+}", "{param}");
            return inner.isEmpty() ? null : inner;
        }
        // Plain identifier — look up in constants
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
    // Model extraction
    // -----------------------------------------------------------------------

    private List<TsModelInfo> extractModels(String filePath, List<String> lines) {
        List<TsModelInfo> result = new ArrayList<>();
        String currentName = null;
        List<TsFieldInfo> currentFields = new ArrayList<>();
        int braceDepth = 0;

        for (String line : lines) {
            if (currentName == null) {
                Matcher im = INTERFACE_PATTERN.matcher(line);
                if (im.find()) {
                    currentName = im.group(1);
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
                if (braceDepth <= 0 && line.contains("}")) {
                    if (!currentFields.isEmpty()) {
                        result.add(new TsModelInfo(currentName, filePath, TsModelInfo.Kind.INTERFACE, currentFields));
                    }
                    currentName = null;
                    currentFields = new ArrayList<>();
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // File collection
    // -----------------------------------------------------------------------

    private List<Path> collectVueFiles() throws IOException {
        if (!Files.isDirectory(projectRoot)) return List.of();
        try (var stream = Files.walk(projectRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".vue"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .collect(Collectors.toList());
        }
    }

    private List<Path> collectComposableFiles() throws IOException {
        if (!Files.isDirectory(projectRoot)) return List.of();
        try (var stream = Files.walk(projectRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString();
                        return s.endsWith(".ts") || s.endsWith(".js") || s.endsWith(".tsx") || s.endsWith(".jsx");
                    })
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().endsWith(".spec.ts") && !p.toString().endsWith(".spec.js"))
                    .filter(p -> !p.toString().endsWith(".d.ts"))
                    .filter(p -> isComposableFile(p))
                    .collect(Collectors.toList());
        }
    }

    private boolean isComposableFile(Path file) {
        String name = file.getFileName().toString();
        if (COMPOSABLE_FILE.matcher(name).matches()) return true;
        for (Path part : file) {
            if (COMPOSABLE_DIRS.contains(part.toString().toLowerCase())) return true;
        }
        return false;
    }

    private static String fileNameToClassName(String fileName) {
        String base = fileName.replaceAll("\\.(vue|tsx?|jsx?)$", "");
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

    private record HttpCallPattern(Pattern pattern, Integer verbGroup, Integer urlGroup) {}
}
