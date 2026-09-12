package com.devmanchego.contextextractor.angular.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AngularI18nCatalogTest {

    private void writeJson(Path dir, String relative, String json) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    @Test
    void resolvesNestedKeysFromConventionalAssetsLayout(@TempDir Path root) throws IOException {
        writeJson(root, "src/assets/i18n/en.json", """
            {
              "employee": {
                "firstName": { "label": "First Name" },
                "email": "Email"
              }
            }
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertFalse(catalog.isEmpty());
        assertEquals("en", catalog.primaryLocale());
        assertEquals("First Name", catalog.resolve("employee.firstName.label").orElseThrow());
        assertEquals("Email", catalog.resolve("employee.email").orElseThrow());
    }

    @Test
    void resolvesFlatDottedKeys(@TempDir Path root) throws IOException {
        writeJson(root, "src/assets/i18n/en.json", """
            {
              "employee.salary.label": "Salary"
            }
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertEquals("Salary", catalog.resolve("employee.salary.label").orElseThrow());
    }

    @Test
    void prefersEnglishLocaleWhenMultiplePresent(@TempDir Path root) throws IOException {
        writeJson(root, "src/assets/i18n/es.json", """
            { "employee": { "email": "Correo" } }
            """);
        writeJson(root, "src/assets/i18n/en.json", """
            { "employee": { "email": "Email" } }
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertEquals("en", catalog.primaryLocale());
        assertEquals("Email", catalog.resolve("employee.email").orElseThrow());
    }

    @Test
    void fallsBackToAlphabeticallyFirstLocaleWhenNoEnglish(@TempDir Path root) throws IOException {
        writeJson(root, "src/assets/i18n/fr.json", """
            { "greeting": "Bonjour" }
            """);
        writeJson(root, "src/assets/i18n/de.json", """
            { "greeting": "Hallo" }
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertEquals("de", catalog.primaryLocale());
        assertEquals("Hallo", catalog.resolve("greeting").orElseThrow());
    }

    @Test
    void returnsEmptyForUnknownKey(@TempDir Path root) throws IOException {
        writeJson(root, "src/assets/i18n/en.json", "{ \"a\": \"b\" }");

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertTrue(catalog.resolve("does.not.exist").isEmpty());
    }

    @Test
    void ignoresJsonFilesOutsideI18nDirectories(@TempDir Path root) throws IOException {
        writeJson(root, "src/assets/config/settings.json", "{ \"key\": \"value\" }");

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertTrue(catalog.isEmpty());
    }

    @Test
    void ignoresI18nFilesUnderNodeModules(@TempDir Path root) throws IOException {
        writeJson(root, "node_modules/some-lib/i18n/en.json", "{ \"lib\": \"value\" }");

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertTrue(catalog.isEmpty());
    }

    @Test
    void emptyCatalogResolvesNothing() {
        AngularI18nCatalog catalog = AngularI18nCatalog.empty();
        assertTrue(catalog.isEmpty());
        assertTrue(catalog.resolve("any.key").isEmpty());
        assertNull(catalog.primaryLocale());
    }

    @Test
    void loadReturnsEmptyForNonexistentDirectory() {
        AngularI18nCatalog catalog = AngularI18nCatalog.load(Path.of("/nonexistent/dir/xyz"));
        assertTrue(catalog.isEmpty());
    }

    // -----------------------------------------------------------------------
    // @angular/localize XLIFF catalogs (Phase 5)
    // -----------------------------------------------------------------------

    private void writeFile(Path dir, String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void xliff12_prefersTargetOverSource(@TempDir Path root) throws IOException {
        writeFile(root, "src/locale/messages.es.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
              <file source-language="en" target-language="es" datatype="plaintext" original="ng2.template">
                <body>
                  <trans-unit id="emailLabel" datatype="html">
                    <source>Email</source>
                    <target>Correo electronico</target>
                  </trans-unit>
                </body>
              </file>
            </xliff>
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertFalse(catalog.isEmpty());
        assertEquals("Correo electronico", catalog.resolve("emailLabel").orElseThrow(),
                "a translated <target> must win over the original <source>");
    }

    @Test
    void xliff12_fallsBackToSourceWhenNoTarget(@TempDir Path root) throws IOException {
        // The extraction output (messages.xlf) has no <target> until it is translated.
        writeFile(root, "src/locale/messages.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
              <file source-language="en" datatype="plaintext" original="ng2.template">
                <body>
                  <trans-unit id="emailLabel" datatype="html">
                    <source>Email</source>
                  </trans-unit>
                </body>
              </file>
            </xliff>
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertEquals("Email", catalog.resolve("emailLabel").orElseThrow(),
                "an untranslated unit must still yield its source text");
    }

    @Test
    void xliff20_unitSegmentStructureParsed(@TempDir Path root) throws IOException {
        writeFile(root, "src/locale/messages.fr.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="fr">
              <file original="ng.template" id="ngi18n">
                <unit id="usernameLabel">
                  <segment>
                    <source>Username</source>
                    <target>Nom d utilisateur</target>
                  </segment>
                </unit>
              </file>
            </xliff>
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertEquals("Nom d utilisateur", catalog.resolve("usernameLabel").orElseThrow(),
                "XLIFF 2.0 uses <unit><segment> instead of <trans-unit>");
    }

    @Test
    void xliff_inlinePlaceholderMarkupSkipped(@TempDir Path root) throws IOException {
        // Angular emits <x/> placeholders for interpolations; only the surrounding text is usable.
        writeFile(root, "src/locale/messages.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
              <file source-language="en" datatype="plaintext" original="ng2.template">
                <body>
                  <trans-unit id="greeting" datatype="html">
                    <source>Hello <x id="INTERPOLATION" equiv-text="{{name}}"/>!</source>
                  </trans-unit>
                </body>
              </file>
            </xliff>
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertEquals("Hello !", catalog.resolve("greeting").orElseThrow(),
                "placeholder elements contribute no text; surrounding text is preserved");
    }

    @Test
    void xliff_prefersEnglishLocaleWhenMultiplePresent(@TempDir Path root) throws IOException {
        writeFile(root, "src/locale/messages.es.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
              <file source-language="en" target-language="es" original="ng2.template">
                <body>
                  <trans-unit id="k"><source>Email</source><target>Correo</target></trans-unit>
                </body>
              </file>
            </xliff>
            """);
        writeFile(root, "src/locale/messages.en.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
              <file source-language="en" target-language="en" original="ng2.template">
                <body>
                  <trans-unit id="k"><source>Email</source><target>Email</target></trans-unit>
                </body>
              </file>
            </xliff>
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertEquals("en", catalog.primaryLocale());
        assertEquals("Email", catalog.resolve("k").orElseThrow());
    }

    @Test
    void jsonCatalogTakesPrecedenceOverXliffWhenBothPresent(@TempDir Path root) throws IOException {
        // A project migrating between the two would otherwise get a non-deterministic mix.
        // JSON wins because it is the pre-existing supported format — no behavior regression.
        writeJson(root, "src/assets/i18n/en.json", """
            { "shared": "from json" }
            """);
        writeFile(root, "src/locale/messages.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
              <file source-language="en" original="ng2.template">
                <body>
                  <trans-unit id="shared"><source>from xliff</source></trans-unit>
                </body>
              </file>
            </xliff>
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertEquals("from json", catalog.resolve("shared").orElseThrow());
    }

    @Test
    void malformedXliffDoesNotThrow(@TempDir Path root) throws IOException {
        writeFile(root, "src/locale/messages.xlf", "<xliff><unclosed>");

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertTrue(catalog.isEmpty(), "a broken file degrades to an empty catalog rather than failing the run");
    }

    @Test
    void ignoresXliffFilesUnderNodeModules(@TempDir Path root) throws IOException {
        writeFile(root, "node_modules/some-lib/locale/messages.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
              <file source-language="en" original="ng2.template">
                <body><trans-unit id="lib"><source>Library</source></trans-unit></body>
              </file>
            </xliff>
            """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(root);

        assertTrue(catalog.isEmpty());
    }
}
