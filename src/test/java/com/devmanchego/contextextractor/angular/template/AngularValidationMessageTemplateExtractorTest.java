package com.devmanchego.contextextractor.angular.template;

import com.devmanchego.contextextractor.angular.i18n.AngularI18nCatalog;
import com.devmanchego.contextextractor.common.ValidationMessage;
import com.devmanchego.contextextractor.common.ValidationMessageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first fixtures for {@link AngularValidationMessageTemplateExtractor}: correlates a
 * validator ({@code form.get('field').hasError('validator')} and equivalent spellings) with the
 * error-display element it guards ({@code *ngIf}), extracting either a hardcoded literal message
 * or an i18n key resolved against the catalog. Each case states the exact expected outcome
 * before any implementation exists.
 */
class AngularValidationMessageTemplateExtractorTest {

    private final AngularValidationMessageTemplateExtractor extractor = new AngularValidationMessageTemplateExtractor();

    private Path writeComponent(Path dir, String name, String template) throws IOException {
        String src = """
                @Component({
                  selector: 'app-form',
                  template: `%s`
                })
                export class TestFormComponent {}
                """.formatted(template);
        Path file = dir.resolve(name);
        Files.writeString(file, src, StandardCharsets.UTF_8);
        return file;
    }

    private AngularI18nCatalog catalogFromJson(Path dir, String json) throws IOException {
        Path i18nDir = dir.resolve("assets/i18n");
        Files.createDirectories(i18nDir);
        Files.writeString(i18nDir.resolve("en.json"), json, StandardCharsets.UTF_8);
        return AngularI18nCatalog.load(dir);
    }

    // 1. form.get('field').hasError('validator') wrapping an i18n pipe whose key IS in the catalog → RESOLVED.
    @Test
    void formGetHasError_withI18nPipe_resolvesAgainstCatalog(@TempDir Path dir) throws IOException {
        String template = """
                <mat-error *ngIf="form.get('email').hasError('required')">
                  {{ 'errors.email.required' | translate }}
                </mat-error>
                """;
        Path file = writeComponent(dir, "resolved.component.ts", template);
        AngularI18nCatalog catalog = catalogFromJson(dir, """
                { "errors": { "email": { "required": "Email is required" } } }
                """);

        Map<String, Map<String, ValidationMessage>> result = extractor.extract(file.toString(), catalog);

        ValidationMessage msg = result.get("email").get("required");
        assertEquals(ValidationMessageStatus.RESOLVED, msg.status());
        assertEquals("Email is required", msg.text());
        assertEquals("errors.email.required", msg.i18nKey());
    }

    // 2. Same pattern, but the key has no entry in the catalog → KEY_UNRESOLVED (the key WAS observed
    //    in the template — unlike Phase 2's guess, this is real evidence of a missing translation).
    @Test
    void formGetHasError_withI18nPipe_missingFromCatalog_yieldsKeyUnresolved(@TempDir Path dir) throws IOException {
        String template = """
                <mat-error *ngIf="form.get('email').hasError('required')">
                  {{ 'errors.email.required' | translate }}
                </mat-error>
                """;
        Path file = writeComponent(dir, "unresolved.component.ts", template);

        Map<String, Map<String, ValidationMessage>> result =
                extractor.extract(file.toString(), AngularI18nCatalog.empty());

        ValidationMessage msg = result.get("email").get("required");
        assertEquals(ValidationMessageStatus.KEY_UNRESOLVED, msg.status());
        assertEquals("errors.email.required", msg.i18nKey());
        assertNull(msg.text());
    }

    // 3. form.get('field').hasError('validator') wrapping hardcoded text (no i18n) → LITERAL.
    @Test
    void formGetHasError_withHardcodedText_yieldsLiteral(@TempDir Path dir) throws IOException {
        String template = """
                <mat-error *ngIf="form.get('email').hasError('required')">
                  Email is required
                </mat-error>
                """;
        Path file = writeComponent(dir, "literal.component.ts", template);

        Map<String, Map<String, ValidationMessage>> result =
                extractor.extract(file.toString(), AngularI18nCatalog.empty());

        ValidationMessage msg = result.get("email").get("required");
        assertEquals(ValidationMessageStatus.LITERAL, msg.status());
        assertEquals("Email is required", msg.text());
        assertNull(msg.i18nKey());
    }

    // 4. Bare getter form (the template references a field-named identifier directly, a common
    //    convenience-getter pattern) with .errors?.validator instead of .hasError(...).
    @Test
    void bareIdentifierErrorsProperty_yieldsLiteral(@TempDir Path dir) throws IOException {
        String template = """
                <div class="error" *ngIf="email.errors?.minlength">Email is too short</div>
                """;
        Path file = writeComponent(dir, "bare-getter.component.ts", template);

        Map<String, Map<String, ValidationMessage>> result =
                extractor.extract(file.toString(), AngularI18nCatalog.empty());

        ValidationMessage msg = result.get("email").get("minlength");
        assertEquals(ValidationMessageStatus.LITERAL, msg.status());
        assertEquals("Email is too short", msg.text());
    }

    // 5. Two *ngIf blocks in the same template, for two different validators on the same field —
    //    both must be extracted independently, without one clobbering the other.
    @Test
    void multipleValidatorsOnSameField_extractedIndependently(@TempDir Path dir) throws IOException {
        String template = """
                <mat-error *ngIf="form.get('password').hasError('required')">Password is required</mat-error>
                <mat-error *ngIf="form.get('password').hasError('minlength')">Password is too short</mat-error>
                """;
        Path file = writeComponent(dir, "multi.component.ts", template);

        Map<String, Map<String, ValidationMessage>> result =
                extractor.extract(file.toString(), AngularI18nCatalog.empty());

        assertEquals("Password is required", result.get("password").get("required").text());
        assertEquals("Password is too short", result.get("password").get("minlength").text());
    }

    // 6. Self-closing error element (no content to correlate) — must not crash, and produces
    //    no entry for that validator (the field is simply absent from the result, or if present
    //    via another match, this specific validator key is not populated).
    @Test
    void selfClosingElement_producesNoEntryForThatValidator(@TempDir Path dir) throws IOException {
        String template = """
                <mat-error *ngIf="form.get('email').hasError('required')" />
                """;
        Path file = writeComponent(dir, "self-closing.component.ts", template);

        Map<String, Map<String, ValidationMessage>> result =
                extractor.extract(file.toString(), AngularI18nCatalog.empty());

        assertTrue(result.isEmpty() || !result.getOrDefault("email", Map.of()).containsKey("required"));
    }

    // 7. form.controls['field'].hasError('validator') — bracket-accessor spelling.
    @Test
    void formControlsBracketAccessor_extractedCorrectly(@TempDir Path dir) throws IOException {
        String template = """
                <mat-error *ngIf="form.controls['username'].hasError('minlength')">Too short</mat-error>
                """;
        Path file = writeComponent(dir, "bracket.component.ts", template);

        Map<String, Map<String, ValidationMessage>> result =
                extractor.extract(file.toString(), AngularI18nCatalog.empty());

        ValidationMessage msg = result.get("username").get("minlength");
        assertNotNull(msg, "form.controls['username'] must resolve field name 'username'");
        assertEquals("Too short", msg.text());
    }

    // 8. No *ngIf blocks at all in the template → empty map, no exception.
    @Test
    void templateWithoutErrorBlocks_returnsEmptyMap(@TempDir Path dir) throws IOException {
        String template = "<div>{{ message }}</div>";
        Path file = writeComponent(dir, "no-errors.component.ts", template);

        Map<String, Map<String, ValidationMessage>> result =
                extractor.extract(file.toString(), AngularI18nCatalog.empty());

        assertTrue(result.isEmpty());
    }

    // 9. Nonexistent file → empty map, no exception (mirrors every other extractor's contract).
    @Test
    void returnsEmptyMapForNonexistentFile() {
        Map<String, Map<String, ValidationMessage>> result =
                extractor.extract("/nonexistent/path.ts", AngularI18nCatalog.empty());
        assertTrue(result.isEmpty());
    }
}
