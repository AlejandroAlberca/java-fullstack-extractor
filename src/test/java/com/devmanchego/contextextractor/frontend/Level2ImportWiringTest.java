package com.devmanchego.contextextractor.frontend;

import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.HttpCallInfo;
import com.devmanchego.contextextractor.angular.model.ServiceInfo;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.devmanchego.contextextractor.react.ReactJvmExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Level 2: wiring ComponentInfo.injectedServices from import statements.
 */
class Level2ImportWiringTest {

    private Path findSampleNextJs() {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path samplePath = current.resolve(".private/sample-nextjs");
        if (Files.isDirectory(samplePath.resolve("app"))) {
            return samplePath;
        }
        return null;
    }

    @Test
    void testImportWiringFromNextJs() throws Exception {
        Path nextjsRoot = findSampleNextJs();
        if (nextjsRoot == null) {
            return; // Skip if sample doesn't exist
        }

        // Step 1: Extract services (api-client.ts has HTTP calls)
        List<ServiceInfo> services = new ReactJvmExtractor(nextjsRoot).extract().getServices();
        assertFalse(services.isEmpty(), "Should extract api-client.ts as a service");

        // Should have ApiClient service with 5 HTTP calls
        ServiceInfo apiClientService = services.stream()
                .filter(s -> s.getClassName().equals("ApiClient"))
                .findFirst()
                .orElse(null);
        assertNotNull(apiClientService, "Should have ApiClient service");
        assertTrue(apiClientService.getHttpCalls().size() >= 5,
                "ApiClient should have at least 5 HTTP methods");

        // Verify calls have real method names (not "unknown")
        apiClientService.getHttpCalls().forEach(call ->
                assertNotEquals("unknown", call.getMethodName(),
                        "Method names should be populated, not 'unknown'"));

        // Step 2: Extract pages (file-based routes from app/)
        FileBasedRouteExtractor fileExtractor = new FileBasedRouteExtractor(nextjsRoot, FrontendFramework.NEXTJS);
        List<ComponentInfo> components = new java.util.ArrayList<>(
                fileExtractor.extractComponentInfoByName().values());
        assertFalse(components.isEmpty(), "Should extract page components");

        // Step 3: Wire imports (Level 2)
        FrontendImportGraphExtractor wireExtractor = new FrontendImportGraphExtractor(nextjsRoot, services);
        List<ComponentInfo> wired = wireExtractor.wireServices(components, services);

        // Verify UsersPage was wired with ApiClient
        ComponentInfo usersPage = wired.stream()
                .filter(c -> c.getClassName().equals("UsersPage"))
                .findFirst()
                .orElse(null);
        assertNotNull(usersPage, "Should have UsersPage component");
        assertTrue(usersPage.getInjectedServices().contains("ApiClient"),
                "UsersPage should have injected ApiClient service");

        System.out.println("✅ Level 2 verification: UsersPage → ApiClient wired successfully");
        System.out.println("   Services: " + usersPage.getInjectedServices());
        System.out.println("   API calls in ApiClient: " + apiClientService.getHttpCalls().stream()
                .map(HttpCallInfo::getMethodName)
                .toList());
    }

    @Test
    void testHttpMethodNamesArePopulated() throws Exception {
        Path nextjsRoot = findSampleNextJs();
        if (nextjsRoot == null) {
            return;
        }

        List<ServiceInfo> services = new ReactJvmExtractor(nextjsRoot).extract().getServices();

        // Part A verification: no "unknown" method names
        for (ServiceInfo service : services) {
            for (HttpCallInfo call : service.getHttpCalls()) {
                assertNotEquals("unknown", call.getMethodName(),
                        String.format("Service %s method should have real name, not 'unknown'",
                                service.getClassName()));
            }
        }

        System.out.println("✅ Part A verification: All HTTP calls have real method names");
    }
}
