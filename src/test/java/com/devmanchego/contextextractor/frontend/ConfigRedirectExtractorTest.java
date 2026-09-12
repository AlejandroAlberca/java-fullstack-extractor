package com.devmanchego.contextextractor.frontend;

import com.devmanchego.contextextractor.angular.model.RouteNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Level 3a: verifies static redirects are extracted from next.config.js redirects().
 */
class ConfigRedirectExtractorTest {

    private Path findSampleNextJs() {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path samplePath = current.resolve(".private/sample-nextjs");
        if (Files.isDirectory(samplePath.resolve("app"))) {
            return samplePath;
        }
        return null;
    }

    @Test
    void testNextJsRedirectsExtraction() throws Exception {
        Path nextjsRoot = findSampleNextJs();
        if (nextjsRoot == null) {
            return; // Skip if sample doesn't exist
        }

        List<RouteNode> redirects = new ConfigRedirectExtractor(nextjsRoot, FrontendFramework.NEXTJS).extract();

        assertEquals(2, redirects.size(), "Should extract exactly 2 redirects from next.config.js");

        RouteNode oldDashboard = redirects.stream()
                .filter(r -> r.getPath().equals("old-dashboard"))
                .findFirst()
                .orElse(null);
        assertNotNull(oldDashboard, "Should have /old-dashboard redirect");
        assertEquals("/dashboard", oldDashboard.getRedirectTo());
        assertNull(oldDashboard.getComponentName(), "Redirects have no component");
        assertFalse(oldDashboard.isPage(), "Redirects are not pages");

        RouteNode profile = redirects.stream()
                .filter(r -> r.getPath().equals("profile"))
                .findFirst()
                .orElse(null);
        assertNotNull(profile, "Should have /profile redirect");
        assertEquals("/settings", profile.getRedirectTo());

        System.out.println("✅ Level 3a verification: " + redirects.size() + " redirect(s) extracted");
        redirects.forEach(r -> System.out.println("   " + r.getPath() + " -> " + r.getRedirectTo()));
    }

    @Test
    void testNuxtRedirectsExtraction() throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path nuxtRoot = current.resolve(".private/sample-nuxt");
        if (!Files.isDirectory(nuxtRoot.resolve("pages"))) {
            return; // Skip if sample doesn't exist
        }

        List<RouteNode> redirects = new ConfigRedirectExtractor(nuxtRoot, FrontendFramework.NUXT).extract();

        assertEquals(2, redirects.size(), "Should extract exactly 2 redirects from nuxt.config.ts");

        RouteNode oldPage = redirects.stream()
                .filter(r -> r.getPath().equals("old-page"))
                .findFirst()
                .orElse(null);
        assertNotNull(oldPage, "Should have /old-page redirect (string form)");
        assertEquals("/new-page", oldPage.getRedirectTo());

        RouteNode legacy = redirects.stream()
                .filter(r -> r.getPath().equals("legacy/**"))
                .findFirst()
                .orElse(null);
        assertNotNull(legacy, "Should have /legacy/** redirect (object form with 'to')");
        assertEquals("/modern", legacy.getRedirectTo());

        System.out.println("✅ Nuxt redirect verification: " + redirects.size() + " redirect(s) extracted");
        redirects.forEach(r -> System.out.println("   " + r.getPath() + " -> " + r.getRedirectTo()));
    }

    @Test
    void testUnknownFrameworkReturnsEmpty() throws Exception {
        Path nextjsRoot = findSampleNextJs();
        if (nextjsRoot == null) {
            return;
        }

        List<RouteNode> redirects = new ConfigRedirectExtractor(nextjsRoot, FrontendFramework.REACT).extract();
        assertTrue(redirects.isEmpty(), "Non-Next/Nuxt frameworks should yield no config redirects");
    }

    @Test
    void testMissingConfigFileReturnsEmpty() throws Exception {
        Path emptyDir = Paths.get(System.getProperty("java.io.tmpdir"));
        List<RouteNode> redirects = new ConfigRedirectExtractor(emptyDir, FrontendFramework.NEXTJS).extract();
        assertTrue(redirects.isEmpty(), "Missing config file should degrade to empty list, not throw");
    }
}
