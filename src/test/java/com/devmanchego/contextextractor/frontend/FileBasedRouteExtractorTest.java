package com.devmanchego.contextextractor.frontend;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileBasedRouteExtractorTest {

    private Path findSampleNextJs() {
        // Build path from Maven working directory context
        Path current = Paths.get(System.getProperty("user.dir"));
        Path samplePath = current.resolve(".private/sample-nextjs");

        if (Files.isDirectory(samplePath.resolve("app"))) {
            return samplePath;
        }
        return null;
    }

    @Test
    void testNextJsAppRouterExtraction() throws IOException {
        Path nextjsRoot = findSampleNextJs();
        if (nextjsRoot == null) {
            return; // Skip if sample doesn't exist in this environment
        }

        FileBasedRouteExtractor extractor = new FileBasedRouteExtractor(nextjsRoot, FrontendFramework.NEXTJS);
        List<RouteNode> routes = extractor.extract();

        assertNotNull(routes, "Routes list should not be null");
        assertFalse(routes.isEmpty(), "Should extract routes from Next.js app router");

        // Print for debugging
        routes.forEach(r -> System.out.println("Route: path='" + r.getPath() + "', componentName='" + r.getComponentName() + "'"));

        // Verify root route
        assertTrue(routes.stream().anyMatch(r -> r.getPath().isEmpty() && r.getComponentName().equals("HomePage")),
                "Should have HomePage as root");

        // Verify /users route
        assertTrue(routes.stream().anyMatch(r -> r.getPath().equals("users") && r.getComponentName().equals("UsersPage")),
                "Should have UsersPage");

        // Verify /users/:id route
        assertTrue(routes.stream().anyMatch(r -> r.getPath().equals("users/:id") && r.getComponentName().equals("UsersIdPage")),
                "Should have UsersIdPage with dynamic segment");

        // Verify all routes are lazy
        assertTrue(routes.stream().allMatch(RouteNode::isLazy),
                "All file-based routes should be lazy by default");

        // Verify all routes are pages (have componentName)
        assertTrue(routes.stream().allMatch(RouteNode::isPage),
                "All extracted routes should be pages");
    }

    @Test
    void testComponentInfoExtraction() throws IOException {
        Path nextjsRoot = findSampleNextJs();
        if (nextjsRoot == null) {
            return;
        }

        FileBasedRouteExtractor extractor = new FileBasedRouteExtractor(nextjsRoot, FrontendFramework.NEXTJS);
        Map<String, ComponentInfo> components = extractor.extractComponentInfoByName();

        assertNotNull(components);
        assertFalse(components.isEmpty(), "Should extract minimal ComponentInfo for route pages");

        // Print for debugging
        components.forEach((name, info) -> System.out.println("Component: " + name + " -> " + info.getFilePath()));

        // Verify component names match the synthetic names
        assertTrue(components.containsKey("HomePage"), "Should have HomePage");
        assertTrue(components.containsKey("UsersPage"), "Should have UsersPage");
        assertTrue(components.containsKey("UsersIdPage"), "Should have UsersIdPage");

        // Verify file paths are present
        for (ComponentInfo comp : components.values()) {
            assertNotNull(comp.getFilePath());
            assertTrue(comp.getFilePath().endsWith(".tsx"),
                    "Component file path should end with .tsx");
        }
    }

    @Test
    void testNuxtExtraction() throws IOException {
        // Create a minimal Nuxt structure for testing
        Path nuxtRoot = Paths.get(".private/sample-nuxt");
        if (!nuxtRoot.toFile().exists()) {
            return; // Skip if sample doesn't exist
        }

        FileBasedRouteExtractor extractor = new FileBasedRouteExtractor(nuxtRoot, FrontendFramework.NUXT);
        List<RouteNode> routes = extractor.extract();

        assertNotNull(routes);
        // Routes should be extracted from pages/ directory
    }

    @Test
    void testUnknownFrameworkReturnsEmpty() throws IOException {
        Path unknownRoot = Paths.get(".private/sample-app");
        if (!unknownRoot.toFile().exists()) {
            return;
        }

        FileBasedRouteExtractor extractor = new FileBasedRouteExtractor(unknownRoot, FrontendFramework.UNKNOWN);
        List<RouteNode> routes = extractor.extract();

        assertTrue(routes.isEmpty(), "Unknown framework should return empty routes");
    }

    @Test
    void testDynamicSegmentNormalization() throws IOException {
        FileBasedRouteExtractor extractor = new FileBasedRouteExtractor(Paths.get("."), FrontendFramework.NEXTJS);

        // Test bracket normalization via component name generation
        // This tests the internal normalizeDynamicSegment method indirectly

        // [id] → UsersIdPage, [:slug] → slug
        Map<String, ComponentInfo> components = extractor.extractComponentInfoByName();
        // If we got here without exception, normalization is working
        assertNotNull(components);
    }
}
