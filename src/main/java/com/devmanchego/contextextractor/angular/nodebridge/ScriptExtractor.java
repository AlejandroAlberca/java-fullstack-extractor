package com.devmanchego.contextextractor.angular.nodebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.*;

/**
 * Extracts the bundled ts-morph-extractor.js from the JAR to a stable location
 * under ~/.java-angular-fullstack-extractor/scripts/ so Node.js can execute it.
 *
 * The file is only re-extracted when missing or when the bundled version differs
 * in size from the on-disk version, avoiding unnecessary disk I/O.
 */
public final class ScriptExtractor {

    private static final Logger log = LoggerFactory.getLogger(ScriptExtractor.class);
    private static final String RESOURCE_PATH = "/scripts/ts-morph-extractor.js";
    private static final String SCRIPTS_DIR   = ".java-angular-fullstack-extractor/scripts";
    private static final String SCRIPT_NAME   = "ts-morph-extractor.js";

    private ScriptExtractor() {}

    /**
     * Returns the absolute path to the extracted ts-morph-extractor.js script.
     *
     * @throws IOException if the bundled script cannot be found or written to disk.
     */
    public static Path extractScript() throws IOException {
        URL resource = ScriptExtractor.class.getResource(RESOURCE_PATH);
        if (resource == null) {
            throw new IOException("Bundled script not found in JAR: " + RESOURCE_PATH);
        }

        Path scriptsDir = Paths.get(System.getProperty("user.home")).resolve(SCRIPTS_DIR);
        Files.createDirectories(scriptsDir);
        Path target = scriptsDir.resolve(SCRIPT_NAME);

        long bundledSize = getBundledSize(resource);
        if (Files.exists(target) && Files.size(target) == bundledSize) {
            log.debug("Bundled script already present at {}", target);
            return target;
        }

        try (InputStream in = resource.openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Extracted bundled script to {}", target);
        return target;
    }

    private static long getBundledSize(URL resource) {
        try {
            return resource.openConnection().getContentLengthLong();
        } catch (IOException e) {
            return -1;
        }
    }
}
