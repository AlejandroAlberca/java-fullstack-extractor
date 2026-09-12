package com.devmanchego.contextextractor.java.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the runtime classpath of a Maven project by running
 * {@code mvn dependency:build-classpath}.
 * Falls back to an empty list (degraded mode) when Maven is not available
 * or the project has no pom.xml.
 */
public final class ClasspathResolver {

    private static final Logger log = LoggerFactory.getLogger(ClasspathResolver.class);
    private static final long TIMEOUT_SECONDS = 120;

    private ClasspathResolver() {}

    /**
     * Returns the resolved classpath entries for the given Maven project root.
     * Returns an empty list when resolution fails (degraded mode).
     */
    public static List<Path> resolve(Path projectRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            log.warn("No pom.xml found at {}. Classpath resolution skipped — type resolution degraded.", projectRoot);
            return List.of();
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile("fullstack-extractor-cp-", ".txt");
            tempFile.toFile().deleteOnExit();
        } catch (IOException e) {
            log.warn("Cannot create temp file for classpath output: {}", e.getMessage());
            return List.of();
        }

        try {
            List<String> cmd = List.of(
                    resolveMvnExecutable(),
                    "-f", pom.toString(),
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + tempFile.toAbsolutePath(),
                    "-q"
            );
            log.debug("Running classpath resolution: {}", String.join(" ", cmd));

            Process proc = new ProcessBuilder(cmd)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .start();

            boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("Classpath resolution timed out after {}s — degraded mode.", TIMEOUT_SECONDS);
                return List.of();
            }
            if (proc.exitValue() != 0) {
                log.warn("mvn dependency:build-classpath exited with code {} — degraded mode.", proc.exitValue());
                return List.of();
            }

            return parseClasspathFile(tempFile);

        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // A "No such file" / "cannot run" error almost always means mvn is not on PATH.
            boolean looksLikeMvnNotFound = msg.toLowerCase().contains("no such file")
                    || msg.toLowerCase().contains("cannot run")
                    || msg.toLowerCase().contains("createprocess error")
                    || msg.toLowerCase().contains("error=2");
            if (looksLikeMvnNotFound) {
                log.warn("Maven (mvn) not found on PATH — classpath resolution skipped (degraded mode).");
                log.warn("Add Maven's 'bin' directory to PATH so that type resolution and TypeScript"
                        + " parsing work correctly.");
            } else {
                log.warn("Classpath resolution failed: {} — degraded mode.", msg);
            }
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Classpath resolution interrupted — degraded mode.");
            return List.of();
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    private static List<Path> parseClasspathFile(Path file) throws IOException {
        String content = Files.readString(file).trim();
        if (content.isBlank()) {
            return List.of();
        }
        String separator = System.getProperty("path.separator", ":");
        String[] parts = content.split(java.util.regex.Pattern.quote(separator));
        List<Path> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                result.add(Path.of(trimmed));
            }
        }
        log.debug("Classpath resolved: {} entries.", result.size());
        return result;
    }

    private static String resolveMvnExecutable() {
        // On Windows, prefer mvn.cmd
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "mvn.cmd" : "mvn";
    }
}
