package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.common.FieldLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AngularFormFieldExtractorTest {

    private final AngularFormFieldExtractor extractor = new AngularFormFieldExtractor();

    private Path writeComponent(Path dir, String name, String tsSource) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, tsSource, StandardCharsets.UTF_8);
        return file;
    }

    // -----------------------------------------------------------------------
    // Literal labels
    // -----------------------------------------------------------------------

    @Test
    void resolvesLiteralLabelViaForIdPairing(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              selector: 'app-employee-form',
              template: `
                <form [formGroup]="form">
                  <label for="salary">Salary (€) *</label>
                  <input id="salary" formControlName="salary" type="number" />
                </form>
              `
            })
            export class EmployeeFormComponent {}
            """;
        Path file = writeComponent(dir, "employee-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Salary (€)"), labels.get("salary"));
    }

    @Test
    void resolvesLiteralLabelViaAriaLabelOnControl(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <input formControlName="email" aria-label="Email address" type="email" />
              `
            })
            export class ContactFormComponent {}
            """;
        Path file = writeComponent(dir, "contact-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Email address"), labels.get("email"));
    }

    @Test
    void resolvesLiteralLabelViaNearestPrecedingLabelWhenNoForIdPairing(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <div class="form-group">
                  <label>First Name</label>
                  <input formControlName="firstName" type="text" />
                </div>
              `
            })
            export class PersonFormComponent {}
            """;
        Path file = writeComponent(dir, "person-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("First Name"), labels.get("firstName"));
    }

    @Test
    void resolvesLiteralLabelForTextareaAndSelectControls(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <label for="reason">Reason</label>
                <textarea id="reason" formControlName="reason"></textarea>

                <label for="departmentId">Department</label>
                <select id="departmentId" formControlName="departmentId">
                  <option value="1">Engineering</option>
                </select>
              `
            })
            export class SalaryUpdateComponent {}
            """;
        Path file = writeComponent(dir, "salary-update.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Reason"), labels.get("reason"));
        assertEquals(FieldLabel.ofLiteral("Department"), labels.get("departmentId"));
    }

    @Test
    void resolvesLiteralLabelFromExternalTemplateUrl(@TempDir Path dir) throws IOException {
        String html = """
            <label for="name">Department Name *</label>
            <input id="name" formControlName="name" type="text" />
            """;
        Files.writeString(dir.resolve("department-form.component.html"), html, StandardCharsets.UTF_8);

        String src = """
            @Component({
              selector: 'app-department-form',
              templateUrl: './department-form.component.html'
            })
            export class DepartmentFormComponent {}
            """;
        Path file = writeComponent(dir, "department-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Department Name"), labels.get("name"));
    }

    // -----------------------------------------------------------------------
    // i18n keys
    // -----------------------------------------------------------------------

    @Test
    void detectsI18nKeyFromTranslatePipeInLabelContent(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <label for="email">{{ 'employee.email.label' | translate }}</label>
                <input id="email" formControlName="email" type="email" />
              `
            })
            export class EmployeeFormComponent {}
            """;
        Path file = writeComponent(dir, "employee-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofKey("employee.email.label"), labels.get("email"));
        assertTrue(labels.get("email").hasI18nKey());
    }

    @Test
    void detectsI18nKeyFromTranslocoPipe(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <label for="salary">{{ 'employee.salary' | transloco }}</label>
                <input id="salary" formControlName="salary" type="number" />
              `
            })
            export class SalaryComponent {}
            """;
        Path file = writeComponent(dir, "salary.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofKey("employee.salary"), labels.get("salary"));
    }

    @Test
    void detectsI18nKeyFromTranslateStringAttribute(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <label for="firstName" translate="employee.firstName"></label>
                <input id="firstName" formControlName="firstName" type="text" />
              `
            })
            export class NameFormComponent {}
            """;
        Path file = writeComponent(dir, "name-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofKey("employee.firstName"), labels.get("firstName"));
    }

    @Test
    void detectsI18nKeyFromTranslatePropertyBinding(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <label for="phone" [translate]="'employee.phone.label'"></label>
                <input id="phone" formControlName="phone" type="tel" />
              `
            })
            export class PhoneFormComponent {}
            """;
        Path file = writeComponent(dir, "phone-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofKey("employee.phone.label"), labels.get("phone"));
    }

    @Test
    void detectsI18nKeyFromBareTranslateDirective(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <label for="dept" translate>department.name</label>
                <input id="dept" formControlName="dept" type="text" />
              `
            })
            export class DeptFormComponent {}
            """;
        Path file = writeComponent(dir, "dept-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofKey("department.name"), labels.get("dept"));
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void omitsControlWhenNoLabelCanBeResolved(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <input formControlName="hiddenField" type="hidden" />
              `
            })
            export class TrackingFormComponent {}
            """;
        Path file = writeComponent(dir, "tracking-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertFalse(labels.containsKey("hiddenField"));
    }

    @Test
    void returnsEmptyMapForNonexistentFile() {
        Map<String, FieldLabel> labels = extractor.extract("/nonexistent/path.ts");
        assertTrue(labels.isEmpty());
    }

    @Test
    void ignoresDynamicFormControlNameBindings(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <input [formControlName]="dynamicField" type="text" />
              `
            })
            export class DynamicFormComponent {}
            """;
        Path file = writeComponent(dir, "dynamic-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        // The bound form holds a JS expression, not a literal control name — the
        // expression text ("dynamicField") must never be mistaken for a field.
        assertTrue(labels.isEmpty());
        assertFalse(labels.containsKey("dynamicField"));
    }

    @Test
    void staticFormControlNameStillResolvedNextToDynamicBinding(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <input [formControlName]="dynamicField" type="text" />
                <label for="email">Email</label>
                <input id="email" formControlName="email" type="text" />
              `
            })
            export class MixedFormComponent {}
            """;
        Path file = writeComponent(dir, "mixed-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertFalse(labels.containsKey("dynamicField"));
        assertEquals(FieldLabel.ofLiteral("Email"), labels.get("email"));
    }

    // -----------------------------------------------------------------------
    // Template-driven forms (Phase 4): name="x" on an ngModel-bound element
    // -----------------------------------------------------------------------

    @Test
    void resolvesLabelViaNameAttribute_whenNgModelBound(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <label for="email">Email</label>
                <input id="email" name="email" [(ngModel)]="user.email" type="text" />
              `
            })
            export class TemplateDrivenFormComponent {}
            """;
        Path file = writeComponent(dir, "template-driven.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Email"), labels.get("email"));
    }

    @Test
    void resolvesLabelViaNameAttribute_withOneWayNgModelBinding(@TempDir Path dir) throws IOException {
        // [ngModel]="x" (one-way, no banana-in-a-box) must also be recognized as the ngModel signal.
        String src = """
            @Component({
              template: `
                <label for="username">Username</label>
                <input id="username" name="username" [ngModel]="user.username" type="text" />
              `
            })
            export class OneWayNgModelComponent {}
            """;
        Path file = writeComponent(dir, "one-way.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Username"), labels.get("username"));
    }

    // -----------------------------------------------------------------------
    // @angular/localize i18n attribute (Phase 5)
    // -----------------------------------------------------------------------

    @Test
    void localizeI18nAttribute_capturesCustomIdAndSourceText(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              template: `
                <label for="email" i18n="@@emailLabel">Email</label>
                <input id="email" formControlName="email" type="text" />
              `
            })
            export class LocalizeFormComponent {}
            """;
        Path file = writeComponent(dir, "localize.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        FieldLabel label = labels.get("email");
        assertEquals("emailLabel", label.i18nKey());
        assertEquals("Email", label.literal(),
                "the template text is the source message and must be kept as a fallback");
    }

    @Test
    void localizeI18nAttribute_stripsDescriptionAndMeaningPrefixes(@TempDir Path dir) throws IOException {
        // Full syntax is i18n="meaning|description@@id" — both prefixes are optional metadata,
        // only the part after @@ is the translation id.
        String src = """
            @Component({
              template: `
                <label for="first" i18n="Label for the given name@@firstNameLabel">First Name</label>
                <input id="first" formControlName="first" type="text" />
                <label for="last" i18n="form|Family name label@@lastNameLabel">Last Name</label>
                <input id="last" formControlName="last" type="text" />
              `
            })
            export class PrefixFormComponent {}
            """;
        Path file = writeComponent(dir, "prefixes.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals("firstNameLabel", labels.get("first").i18nKey());
        assertEquals("lastNameLabel", labels.get("last").i18nKey());
    }

    @Test
    void localizeI18nAttribute_withoutCustomId_treatedAsPlainLiteral(@TempDir Path dir) throws IOException {
        // Without @@, Angular generates a content-hash id that cannot be mapped back to a
        // template location — so there is no usable key and the text stays a plain literal.
        String src = """
            @Component({
              template: `
                <label for="bare" i18n>Bare Label</label>
                <input id="bare" formControlName="bare" type="text" />
                <label for="desc" i18n="Only a description">Described Label</label>
                <input id="desc" formControlName="desc" type="text" />
              `
            })
            export class GeneratedIdComponent {}
            """;
        Path file = writeComponent(dir, "generated-id.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Bare Label"), labels.get("bare"));
        assertEquals(FieldLabel.ofLiteral("Described Label"), labels.get("desc"));
    }

    @Test
    void ignoresNameAttribute_whenNoNgModelPresent(@TempDir Path dir) throws IOException {
        // A bare `name` attribute unrelated to Angular forms (e.g. a plain <div> or an input
        // with no ngModel binding) must not be mistaken for a form control.
        String src = """
            @Component({
              template: `
                <div name="section-one">Not a form field</div>
                <input name="csrf_token" type="hidden" value="abc123" />
              `
            })
            export class NonFormComponent {}
            """;
        Path file = writeComponent(dir, "non-form.component.ts", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertTrue(labels.isEmpty());
    }
}
