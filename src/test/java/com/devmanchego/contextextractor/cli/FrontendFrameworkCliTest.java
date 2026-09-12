package com.devmanchego.contextextractor.cli;

import com.devmanchego.contextextractor.frontend.FrontendFramework;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FrontendFrameworkCliTest {

    @TempDir
    Path tempDir;

    @Test
    void parseFrontendFramework_angular_caseInsensitive() {
        assertEquals(FrontendFramework.ANGULAR, CliValidator.parseFrontendFramework("angular"));
        assertEquals(FrontendFramework.ANGULAR, CliValidator.parseFrontendFramework("ANGULAR"));
        assertEquals(FrontendFramework.ANGULAR, CliValidator.parseFrontendFramework("Angular"));
    }

    @Test
    void parseFrontendFramework_react() {
        assertEquals(FrontendFramework.REACT, CliValidator.parseFrontendFramework("react"));
    }

    @Test
    void parseFrontendFramework_vue3() {
        assertEquals(FrontendFramework.VUE3, CliValidator.parseFrontendFramework("vue3"));
    }

    @Test
    void parseFrontendFramework_vue2() {
        assertEquals(FrontendFramework.VUE2, CliValidator.parseFrontendFramework("vue2"));
    }

    @Test
    void parseFrontendFramework_nextjs() {
        assertEquals(FrontendFramework.NEXTJS, CliValidator.parseFrontendFramework("nextjs"));
    }

    @Test
    void parseFrontendFramework_nuxt() {
        assertEquals(FrontendFramework.NUXT, CliValidator.parseFrontendFramework("nuxt"));
    }

    @Test
    void parseFrontendFramework_jsp() {
        assertEquals(FrontendFramework.JSP_JQUERY, CliValidator.parseFrontendFramework("jsp"));
        assertEquals(FrontendFramework.JSP_JQUERY, CliValidator.parseFrontendFramework("JSP"));
    }

    @Test
    void parseFrontendFramework_invalid_throwsCliException() {
        assertThrows(CliValidator.CliException.class,
                () -> CliValidator.parseFrontendFramework("svelte"));
    }

    @Test
    void validateWithFrontendFrameworkFlag_setsOverride() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(),
                "--frontend-framework", "react"
        });

        assertTrue(result.getFrontendFramework().isPresent());
        assertEquals(FrontendFramework.REACT, result.getFrontendFramework().get());
    }

    @Test
    void validateWithJspFrontendFrameworkFlag_setsOverride() {
        // The override flag selects the framework when auto-detection would be ambiguous
        // (e.g. no package.json in the fixture at all, so auto-detection alone couldn't tell).
        Path java = tempDir.resolve("backend");
        Path frontend = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        frontend.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), frontend.toString(),
                "--frontend-framework", "jsp"
        });

        assertTrue(result.getFrontendFramework().isPresent());
        assertEquals(FrontendFramework.JSP_JQUERY, result.getFrontendFramework().get());
    }

    @Test
    void validateWithoutFrontendFrameworkFlag_optionalIsEmpty() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString()
        });

        assertTrue(result.getFrontendFramework().isEmpty());
    }
}
