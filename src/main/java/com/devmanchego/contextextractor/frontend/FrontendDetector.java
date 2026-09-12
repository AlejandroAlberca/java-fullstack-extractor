package com.devmanchego.contextextractor.frontend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Detects the frontend framework in use by inspecting package.json.
 * Detection order enforces specificity: NEXTJS before REACT, NUXT before VUE.
 */
public final class FrontendDetector {

    private static final Logger log = LoggerFactory.getLogger(FrontendDetector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FrontendDetector() {}

    public static FrontendFramework detect(Path projectRoot) {
        Path packageJson = projectRoot.resolve("package.json");
        if (!packageJson.toFile().exists()) {
            log.debug("No package.json found at {} — returning UNKNOWN.", projectRoot);
            return FrontendFramework.UNKNOWN;
        }

        PackageJson pkg;
        try {
            pkg = MAPPER.readValue(packageJson.toFile(), PackageJson.class);
        } catch (IOException e) {
            log.warn("Could not parse package.json at {}: {} — returning UNKNOWN.", packageJson, e.getMessage());
            return FrontendFramework.UNKNOWN;
        }

        boolean hasAngular  = has(pkg, "@angular/core");
        boolean hasNext     = has(pkg, "next");
        boolean hasNuxt     = has(pkg, "nuxt");
        boolean hasReact    = has(pkg, "react");
        boolean hasVue      = has(pkg, "vue");

        if (hasAngular) {
            log.info("Detected frontend framework: ANGULAR");
            return FrontendFramework.ANGULAR;
        }
        if (hasNext) {
            log.info("Detected frontend framework: NEXTJS");
            return FrontendFramework.NEXTJS;
        }
        if (hasNuxt) {
            log.info("Detected frontend framework: NUXT");
            return FrontendFramework.NUXT;
        }
        if (hasReact) {
            log.info("Detected frontend framework: REACT");
            return FrontendFramework.REACT;
        }
        if (hasVue) {
            String version = vueVersion(pkg);
            FrontendFramework fw = version.startsWith("2") ? FrontendFramework.VUE2 : FrontendFramework.VUE3;
            log.info("Detected frontend framework: {} (vue version string: {})", fw, version);
            return fw;
        }

        if (looksLikeJspJQueryProject(projectRoot)) {
            log.info("Detected frontend framework: JSP_JQUERY (no SPA dependency, JSP views + bundler config found)");
            return FrontendFramework.JSP_JQUERY;
        }

        log.info("No known frontend framework detected in package.json — returning UNKNOWN.");
        return FrontendFramework.UNKNOWN;
    }

    // -----------------------------------------------------------------------
    // JSP + jQuery detection
    // -----------------------------------------------------------------------

    private static final Set<String> EXCLUDED_SCAN_DIRS =
            Set.of("node_modules", "dist", "build", ".git", "target");

    /**
     * True when no known SPA framework dependency matched, but the project has both JSP views
     * and a bundler configuration — the profile {@link FrontendFramework#JSP_JQUERY} targets.
     * Both signals are required: a webpack config with no JSP views is more likely a plain SPA
     * this detector simply doesn't recognise yet, and a stray {@code .jsp} fixture with no
     * bundler at all isn't the webpack-bundled jQuery layer this framework tag describes.
     */
    private static boolean looksLikeJspJQueryProject(Path projectRoot) {
        return hasFileMatching(projectRoot, name -> name.endsWith(".jsp"))
                && hasFileMatching(projectRoot, name -> name.startsWith("webpack") && name.endsWith(".js"));
    }

    private static boolean hasFileMatching(Path root, java.util.function.Predicate<String> nameMatches) {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(p -> {
                        for (Path segment : root.relativize(p)) {
                            if (EXCLUDED_SCAN_DIRS.contains(segment.toString())) return false;
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> nameMatches.test(p.getFileName().toString().toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            log.debug("Could not scan {} for JSP/webpack signals: {}", root, e.getMessage());
            return false;
        }
    }

    private static boolean has(PackageJson pkg, String dep) {
        return containsKey(pkg.dependencies, dep) || containsKey(pkg.devDependencies, dep);
    }

    private static boolean containsKey(Map<String, String> map, String key) {
        return map != null && map.containsKey(key);
    }

    /**
     * Returns the version string for the "vue" dependency, or "3" as a safe default
     * (Vue 3 is the current major version).
     */
    private static String vueVersion(PackageJson pkg) {
        return Optional.ofNullable(pkg.dependencies)
                .map(m -> m.get("vue"))
                .or(() -> Optional.ofNullable(pkg.devDependencies).map(m -> m.get("vue")))
                .map(v -> v.replaceAll("[^0-9.]", "").strip())
                .filter(v -> !v.isEmpty())
                .orElse("3");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PackageJson {
        public Map<String, String> dependencies;
        public Map<String, String> devDependencies;
    }
}
