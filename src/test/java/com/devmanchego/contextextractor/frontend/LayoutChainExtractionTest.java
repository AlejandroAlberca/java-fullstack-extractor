package com.devmanchego.contextextractor.frontend;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Level 3b: verifies Next.js App Router layout chains are derived from directory structure.
 */
class LayoutChainExtractionTest {

    private Path findSampleNextJs() {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path samplePath = current.resolve(".private/sample-nextjs");
        if (Files.isDirectory(samplePath.resolve("app"))) {
            return samplePath;
        }
        return null;
    }

    @Test
    void testLayoutChainsFromAppRouter() throws Exception {
        Path nextjsRoot = findSampleNextJs();
        if (nextjsRoot == null) {
            return; // Skip if sample doesn't exist
        }

        Map<String, List<String>> layoutsByPage =
                new FileBasedRouteExtractor(nextjsRoot, FrontendFramework.NEXTJS).extractLayoutChains();

        assertFalse(layoutsByPage.isEmpty(), "Should find layout chains");

        // HomePage: only the root layout applies
        List<String> homeChain = layoutsByPage.get("HomePage");
        assertNotNull(homeChain, "HomePage should have a layout chain");
        assertEquals(List.of("RootLayout"), homeChain);

        // DashboardPage: root layout + dashboard layout (nested), root-first
        List<String> dashboardChain = layoutsByPage.get("DashboardPage");
        assertNotNull(dashboardChain, "DashboardPage should have a layout chain");
        assertEquals(List.of("RootLayout", "DashboardLayout"), dashboardChain);

        // UsersPage: no dashboard-specific layout, only root
        List<String> usersChain = layoutsByPage.get("UsersPage");
        assertNotNull(usersChain, "UsersPage should have a layout chain");
        assertEquals(List.of("RootLayout"), usersChain);

        System.out.println("✅ Level 3b verification: layout chains extracted");
        layoutsByPage.forEach((page, chain) ->
                System.out.println("   " + page + " -> " + String.join(" → ", chain)));
    }

    @Test
    void testNuxtReturnsEmpty() throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path nuxtRoot = current.resolve(".private/sample-nuxt");
        if (!Files.isDirectory(nuxtRoot.resolve("pages"))) {
            return;
        }

        Map<String, List<String>> layoutsByPage =
                new FileBasedRouteExtractor(nuxtRoot, FrontendFramework.NUXT).extractLayoutChains();
        assertTrue(layoutsByPage.isEmpty(), "Nuxt layout mechanism is out of scope — should return empty map");
    }
}
