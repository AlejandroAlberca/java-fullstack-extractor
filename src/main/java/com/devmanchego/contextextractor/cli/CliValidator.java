package com.devmanchego.contextextractor.cli;

import com.devmanchego.contextextractor.angular.resolve.UrlPrefixResolver;
import com.devmanchego.contextextractor.frontend.FrontendFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates raw CLI arguments and produces a {@link CliArguments} instance
 * or exits the process with a non-zero status code on any validation failure.
 */
public final class CliValidator {

    private static final Logger log = LoggerFactory.getLogger(CliValidator.class);

    private static final String DEFAULT_OUTPUT_FILE = "full-stack-spec.md";
    private static final String MD_EXTENSION = ".md";
    private static final String FULL_SPECS_DIRNAME = "full_specs";
    private static final String INDEXED_SPECS_DIRNAME = "indexed_specs";
    private static final String ROOT_INDEX_FILENAME = "index_specs.md";
    private static final int DEFAULT_INDEX_DETAIL_THRESHOLD = 3;

    private CliValidator() {}

    public static CliArguments validate(String[] args) {
        if (args.length < 2) {
            fatal("Usage: java -jar java-angular-fullstack-extractor.jar"
                    + " <java-project-path> <frontend-project-path>"
                    + " [output-file]"
                    + " [--output-dir <dir>]"
                    + " [--index-detail-threshold <n>]"
                    + " [--frontend-framework angular|react|vue3|vue2|nextjs|nuxt|jsp]"
                    + " [--url-prefix-map key=value] ..."
                    + " [--flow-diagrams-dir <dir>]"
                    + " [--full-flow-diagrams-dir <dir>]"
                    + " [--sequence-diagrams-dir <dir>]"
                    + " [--class-diagrams-dir <dir>]"
                    + " [--strict-matching]"
                    + " [--db-url <jdbc-url>] [--db-type postgresql|mysql|oracle]"
                    + " [--db-user <user>] [--db-password <password>] [--db-profile <profile>]"
                    + " [--skip-db-connection]");
        }

        Path javaPath    = requireReadableDirectory(args[0], "java-project-path");
        Path angularPath = requireReadableDirectory(args[1], "angular-project-path");

        // Positional arg[2]: full path (or name) of the output file.
        //   - may include a directory:  /reports/api-spec.md
        //   - or be just a file name:   api-spec.md  (written to current dir)
        // Named flags:  --url-prefix-map key=value (repeatable)
        //               --flow-diagrams-dir <path>
        String rawOutput = DEFAULT_OUTPUT_FILE;
        Map<String, String> urlPrefixMap = new LinkedHashMap<>();
        Path flowDiagramsDir = null;
        Path sequenceDiagramsDir = null;
        Path classDiagramsDir = null;
        Path fullFlowDiagramsDir = null;
        FrontendFramework frontendFramework = null;
        boolean strictMatching = false;
        boolean enableGenericExceptions = true;  // default: enabled
        String dbUrl = null;
        String dbType = null;
        String dbUser = null;
        String dbPassword = null;
        String dbProfile = null;
        boolean skipDbConnection = false;
        Path outputDirFlag = null;
        int indexDetailThreshold = DEFAULT_INDEX_DETAIL_THRESHOLD;

        for (int i = 2; i < args.length; i++) {
            if ("--url-prefix-map".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--url-prefix-map requires a value in 'key=value' form.");
                }
                String entry = args[++i];
                UrlPrefixResolver.parseMapEntry(entry)
                        .ifPresent(e -> urlPrefixMap.put(e.getKey(), e.getValue()));
            } else if ("--flow-diagrams-dir".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--flow-diagrams-dir requires a directory path.");
                }
                flowDiagramsDir = resolveDirectory(args[++i], "--flow-diagrams-dir");
            } else if ("--sequence-diagrams-dir".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--sequence-diagrams-dir requires a directory path.");
                }
                sequenceDiagramsDir = resolveDirectory(args[++i], "--sequence-diagrams-dir");
            } else if ("--class-diagrams-dir".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--class-diagrams-dir requires a directory path.");
                }
                classDiagramsDir = resolveDirectory(args[++i], "--class-diagrams-dir");
            } else if ("--full-flow-diagrams-dir".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--full-flow-diagrams-dir requires a directory path.");
                }
                fullFlowDiagramsDir = resolveDirectory(args[++i], "--full-flow-diagrams-dir");
            } else if ("--frontend-framework".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--frontend-framework requires a value: angular, react, vue3, vue2, nextjs, nuxt, jsp");
                }
                frontendFramework = parseFrontendFramework(args[++i]);
            } else if ("--strict-matching".equals(args[i])) {
                strictMatching = true;
            } else if ("--enable-generic-exceptions".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--enable-generic-exceptions requires a value: true or false");
                }
                enableGenericExceptions = parseBoolean(args[++i], "--enable-generic-exceptions");
            } else if ("--db-url".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--db-url requires a JDBC URL value.");
                }
                dbUrl = args[++i];
            } else if ("--db-type".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--db-type requires a value: postgresql, mysql, or oracle.");
                }
                dbType = args[++i];
            } else if ("--db-user".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--db-user requires a value.");
                }
                dbUser = args[++i];
            } else if ("--db-password".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--db-password requires a value.");
                }
                dbPassword = args[++i];
            } else if ("--db-profile".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--db-profile requires a value (e.g. 'prod').");
                }
                dbProfile = args[++i];
            } else if ("--skip-db-connection".equals(args[i])) {
                skipDbConnection = true;
            } else if ("--output-dir".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--output-dir requires a directory path.");
                }
                outputDirFlag = resolveDirectory(args[++i], "--output-dir");
            } else if ("--index-detail-threshold".equals(args[i])) {
                if (i + 1 >= args.length) {
                    fatal("--index-detail-threshold requires an integer value.");
                }
                indexDetailThreshold = parseIndexDetailThreshold(args[++i]);
            } else if (args[i].startsWith("--")) {
                log.warn("Unknown flag '{}' — ignored.", args[i]);
            } else if (i == 2) {
                rawOutput = args[i];
            }
        }

        if (!urlPrefixMap.isEmpty()) {
            log.info("CLI url-prefix-map: {} mapping(s) provided.", urlPrefixMap.size());
        }
        if (flowDiagramsDir != null) {
            log.info("Flow diagrams (PNG) will be written to: {}", flowDiagramsDir);
        }
        if (sequenceDiagramsDir != null) {
            log.info("Sequence diagrams (PNG) will be written to: {}", sequenceDiagramsDir);
        }
        if (classDiagramsDir != null) {
            log.info("Class diagrams (PNG) will be written to: {}", classDiagramsDir);
        }
        if (fullFlowDiagramsDir != null) {
            log.info("Full flow diagrams (PNG) will be written to: {}", fullFlowDiagramsDir);
        }
        if (frontendFramework != null) {
            log.info("Frontend framework override: {}", frontendFramework);
        }
        if (strictMatching) {
            log.info("Strict matching enabled — only exact canonical endpoint paths will be matched.");
        }

        ResolvedOutput resolved = resolveOutput(rawOutput, outputDirFlag);
        log.info("Full specs directory:   {}", resolved.fullSpecsDir());
        log.info("Indexed specs directory: {}", resolved.indexedSpecsDir());
        log.info("Root index file:        {}", resolved.rootIndexFile());

        Path rootPath = commonAncestor(javaPath, angularPath);
        log.debug("Common root path for relativization: {}", rootPath);
        if (enableGenericExceptions) {
            log.info("Generic exception classification enabled (--enable-generic-exceptions true)");
        } else {
            log.info("Generic exception classification disabled (--enable-generic-exceptions false)");
        }
        if (skipDbConnection) {
            log.info("--skip-db-connection set — live database introspection will be skipped.");
        }
        return new CliArguments(javaPath, angularPath, resolved.outputFile(), rootPath,
                                urlPrefixMap, flowDiagramsDir, sequenceDiagramsDir, classDiagramsDir,
                                fullFlowDiagramsDir, frontendFramework, strictMatching, enableGenericExceptions,
                                dbUrl, dbType, dbUser, dbPassword, dbProfile, skipDbConnection,
                                resolved.baseOutputDir(), resolved.indexedSpecsDir(), resolved.rootIndexFile(),
                                indexDetailThreshold);
    }

    /**
     * Parses the {@code --index-detail-threshold} value: the minimum element count
     * (tables, persistence mappings, ...) a category needs before it gets its own
     * per-element index + detail documents under {@code indexed_specs/}. Below this
     * count, {@code index_specs.md} links directly to the full document instead.
     */
    static int parseIndexDetailThreshold(String value) {
        try {
            int n = Integer.parseInt(value.strip());
            if (n < 0) {
                fatal("--index-detail-threshold must be zero or a positive integer, got: " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            fatal("Invalid --index-detail-threshold value: '" + value + "'. Must be an integer.");
            throw new AssertionError("unreachable");
        }
    }

    // -----------------------------------------------------------------------
    // Output file resolution
    // -----------------------------------------------------------------------

    /**
     * Bundles the resolved output layout: every generated Markdown document lives under
     * one base directory, split into {@code full_specs/} (complete, monolithic documents)
     * and {@code indexed_specs/} (per-element index + detail documents), with a single
     * {@code index_specs.md} at the base directory linking to both.
     */
    record ResolvedOutput(Path outputFile, Path baseOutputDir, Path fullSpecsDir,
                          Path indexedSpecsDir, Path rootIndexFile) {}

    /**
     * Resolves the raw output argument (and optional {@code --output-dir}) into the
     * {@code full_specs/} + {@code indexed_specs/} + {@code index_specs.md} layout.
     * <ul>
     *   <li>Ensures the output file name has a {@code .md} extension.</li>
     *   <li>The base directory is {@code --output-dir} when given; otherwise the directory
     *       component of the positional output-file argument (current working directory
     *       if none was given).</li>
     *   <li>{@code full_specs/} and {@code indexed_specs/} are created under the base
     *       directory if absent.</li>
     * </ul>
     */
    static ResolvedOutput resolveOutput(String raw, Path outputDirFlag) {
        String mdName = ensureMdExtension(raw);
        Path rawPath;
        try {
            rawPath = Paths.get(mdName);
        } catch (InvalidPathException e) {
            fatal("Invalid output file path: " + raw);
            throw new AssertionError("unreachable");
        }

        Path baseDir;
        String fileName;
        if (outputDirFlag != null) {
            baseDir = outputDirFlag;
            fileName = rawPath.getFileName().toString();
        } else {
            Path abs = rawPath.toAbsolutePath().normalize();
            Path parent = abs.getParent();
            baseDir = parent != null ? parent : Paths.get("").toAbsolutePath();
            fileName = abs.getFileName().toString();
        }
        baseDir = baseDir.toAbsolutePath().normalize();

        Path fullSpecsDir = baseDir.resolve(FULL_SPECS_DIRNAME);
        Path indexedSpecsDir = baseDir.resolve(INDEXED_SPECS_DIRNAME);
        createDirectoryIfAbsent(fullSpecsDir, "full_specs");
        createDirectoryIfAbsent(indexedSpecsDir, "indexed_specs");

        Path outputFile = fullSpecsDir.resolve(fileName);
        Path rootIndexFile = baseDir.resolve(ROOT_INDEX_FILENAME);
        return new ResolvedOutput(outputFile, baseDir, fullSpecsDir, indexedSpecsDir, rootIndexFile);
    }

    private static void createDirectoryIfAbsent(Path dir, String label) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("Created {} directory: {}", label, dir);
            } catch (Exception e) {
                fatal("Failed to create " + label + " directory '" + dir + "': " + e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves a directory path from a CLI flag value, creating it if absent.
     */
    static Path resolveDirectory(String raw, String flagName) {
        Path p;
        try {
            p = Paths.get(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            fatal("Invalid path for " + flagName + ": " + raw);
            throw new AssertionError("unreachable");
        }
        if (!Files.exists(p)) {
            try {
                Files.createDirectories(p);
                log.info("Created directory for {}: {}", flagName, p);
            } catch (Exception e) {
                fatal("Failed to create directory for " + flagName + " '" + p + "': " + e.getMessage());
            }
        }
        if (!Files.isDirectory(p)) {
            fatal(flagName + " path is not a directory: " + p);
        }
        return p;
    }

    private static Path requireReadableDirectory(String raw, String argName) {
        Path path;
        try {
            path = Paths.get(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            fatal("Invalid path for " + argName + ": " + raw);
            throw new AssertionError("unreachable");
        }
        if (!Files.exists(path)) {
            fatal("Path does not exist for " + argName + ": " + path);
        }
        if (!Files.isDirectory(path)) {
            fatal("Path is not a directory for " + argName + ": " + path);
        }
        if (!Files.isReadable(path)) {
            fatal("Path is not readable for " + argName + ": " + path);
        }
        return path;
    }

    private static String ensureMdExtension(String name) {
        if (!name.endsWith(MD_EXTENSION)) {
            log.warn("Output file name '{}' does not end in .md — appending .md automatically.", name);
            return name + MD_EXTENSION;
        }
        return name;
    }

    /**
     * Returns the deepest common ancestor directory of two absolute, normalized paths.
     * Example: common("/a/b/c", "/a/b/d") → "/a/b"
     */
    static Path commonAncestor(Path a, Path b) {
        Path candidate = a;
        while (candidate != null && !b.startsWith(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate != null ? candidate : a.getRoot();
    }

    /**
     * Parses the {@code --frontend-framework} value (case-insensitive).
     * Accepted values: angular, react, vue3, vue2, nextjs, nuxt, jsp.
     */
    static FrontendFramework parseFrontendFramework(String value) {
        return switch (value.toLowerCase().strip()) {
            case "angular" -> FrontendFramework.ANGULAR;
            case "react"   -> FrontendFramework.REACT;
            case "vue3"    -> FrontendFramework.VUE3;
            case "vue2"    -> FrontendFramework.VUE2;
            case "nextjs"  -> FrontendFramework.NEXTJS;
            case "nuxt"    -> FrontendFramework.NUXT;
            case "jsp"     -> FrontendFramework.JSP_JQUERY;
            default -> {
                fatal("Invalid --frontend-framework value: '" + value
                        + "'. Accepted values: angular, react, vue3, vue2, nextjs, nuxt, jsp.");
                throw new AssertionError("unreachable");
            }
        };
    }

    /**
     * Parses a boolean flag value (case-insensitive).
     * Accepted values: true, false.
     */
    static boolean parseBoolean(String value, String flagName) {
        return switch (value.toLowerCase().strip()) {
            case "true" -> true;
            case "false" -> false;
            default -> {
                fatal("Invalid " + flagName + " value: '" + value
                        + "'. Accepted values: true, false.");
                throw new AssertionError("unreachable");
            }
        };
    }

    /** Thrown when argument validation fails. Callers (Main) convert this to System.exit(1). */
    public static final class CliException extends RuntimeException {
        public CliException(String message) { super(message); }
    }

    static void fatal(String message) {
        throw new CliException(message);
    }
}
