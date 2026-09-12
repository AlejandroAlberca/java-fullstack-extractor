package com.devmanchego.contextextractor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a Node.js executable path using the configuration cascade:
 *
 *  1. Environment variable JAVA_ANGULAR_FULLSTACK_EXTRACTOR_NODE_PATH
 *  2. ~/.java-angular-fullstack-extractor/config.properties → node.path
 *  3. Auto-detect: run "node --version" via PATH, accept if version >= 18
 *  4. Return empty → caller falls back to Strategy B (ANTLR JVM parser)
 */
public final class NodePathResolver {

    private static final Logger log = LoggerFactory.getLogger(NodePathResolver.class);

    static final String ENV_VAR = "JAVA_ANGULAR_FULLSTACK_EXTRACTOR_NODE_PATH";
    private static final String CONFIG_DIR = ".java-angular-fullstack-extractor";
    private static final String CONFIG_FILE = "config.properties";
    private static final String PROP_NODE_PATH = "node.path";
    private static final int MIN_NODE_MAJOR_VERSION = 18;

    public NodePathResolver() {}

    /**
     * Returns the resolved Node.js executable path, or empty if Node.js is unavailable
     * or below the minimum required version.
     */
    public Optional<String> resolve() {
        // 1. Environment variable
        String envValue = System.getenv(ENV_VAR);
        if (envValue != null && !envValue.isBlank()) {
            log.debug("Node.js path from environment variable: {}", envValue);
            if (isNodeAcceptable(envValue)) {
                return Optional.of(envValue);
            }
        }

        // 2. Config file
        Optional<String> fromConfig = readFromConfigFile();
        if (fromConfig.isPresent()) {
            log.debug("Node.js path from config file: {}", fromConfig.get());
            if (isNodeAcceptable(fromConfig.get())) {
                return fromConfig;
            }
        }

        // 3. Auto-detect via PATH
        if (isNodeAcceptable("node")) {
            log.debug("Node.js auto-detected via PATH.");
            return Optional.of("node");
        }

        // 4. Fallback
        log.info("Node.js not found or below version {}. Falling back to JVM ANTLR parser (Strategy B).",
                MIN_NODE_MAJOR_VERSION);
        return Optional.empty();
    }

    private Optional<String> readFromConfigFile() {
        Path configPath = Paths.get(System.getProperty("user.home"))
                .resolve(CONFIG_DIR)
                .resolve(CONFIG_FILE);
        if (!Files.isReadable(configPath)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Failed to read config file {}: {}", configPath, e.getMessage());
            return Optional.empty();
        }
        String value = props.getProperty(PROP_NODE_PATH, "").trim();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    boolean isNodeAcceptable(String nodeExe) {
        try {
            Process proc = new ProcessBuilder(nodeExe, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            if (!finished || proc.exitValue() != 0) {
                return false;
            }
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            return parseMajorVersion(output) >= MIN_NODE_MAJOR_VERSION;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static int parseMajorVersion(String versionOutput) {
        // Expected format: "v18.17.0" or "18.17.0"
        String stripped = versionOutput.startsWith("v") ? versionOutput.substring(1) : versionOutput;
        int dot = stripped.indexOf('.');
        String major = dot >= 0 ? stripped.substring(0, dot) : stripped;
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
