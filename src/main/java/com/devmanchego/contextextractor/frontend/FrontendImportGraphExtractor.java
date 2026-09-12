package com.devmanchego.contextextractor.frontend;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.ServiceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts and wires service imports from page components to their corresponding services.
 *
 * For each component (page), scans for import statements and maps them to ServiceInfo
 * based on file path resolution. Populates ComponentInfo.injectedServices so that
 * downstream renderers (FrontendPagesRenderer, Page↔Flow edges) can correlate pages
 * to the API calls their services make.
 *
 * Strategy: import file path → resolve to file on disk → match className to ServiceInfo
 * using fileNameToClassName (deterministic).
 */
public final class FrontendImportGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(FrontendImportGraphExtractor.class);

    // Match: import { x, y } from 'path' or import x from './path' or import * as x from ...
    // Uses two separate patterns: one for single quotes, one for double quotes
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+(?:\\{[^}]*\\}|\\w+|\\*\\s+as\\s+\\w+)\\s+from\\s+(?:'([^']+)'|\"([^\"]+)\")");

    private final Path frontendRoot;
    private final Map<String, ServiceInfo> servicesByFilePath;

    public FrontendImportGraphExtractor(Path frontendRoot, List<ServiceInfo> services) {
        this.frontendRoot = frontendRoot;
        // Build index: normalized file path → ServiceInfo
        this.servicesByFilePath = new HashMap<>();
        for (ServiceInfo service : services) {
            String normalized = normalizePath(service.getFilePath());
            servicesByFilePath.put(normalized, service);
        }
    }

    /**
     * Wires injectedServices into components based on their imports.
     *
     * @param components Pages/components to enrich (typically from file-based route extraction)
     * @param services All available services with HTTP calls
     * @return New ComponentInfo instances with injectedServices populated
     */
    public List<ComponentInfo> wireServices(List<ComponentInfo> components, List<ServiceInfo> services) {
        Map<String, ServiceInfo> servicesByFilePath = new HashMap<>();
        for (ServiceInfo service : services) {
            String normalized = normalizePath(service.getFilePath());
            servicesByFilePath.put(normalized, service);
        }

        List<ComponentInfo> result = new ArrayList<>();
        for (ComponentInfo comp : components) {
            List<String> importedServices = extractImportedServices(comp.getFilePath(), servicesByFilePath);
            ComponentInfo wired = new ComponentInfo(
                    comp.getClassName(),
                    comp.getFilePath(),
                    comp.getSelector(),
                    comp.getInjectedServices().isEmpty() ? importedServices : comp.getInjectedServices());
            result.add(wired);
        }
        return result;
    }

    /**
     * Extracts service class names from a component's import statements.
     * Resolves import paths relative to the component file.
     */
    private List<String> extractImportedServices(String componentFilePath, Map<String, ServiceInfo> servicesByFilePath) {
        List<String> serviceNames = new ArrayList<>();

        Path componentPath = Path.of(componentFilePath);
        if (!Files.exists(componentPath)) {
            log.debug("Component file not found: {}", componentFilePath);
            return serviceNames;
        }

        try {
            List<String> lines = Files.readAllLines(componentPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                Matcher m = IMPORT_PATTERN.matcher(line);
                if (!m.find()) continue;

                // m.group(1) = single-quoted path, m.group(2) = double-quoted path
                String importPath = m.group(1) != null ? m.group(1) : m.group(2);
                // Resolve relative import path to absolute file path
                Path resolvedPath = resolveImportPath(componentPath.getParent(), importPath);
                if (resolvedPath == null) {
                    log.debug("Could not resolve import path: {} from {}", importPath, componentPath.getParent());
                    continue;
                }

                String normalized = normalizePath(resolvedPath.toAbsolutePath().toString());
                ServiceInfo service = servicesByFilePath.get(normalized);
                if (service != null) {
                    log.debug("Wired {} to {}", componentFilePath, service.getClassName());
                    serviceNames.add(service.getClassName());
                } else {
                    log.debug("No service found for resolved path: {} (normalized: {})", resolvedPath, normalized);
                }
            }
        } catch (IOException e) {
            log.debug("Could not read component file {}: {}", componentFilePath, e.getMessage());
        }

        return serviceNames;
    }

    /**
     * Resolves an import path (e.g., '../api/userApi', '@/services/UserService')
     * to an absolute file path.
     *
     * Tries multiple extensions (.ts, .tsx, .js, .jsx) since TypeScript/JS extensions
     * are often omitted in import statements.
     */
    private Path resolveImportPath(Path fromDir, String importPath) {
        // Handle @/ alias (common in Next.js/Nuxt: mapped to src/ root)
        if (importPath.startsWith("@/")) {
            importPath = importPath.substring(2);
            // Try to find src/ or app/ directory
            Path srcRoot = frontendRoot.resolve("src");
            if (Files.isDirectory(srcRoot)) {
                return resolveWithExtensions(srcRoot.resolve(importPath));
            }
            // Fallback: try from frontendRoot directly
            return resolveWithExtensions(frontendRoot.resolve(importPath));
        }

        // Relative import
        Path resolved = fromDir.resolve(importPath).normalize();
        return resolveWithExtensions(resolved);
    }

    /**
     * Tries common TypeScript/JavaScript extensions if the path as-is doesn't exist.
     */
    private Path resolveWithExtensions(Path path) {
        String[] extensions = {".ts", ".tsx", ".js", ".jsx", ""};
        for (String ext : extensions) {
            Path candidate = Path.of(path.toString() + ext);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null; // No file found
    }

    /**
     * Normalizes file paths for comparison: forward slashes, lowercase on Windows.
     */
    private String normalizePath(String filePath) {
        return filePath.replace("\\", "/").toLowerCase();
    }
}
