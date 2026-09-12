package com.devmanchego.contextextractor;

import com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog;
import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for {@link Application#extractUiLabels}: the traceability document
 * must show the resolved i18n message, not the raw translation key, once a catalog is
 * available — previously it always fell back to the key even when the key was resolvable.
 */
class ApplicationUiLabelsTest {

    private Path writeComponent(Path dir, String name, String tsSource) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, tsSource, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void i18nKeyLabel_resolvedAgainstCatalog(@TempDir Path dir) throws IOException {
        Path i18nDir = dir.resolve("assets/i18n");
        Files.createDirectories(i18nDir);
        Files.writeString(i18nDir.resolve("en.json"), """
                { "employee": { "email": "Email address" } }
                """);

        Path component = writeComponent(dir, "employee-form.component.ts", """
                @Component({
                  template: `
                    <label translate="employee.email">employee.email</label>
                    <input id="email" formControlName="email" type="text" />
                  `
                })
                export class EmployeeFormComponent {}
                """);

        AngularI18nCatalog catalog = AngularI18nCatalog.load(dir);
        assertFalse(catalog.isEmpty(), "Precondition: catalog must have loaded the fixture JSON");

        ComponentInfo componentInfo = new ComponentInfo(
                "EmployeeFormComponent", component.toString(), "app-employee-form", List.of());
        AngularProject project = new AngularProject(
                AngularProject.ParsingStrategy.JVM_ANTLR, List.of(componentInfo), List.of(), List.of());

        Map<String, Map<String, String>> uiLabels = Application.extractUiLabels(project, catalog);

        Map<String, String> fields = uiLabels.get("EmployeeFormComponent");
        assertNotNull(fields, "Expected labels extracted for EmployeeFormComponent");
        assertEquals("Email address", fields.get("email"),
                "Label must be the resolved i18n message, not the raw key 'employee.email'");
    }

    @Test
    void unresolvableI18nKey_fallsBackToRawKey(@TempDir Path dir) throws IOException {
        Path component = writeComponent(dir, "orphan-form.component.ts", """
                @Component({
                  template: `
                    <label translate="orphan.unresolved.key">orphan.unresolved.key</label>
                    <input id="field" formControlName="field" type="text" />
                  `
                })
                export class OrphanFormComponent {}
                """);

        ComponentInfo componentInfo = new ComponentInfo(
                "OrphanFormComponent", component.toString(), "app-orphan-form", List.of());
        AngularProject project = new AngularProject(
                AngularProject.ParsingStrategy.JVM_ANTLR, List.of(componentInfo), List.of(), List.of());

        Map<String, Map<String, String>> uiLabels = Application.extractUiLabels(project, AngularI18nCatalog.empty());

        Map<String, String> fields = uiLabels.get("OrphanFormComponent");
        assertNotNull(fields);
        assertEquals("orphan.unresolved.key", fields.get("field"),
                "With no catalog entry, the raw key remains a legible fallback");
    }
}
