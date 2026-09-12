package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.common.ValidatorInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FormValidationExtractorTest {

    private final AngularFormValidationExtractor extractor = new AngularFormValidationExtractor();

    private Path writeComponent(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    /** rawText of each validator, in order — the display string shown in documentation. */
    private List<String> rawTexts(List<ValidatorInfo> validators) {
        return validators.stream().map(ValidatorInfo::rawText).collect(Collectors.toList());
    }

    /** name (runtime error key) of each validator, in order — used to correlate with template errors. */
    private List<String> names(List<ValidatorInfo> validators) {
        return validators.stream().map(ValidatorInfo::name).collect(Collectors.toList());
    }

    @Test
    void extractsValidatorsFromReactiveForm(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              selector: 'app-product-form'
            })
            export class ProductFormComponent implements OnInit {
              form!: FormGroup;

              constructor(private fb: FormBuilder) {}

              ngOnInit(): void {
                this.form = this.fb.group({
                  name: ['', [Validators.required, Validators.minLength(3)]],
                  price: [0, [Validators.required, Validators.min(0.01)]],
                  stock: [0, [Validators.min(0)]],
                  category: ['', [Validators.required]]
                });
              }
            }
            """;
        Path file = writeComponent(dir, "product-form.component.ts", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(4, rules.size());
        assertEquals(List.of("required", "minLength(3)"), rawTexts(rules.get("name")));
        assertEquals(List.of("required", "min(0.01)"), rawTexts(rules.get("price")));
        assertEquals(List.of("min(0)"), rawTexts(rules.get("stock")));
        assertEquals(List.of("required"), rawTexts(rules.get("category")));
    }

    @Test
    void extractsEmailValidator(@TempDir Path dir) throws IOException {
        String src = """
            ngOnInit(): void {
              this.form = this.fb.group({
                email: ['', [Validators.required, Validators.email]],
                name: ['', Validators.required]
              });
            }
            """;
        Path file = writeComponent(dir, "account-form.component.ts", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(List.of("required", "email"), rawTexts(rules.get("email")));
        assertEquals(List.of("required"), rawTexts(rules.get("name")));
    }

    @Test
    void extractsMaxLengthValidator(@TempDir Path dir) throws IOException {
        String src = """
            this.form = this.fb.group({
              username: ['', Validators.maxLength(50)],
              bio: ['', Validators.maxLength(500)]
            });
            """;
        Path file = writeComponent(dir, "profile.component.ts", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(List.of("maxLength(50)"), rawTexts(rules.get("username")));
        assertEquals(List.of("maxLength(500)"), rawTexts(rules.get("bio")));
    }

    @Test
    void ignoresComponentsWithoutFormGroup(@TempDir Path dir) throws IOException {
        String src = """
            @Component({
              selector: 'app-display'
            })
            export class DisplayComponent {
              data: any;
            }
            """;
        Path file = writeComponent(dir, "display.component.ts", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertTrue(rules.isEmpty());
    }

    @Test
    void returnsEmptyMapForNonexistentFile() {
        Map<String, List<ValidatorInfo>> rules = extractor.extract("/nonexistent/path.ts");
        assertTrue(rules.isEmpty());
    }

    @Test
    void preservesOrderOfValidators(@TempDir Path dir) throws IOException {
        String src = """
            this.form = this.fb.group({
              field: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(100)]]
            });
            """;
        Path file = writeComponent(dir, "form.component.ts", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(
            List.of("required", "minLength(5)", "maxLength(100)"),
            rawTexts(rules.get("field"))
        );
    }

    @Test
    void minAndMaxLengthUseLowercaseRuntimeErrorKey(@TempDir Path dir) throws IOException {
        // Angular's control.errors exposes these two as "minlength"/"maxlength" (no camelCase),
        // unlike every other built-in validator — ValidatorInfo.name() must match that exactly
        // so a future template correlation step (errors?.minlength) finds the right validator.
        String src = """
            this.form = this.fb.group({
              username: ['', [Validators.minLength(3), Validators.maxLength(50), Validators.required]]
            });
            """;
        Path file = writeComponent(dir, "username-form.component.ts", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(List.of("minlength", "maxlength", "required"), names(rules.get("username")));
        // Display text keeps the camelCase method name as written, unaffected by the name normalization.
        assertEquals(List.of("minLength(3)", "maxLength(50)", "required"), rawTexts(rules.get("username")));
    }

    // -----------------------------------------------------------------------
    // Template-driven forms (Phase 4): HTML5 validation attributes on ngModel-bound elements
    // -----------------------------------------------------------------------

    private Path writeComponentWithTemplate(Path dir, String className, String template) throws IOException {
        String src = """
                @Component({
                  selector: 'app-form',
                  template: `%s`
                })
                export class %s {}
                """.formatted(template, className);
        return writeComponent(dir, className + ".ts", src);
    }

    @Test
    void templateDriven_requiredAndMinlengthAttributes_extracted(@TempDir Path dir) throws IOException {
        String template = """
                <input name="username" [(ngModel)]="user.username" required minlength="3">
                """;
        Path file = writeComponentWithTemplate(dir, "UsernameFormComponent", template);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(List.of("required", "minlength"), names(rules.get("username")));
        assertEquals(List.of("required", "minlength(3)"), rawTexts(rules.get("username")));
    }

    @Test
    void templateDriven_propertyBindingStyleAttribute_extracted(@TempDir Path dir) throws IOException {
        String template = """
                <input name="bio" [(ngModel)]="user.bio" [maxlength]="20">
                """;
        Path file = writeComponentWithTemplate(dir, "BioFormComponent", template);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(List.of("maxlength(20)"), rawTexts(rules.get("bio")));
    }

    @Test
    void templateDriven_emailBooleanAttribute_notFalselyMatchedFromQuotedAttributeValues(
            @TempDir Path dir) throws IOException {
        // "email" appears inside type="email" and name="email" too — the bare boolean
        // attribute must be recognized only once, not confused with those quoted occurrences.
        String template = """
                <input type="email" name="email" [(ngModel)]="user.email" required email>
                """;
        Path file = writeComponentWithTemplate(dir, "EmailFormComponent", template);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(List.of("required", "email"), names(rules.get("email")));
    }

    @Test
    void templateDriven_multipleNgModelFields_eachGetsOwnValidators(@TempDir Path dir) throws IOException {
        String template = """
                <input name="firstName" [(ngModel)]="user.firstName" required>
                <input name="age" [(ngModel)]="user.age" [min]="18" [max]="99">
                """;
        Path file = writeComponentWithTemplate(dir, "MultiFieldFormComponent", template);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(List.of("required"), names(rules.get("firstName")));
        assertEquals(List.of("min(18)", "max(99)"), rawTexts(rules.get("age")));
    }

    @Test
    void templateDriven_ignoredWhenReactiveFormGroupAlreadyPresent(@TempDir Path dir) throws IOException {
        // A component with a reactive FormGroup takes the reactive path exclusively — an
        // unrelated ngModel-bound element elsewhere in the same template is not also scanned.
        // Documented limitation: mixed reactive + template-driven usage in one component only
        // surfaces the reactive fields.
        String src = """
                @Component({
                  selector: 'app-mixed',
                  template: `<input name="search" [(ngModel)]="query" required>`
                })
                export class MixedFormComponent {
                  form = this.fb.group({
                    email: ['', [Validators.required]]
                  });
                }
                """;
        Path file = writeComponent(dir, "MixedFormComponent.ts", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(1, rules.size());
        assertTrue(rules.containsKey("email"));
        assertFalse(rules.containsKey("search"));
    }

    @Test
    void templateDriven_noNgModelInTemplate_yieldsEmptyMap(@TempDir Path dir) throws IOException {
        String template = "<div>{{ message }}</div>";
        Path file = writeComponentWithTemplate(dir, "DisplayOnlyComponent", template);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertTrue(rules.isEmpty());
    }
}
