package com.devmanchego.contextextractor.cli;

import com.devmanchego.contextextractor.frontend.FrontendFramework;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Validated, parsed command-line arguments. */
public final class CliArguments {

    private final Path javaProjectPath;
    private final Path angularProjectPath;
    /**
     * Full path of the output Markdown file (directory + filename already combined).
     * The parent directory is guaranteed to exist when this object is created.
     */
    private final Path outputFile;
    /**
     * Common ancestor of both project paths, used to relativize all file paths
     * in the generated document (e.g. "backend/src/..." instead of an absolute path).
     */
    private final Path rootPath;
    /**
     * Explicit mappings for dynamic Angular URL prefixes that cannot be resolved
     * automatically from webpack config. Keys are token patterns (e.g.
     * {@code "{svc}{method}{CONST}"} or the human-readable equivalent); values are
     * the base URL they resolve to (e.g. {@code "/api/v5/"}).
     *
     * <p>Populated from one or more {@code --url-prefix-map key=value} CLI flags.
     */
    private final Map<String, String> urlPrefixMap;
    /**
     * Target directory for PNG flow diagrams generated from PlantUML sources.
     * When absent, PNG generation is skipped entirely.
     * Populated from {@code --flow-diagrams-dir <path>}.
     */
    private final Path flowDiagramsDir;
    /**
     * Target directory for PNG sequence diagrams generated from PlantUML sources.
     * When absent, PNG generation is skipped entirely.
     * Populated from {@code --sequence-diagrams-dir <path>}.
     */
    private final Path sequenceDiagramsDir;
    /**
     * Target directory for PNG class diagrams generated from PlantUML sources.
     * When absent, PNG generation is skipped entirely.
     * Populated from {@code --class-diagrams-dir <path>}.
     */
    private final Path classDiagramsDir;
    /**
     * Target directory for PNG full-flow diagrams (unfiltered conditions).
     * When absent, PNG generation is skipped entirely.
     * Populated from {@code --full-flow-diagrams-dir <path>}.
     */
    private final Path fullFlowDiagramsDir;
    /**
     * Optional override for the frontend framework (populated from {@code --frontend-framework}).
     * When absent, the framework is auto-detected from the project's {@code package.json}.
     */
    private final FrontendFramework frontendFramework;
    /**
     * When {@code true}, only exact canonical path matches are accepted.
     * When {@code false} (default), a fuzzy fallback also matches literal path
     * segments against {@code {param}} variables (e.g. frontend {@code AVAILABLE}
     * matches backend {@code {availability}}).
     * Populated from {@code --strict-matching}.
     */
    private final boolean strictMatching;
    /**
     * When {@code true} (default), the exception catalog includes generic exceptions
     * (IllegalArgumentException, IllegalStateException, etc.) that pass the
     * GenericExceptionClassifier scoring threshold. When {@code false}, only custom
     * exceptions are cataloged.
     * Populated from {@code --enable-generic-exceptions true/false}.
     */
    private final boolean enableGenericExceptions;
    /** Overrides {@code spring.datasource.url}. Populated from {@code --db-url}. */
    private final String dbUrl;
    /**
     * Overrides {@code spring.datasource.driver-class-name} — accepts either a driver
     * class name or a short type name (postgresql, mysql, oracle).
     * Populated from {@code --db-type}.
     */
    private final String dbType;
    /** Overrides {@code spring.datasource.username}. Populated from {@code --db-user}. */
    private final String dbUser;
    /** Overrides {@code spring.datasource.password}. Populated from {@code --db-password}. */
    private final String dbPassword;
    /** Active Spring profile whose {@code application-{profile}.yml} is also read. Populated from {@code --db-profile}. */
    private final String dbProfile;
    /** When {@code true}, skips live database introspection entirely (SQL migrations + JPA only). */
    private final boolean skipDbConnection;
    /**
     * Base directory under which {@code full_specs/}, {@code indexed_specs/}, and
     * {@code index_specs.md} are written. Defaults to the current working directory
     * (or the output-file's own directory) when {@code --output-dir} is not given.
     */
    private final Path baseOutputDir;
    /**
     * Directory for per-element index + detail documents (e.g. {@code index-spec-flows.md}
     * plus one file per flow/page). Always {@code <baseOutputDir>/indexed_specs}.
     */
    private final Path indexedSpecsDir;
    /**
     * Root navigation document linking to every document in {@code full_specs/} and
     * {@code indexed_specs/}. Always {@code <baseOutputDir>/index_specs.md}.
     */
    private final Path rootIndexFile;
    /**
     * Minimum element count (tables, persistence mappings, ...) a category needs before
     * it gets its own per-element index + detail documents under {@code indexed_specs/}.
     * Below this count, {@code index_specs.md} links directly to the full document instead.
     * Populated from {@code --index-detail-threshold} (default 3).
     */
    private final int indexDetailThreshold;

    public CliArguments(Path javaProjectPath, Path angularProjectPath,
                        Path outputFile, Path rootPath,
                        Map<String, String> urlPrefixMap,
                        Path flowDiagramsDir,
                        Path sequenceDiagramsDir,
                        Path classDiagramsDir,
                        Path fullFlowDiagramsDir,
                        FrontendFramework frontendFramework,
                        boolean strictMatching,
                        boolean enableGenericExceptions,
                        String dbUrl, String dbType, String dbUser, String dbPassword,
                        String dbProfile, boolean skipDbConnection,
                        Path baseOutputDir, Path indexedSpecsDir, Path rootIndexFile,
                        int indexDetailThreshold) {
        this.javaProjectPath = javaProjectPath;
        this.angularProjectPath = angularProjectPath;
        this.outputFile = outputFile;
        this.rootPath = rootPath;
        this.urlPrefixMap = Map.copyOf(urlPrefixMap);
        this.flowDiagramsDir = flowDiagramsDir;
        this.sequenceDiagramsDir = sequenceDiagramsDir;
        this.classDiagramsDir = classDiagramsDir;
        this.fullFlowDiagramsDir = fullFlowDiagramsDir;
        this.frontendFramework = frontendFramework;
        this.strictMatching = strictMatching;
        this.enableGenericExceptions = enableGenericExceptions;
        this.dbUrl = dbUrl;
        this.dbType = dbType;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.dbProfile = dbProfile;
        this.skipDbConnection = skipDbConnection;
        this.baseOutputDir = baseOutputDir;
        this.indexedSpecsDir = indexedSpecsDir;
        this.rootIndexFile = rootIndexFile;
        this.indexDetailThreshold = indexDetailThreshold;
    }

    public Path getJavaProjectPath()    { return javaProjectPath; }
    public Path getAngularProjectPath() { return angularProjectPath; }
    public Path getOutputFile()         { return outputFile; }
    public Path getRootPath()           { return rootPath; }
    public Map<String, String> getUrlPrefixMap()           { return urlPrefixMap; }
    public Optional<Path> getFlowDiagramsDir()             { return Optional.ofNullable(flowDiagramsDir); }
    public Optional<Path> getSequenceDiagramsDir()         { return Optional.ofNullable(sequenceDiagramsDir); }
    public Optional<Path> getClassDiagramsDir()            { return Optional.ofNullable(classDiagramsDir); }
    public Optional<Path> getFullFlowDiagramsDir()         { return Optional.ofNullable(fullFlowDiagramsDir); }
    public Optional<FrontendFramework> getFrontendFramework() { return Optional.ofNullable(frontendFramework); }
    public boolean isStrictMatching() { return strictMatching; }
    public boolean isEnableGenericExceptions() { return enableGenericExceptions; }
    public Optional<String> getDbUrl() { return Optional.ofNullable(dbUrl); }
    public Optional<String> getDbType() { return Optional.ofNullable(dbType); }
    public Optional<String> getDbUser() { return Optional.ofNullable(dbUser); }
    public Optional<String> getDbPassword() { return Optional.ofNullable(dbPassword); }
    public Optional<String> getDbProfile() { return Optional.ofNullable(dbProfile); }
    public boolean isSkipDbConnection() { return skipDbConnection; }
    public Path getBaseOutputDir() { return baseOutputDir; }
    public Path getIndexedSpecsDir() { return indexedSpecsDir; }
    public Path getRootIndexFile() { return rootIndexFile; }
    public int getIndexDetailThreshold() { return indexDetailThreshold; }
}
