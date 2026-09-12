package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog;
import com.devmanchego.contextextractor.angular.model.ComponentInfo;
import com.devmanchego.contextextractor.angular.model.RouteNode;
import com.devmanchego.contextextractor.matching.EndpointMatcher;
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
 * End-to-end coverage of the "Form Fields" table in {@link FrontendPagesRenderer}: literal
 * labels, i18n key resolution against an {@link AngularI18nCatalog}, and the association with
 * validation rules extracted from the component's {@code FormBuilder.group}.
 */
class FrontendPagesRendererFormFieldsTest {

    private static final EndpointMatcher.MatchResult EMPTY_MATCH =
            new EndpointMatcher.MatchResult(List.of(), List.of(), List.of());

    private ComponentInfo writeFormComponent(Path dir, String className, String template) throws IOException {
        String src = """
            @Component({
              selector: 'app-form',
              template: `%s`
            })
            export class %s {
              form = this.fb.group({
                firstName: ['', [Validators.required, Validators.minLength(2)]],
                email: ['', [Validators.required, Validators.email]]
              });
            }
            """.formatted(template, className);
        Path file = dir.resolve(className + ".ts");
        Files.writeString(file, src, StandardCharsets.UTF_8);
        return new ComponentInfo(className, file.toString(), "app-form", List.of());
    }

    private RouteNode pageRoute(String path, String componentName) {
        return new RouteNode(path, componentName, null, null, false, List.of());
    }

    private String render(ComponentInfo comp, RouteNode route, AngularI18nCatalog catalog) {
        return new FrontendPagesRenderer(catalog).render(
                List.of(route), Map.of(comp.getClassName(), comp), EMPTY_MATCH,
                List.of(), false, com.devmanchego.contextextractor.frontend.FrontendFramework.ANGULAR);
    }

    @Test
    void rendersLiteralLabelsWithValidations(@TempDir Path dir) throws IOException {
        String template = """
            <label for="firstName">First Name</label>
            <input id="firstName" formControlName="firstName" />
            <label for="email">Email Address</label>
            <input id="email" formControlName="email" />
            """;
        ComponentInfo comp = writeFormComponent(dir, "LiteralFormComponent", template);

        String md = render(comp, pageRoute("form", "LiteralFormComponent"), AngularI18nCatalog.empty());

        assertTrue(md.contains("**Form Fields:**"), "should render the Form Fields block");
        // 3-column table (no i18n column) since there are no i18n keys
        assertTrue(md.contains("| Field | Label | Validations |"), "should use the 3-column header");
        assertTrue(md.contains("| `firstName` | First Name | required, minLength(2) |"));
        assertTrue(md.contains("| `email` | Email Address | required, email |"));
    }

    @Test
    void resolvesI18nKeysToMessagesAndShowsKeyColumn(@TempDir Path dir) throws IOException {
        String template = """
            <label for="firstName">{{ 'employee.firstName.label' | translate }}</label>
            <input id="firstName" formControlName="firstName" />
            <label for="email">{{ 'employee.email.label' | translate }}</label>
            <input id="email" formControlName="email" />
            """;
        ComponentInfo comp = writeFormComponent(dir, "I18nFormComponent", template);

        // Build a catalog from JSON on disk
        Path i18nDir = dir.resolve("assets/i18n");
        Files.createDirectories(i18nDir);
        Files.writeString(i18nDir.resolve("en.json"), """
            {
              "employee": {
                "firstName": { "label": "First Name" },
                "email": { "label": "Email Address" }
              }
            }
            """, StandardCharsets.UTF_8);
        AngularI18nCatalog catalog = AngularI18nCatalog.load(dir);

        String md = render(comp, pageRoute("form", "I18nFormComponent"), catalog);

        // 4-column table because i18n keys are present
        assertTrue(md.contains("| Field | Label | i18n Key | Validations |"), "should use the 4-column header");
        assertTrue(md.contains("| `firstName` | First Name | `employee.firstName.label` | required, minLength(2) |"),
                "should resolve the key to its message and show the key");
        assertTrue(md.contains("| `email` | Email Address | `employee.email.label` | required, email |"));
    }

    @Test
    void showsUnresolvedMarkerWhenKeyMissingFromCatalog(@TempDir Path dir) throws IOException {
        String template = """
            <label for="firstName">{{ 'employee.firstName.label' | translate }}</label>
            <input id="firstName" formControlName="firstName" />
            <label for="email">{{ 'employee.email.label' | translate }}</label>
            <input id="email" formControlName="email" />
            """;
        ComponentInfo comp = writeFormComponent(dir, "PartialI18nComponent", template);

        Path i18nDir = dir.resolve("assets/i18n");
        Files.createDirectories(i18nDir);
        Files.writeString(i18nDir.resolve("en.json"), """
            { "employee": { "firstName": { "label": "First Name" } } }
            """, StandardCharsets.UTF_8);
        AngularI18nCatalog catalog = AngularI18nCatalog.load(dir);

        String md = render(comp, pageRoute("form", "PartialI18nComponent"), catalog);

        assertTrue(md.contains("| `firstName` | First Name | `employee.firstName.label` | required, minLength(2) |"));
        assertTrue(md.contains("| `email` | *(unresolved)* | `employee.email.label` | required, email |"),
                "unknown key should render the unresolved marker but still show the key");
    }

    @Test
    void showsValidationMessagesColumnWhenConventionKeysResolve(@TempDir Path dir) throws IOException {
        String template = """
            <label for="firstName">First Name</label>
            <input id="firstName" formControlName="firstName" />
            <label for="email">Email Address</label>
            <input id="email" formControlName="email" />
            """;
        ComponentInfo comp = writeFormComponent(dir, "ValidationMessagesComponent", template);

        Path i18nDir = dir.resolve("assets/i18n");
        Files.createDirectories(i18nDir);
        Files.writeString(i18nDir.resolve("en.json"), """
            {
              "errors": {
                "firstName": { "required": "First name is required" },
                "email": { "required": "Email is required" }
              }
            }
            """, StandardCharsets.UTF_8);
        AngularI18nCatalog catalog = AngularI18nCatalog.load(dir);

        String md = render(comp, pageRoute("form", "ValidationMessagesComponent"), catalog);

        // 4-column table: no label i18n keys here, but the Validation Messages column appears
        // because the "required" validator resolves via the errors.<field>.<validator> convention.
        assertTrue(md.contains("| Field | Label | Validations | Validation Messages |"),
                "should add the Validation Messages column without the (unrelated) i18n Key column");
        assertTrue(md.contains(
                "| `firstName` | First Name | required, minLength(2) | `required`: First name is required |"),
                "resolved validator shown; minlength (no catalog entry) omitted as noise");
        assertTrue(md.contains(
                "| `email` | Email Address | required, email | `required`: Email is required |"),
                "resolved validator shown; email format validator (no catalog entry) omitted");
    }

    @Test
    void showsTemplateExtractedLiteralMessageWithoutAnyI18nCatalog(@TempDir Path dir) throws IOException {
        String template = """
            <label for="firstName">First Name</label>
            <input id="firstName" formControlName="firstName" />
            <mat-error *ngIf="form.get('firstName').hasError('required')">First name is required</mat-error>
            <label for="email">Email Address</label>
            <input id="email" formControlName="email" />
            """;
        ComponentInfo comp = writeFormComponent(dir, "TemplateLiteralComponent", template);

        // No i18n catalog at all — the message must still surface via template correlation.
        String md = render(comp, pageRoute("form", "TemplateLiteralComponent"), AngularI18nCatalog.empty());

        assertTrue(md.contains("| Field | Label | Validations | Validation Messages |"));
        assertTrue(md.contains(
                "| `firstName` | First Name | required, minLength(2) | `required`: First name is required |"),
                "the *ngIf-guarded mat-error content must be correlated to the 'required' validator");
    }

    @Test
    void templateExtractedMessageTakesPriorityOverConventionGuess(@TempDir Path dir) throws IOException {
        String template = """
            <label for="email">Email Address</label>
            <input id="email" formControlName="email" />
            <mat-error *ngIf="form.get('email').hasError('required')">Custom template message</mat-error>
            """;
        ComponentInfo comp = writeFormComponent(dir, "PriorityComponent", template);

        // The convention resolver would ALSO resolve errors.email.required, but to different text —
        // the template extraction (real evidence) must win.
        Path i18nDir = dir.resolve("assets/i18n");
        Files.createDirectories(i18nDir);
        Files.writeString(i18nDir.resolve("en.json"), """
            { "errors": { "email": { "required": "Convention message" } } }
            """, StandardCharsets.UTF_8);
        AngularI18nCatalog catalog = AngularI18nCatalog.load(dir);

        String md = render(comp, pageRoute("form", "PriorityComponent"), catalog);

        assertTrue(md.contains("`required`: Custom template message"),
                "template-extracted message must win over the convention guess");
        assertFalse(md.contains("Convention message"),
                "convention guess must be fully discarded once the template supplies real evidence");
    }

    // -----------------------------------------------------------------------
    // Template-driven forms (Phase 4): end-to-end through the renderer
    // -----------------------------------------------------------------------

    private ComponentInfo writeTemplateDrivenFormComponent(Path dir, String className, String template)
            throws IOException {
        String src = """
            @Component({
              selector: 'app-form',
              template: `%s`
            })
            export class %s {}
            """.formatted(template, className);
        Path file = dir.resolve(className + ".ts");
        Files.writeString(file, src, StandardCharsets.UTF_8);
        return new ComponentInfo(className, file.toString(), "app-form", List.of());
    }

    @Test
    void templateDrivenForm_fieldLabelValidationsAndMessage_allSurfaceEndToEnd(@TempDir Path dir)
            throws IOException {
        // No reactive FormGroup anywhere — field, label, validators, and message must all come
        // from the template alone (HTML5 attributes + *ngIf correlation), proving the whole
        // pipeline (Phases 1-3) works unmodified once Phase 4 supplies template-driven fields.
        String template = """
            <label for="email">Email Address</label>
            <input id="email" name="email" [(ngModel)]="user.email" #email="ngModel" required email>
            <div *ngIf="email.errors?.required">Email is required</div>
            """;
        ComponentInfo comp = writeTemplateDrivenFormComponent(dir, "TemplateDrivenEndToEndComponent", template);

        String md = render(comp, pageRoute("form", "TemplateDrivenEndToEndComponent"), AngularI18nCatalog.empty());

        assertTrue(md.contains("| `email` | Email Address | required, email | `required`: Email is required |"),
                "field, label, validators, and message must all be present for a pure template-driven form");
    }

    // -----------------------------------------------------------------------
    // @angular/localize end-to-end (Phase 5): i18n="@@id" template + XLIFF catalog
    // -----------------------------------------------------------------------

    private void writeXliff(Path dir, String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void localizeKeyResolvedFromXliffCatalog(@TempDir Path dir) throws IOException {
        String template = """
            <label for="firstName" i18n="@@firstNameLabel">First Name</label>
            <input id="firstName" formControlName="firstName" />
            <label for="email" i18n="@@emailLabel">Email</label>
            <input id="email" formControlName="email" />
            """;
        ComponentInfo comp = writeFormComponent(dir, "LocalizeComponent", template);

        writeXliff(dir, "src/locale/messages.es.xlf", """
            <?xml version="1.0" encoding="UTF-8" ?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
              <file source-language="en" target-language="es" original="ng2.template">
                <body>
                  <trans-unit id="firstNameLabel"><source>First Name</source><target>Nombre</target></trans-unit>
                  <trans-unit id="emailLabel"><source>Email</source><target>Correo</target></trans-unit>
                </body>
              </file>
            </xliff>
            """);
        AngularI18nCatalog catalog = AngularI18nCatalog.load(dir);

        String md = render(comp, pageRoute("form", "LocalizeComponent"), catalog);

        assertTrue(md.contains("| Field | Label | i18n Key | Validations |"),
                "localize ids must populate the i18n Key column");
        assertTrue(md.contains("| `firstName` | Nombre | `firstNameLabel` | required, minLength(2) |"),
                "the XLIFF <target> translation must be shown as the label");
        assertTrue(md.contains("| `email` | Correo | `emailLabel` | required, email |"));
    }

    @Test
    void localizeKeyMissingFromCatalog_fallsBackToInlineSourceText(@TempDir Path dir) throws IOException {
        // Regression guard: before localize support, this label rendered as the plain literal
        // "First Name". Detecting the key must not downgrade that to *(unresolved)* — the source
        // message is written right there in the template.
        String template = """
            <label for="firstName" i18n="@@firstNameLabel">First Name</label>
            <input id="firstName" formControlName="firstName" />
            <label for="email" formControlName="email" i18n="@@emailLabel">Email</label>
            <input id="email" formControlName="email" />
            """;
        ComponentInfo comp = writeFormComponent(dir, "LocalizeNoCatalogComponent", template);

        String md = render(comp, pageRoute("form", "LocalizeNoCatalogComponent"), AngularI18nCatalog.empty());

        assertTrue(md.contains("| `firstName` | First Name | `firstNameLabel` | required, minLength(2) |"),
                "unresolvable key must still show the inline source text, not the unresolved marker");
        assertFalse(md.contains("*(unresolved)*"),
                "the marker is only for keys with no inline source text available");
    }
}
