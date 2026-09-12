package com.devmanchego.contextextractor.react.template;

import com.devmanchego.contextextractor.common.FieldLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first fixtures for {@link ReactFormFieldExtractor}: associates a field name — from
 * react-hook-form's {@code register("field")} or a plain {@code name="field"} attribute — with
 * its JSX {@code <label>}, mirroring {@code AngularFormFieldExtractor}'s three signals
 * (htmlFor/id pairing, aria-label, nearest preceding label). No i18n key detection: React has no
 * single dominant i18n convention this codebase resolves against (unlike ngx-translate for
 * Angular), so every label here is a literal.
 */
class ReactFormFieldExtractorTest {

    private final ReactFormFieldExtractor extractor = new ReactFormFieldExtractor();

    private Path writeComponent(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void resolvesLabelViaHtmlForIdPairing_withRegisterCall(@TempDir Path dir) throws IOException {
        String src = """
            export function LoginForm() {
              return (
                <form>
                  <label htmlFor="email">Email</label>
                  <input id="email" {...register("email")} />
                </form>
              );
            }
            """;
        Path file = writeComponent(dir, "LoginForm.tsx", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Email"), labels.get("email"));
    }

    @Test
    void resolvesLabelViaHtmlForIdPairing_withNameAttribute(@TempDir Path dir) throws IOException {
        String src = """
            export function ContactForm() {
              return (
                <form>
                  <label htmlFor="firstName">First Name</label>
                  <input id="firstName" name="firstName" value={form.firstName} onChange={handleChange} />
                </form>
              );
            }
            """;
        Path file = writeComponent(dir, "ContactForm.tsx", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("First Name"), labels.get("firstName"));
    }

    @Test
    void resolvesLabelViaAriaLabel(@TempDir Path dir) throws IOException {
        String src = """
            export function SearchBox() {
              return <input {...register("query")} aria-label="Search products" />;
            }
            """;
        Path file = writeComponent(dir, "SearchBox.tsx", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Search products"), labels.get("query"));
    }

    @Test
    void resolvesLabelViaNearestPrecedingLabel_whenNoForIdPairing(@TempDir Path dir) throws IOException {
        String src = """
            export function SignupForm() {
              return (
                <div>
                  <label>Password</label>
                  <input type="password" {...register("password")} />
                </div>
              );
            }
            """;
        Path file = writeComponent(dir, "SignupForm.tsx", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Password"), labels.get("password"));
    }

    @Test
    void formikFieldName_alsoResolvesLabel(@TempDir Path dir) throws IOException {
        String src = """
            export function ProfileForm() {
              return (
                <Form>
                  <label htmlFor="bio">Biography</label>
                  <Field id="bio" name="bio" as="textarea" />
                </Form>
              );
            }
            """;
        Path file = writeComponent(dir, "ProfileForm.tsx", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Biography"), labels.get("bio"));
    }

    @Test
    void noI18nKeyDetection_labelWithBraceExpression_yieldsNoLabel(@TempDir Path dir) throws IOException {
        // {t('key')} or any other JS expression inside the label is not a literal — skipped
        // entirely rather than guessing at an i18n key, since React has no i18n catalog support.
        String src = """
            export function TranslatedForm() {
              return (
                <div>
                  <label htmlFor="email">{t('form.email.label')}</label>
                  <input id="email" {...register("email")} />
                </div>
              );
            }
            """;
        Path file = writeComponent(dir, "TranslatedForm.tsx", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertFalse(labels.containsKey("email"));
    }

    @Test
    void dynamicRegisterCall_notResolved(@TempDir Path dir) throws IOException {
        // register(fieldNameVar) — the argument isn't a literal, so no field name is derivable.
        String src = """
            export function DynamicForm() {
              return <input {...register(fieldNameVar)} />;
            }
            """;
        Path file = writeComponent(dir, "DynamicForm.tsx", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertTrue(labels.isEmpty());
    }

    @Test
    void returnsEmptyMapForNonexistentFile() {
        Map<String, FieldLabel> labels = extractor.extract("/nonexistent/path.tsx");
        assertTrue(labels.isEmpty());
    }
}
