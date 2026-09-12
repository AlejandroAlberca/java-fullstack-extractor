package com.devmanchego.contextextractor.vue.template;

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
 * Test-first fixtures for {@link VueFormFieldExtractor}: associates a {@code v-model}-bound
 * field with its {@code <label>}, mirroring {@code AngularFormFieldExtractor}'s three-signal
 * resolution order. Field naming intentionally mirrors {@code VueFormValidationExtractor}'s own
 * {@code v-model="form.field"} → {@code field} convention (stripping only a literal {@code form.}
 * prefix) — a different convention here would silently break the join between labels and
 * validators in the same table row. No i18n key detection, same reasoning as React.
 */
class VueFormFieldExtractorTest {

    private final VueFormFieldExtractor extractor = new VueFormFieldExtractor();

    private Path writeComponent(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void resolvesLabelViaForIdPairing_withFormPrefixedVModel(@TempDir Path dir) throws IOException {
        String src = """
            <template>
              <label for="email">Email</label>
              <input id="email" v-model="form.email" />
            </template>
            """;
        Path file = writeComponent(dir, "LoginForm.vue", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Email"), labels.get("email"));
    }

    @Test
    void resolvesLabelViaForIdPairing_withBareVModel(@TempDir Path dir) throws IOException {
        // No "form." prefix — the whole v-model path is the field name, matching how
        // VueFormValidationExtractor resolves the same bare-path case.
        String src = """
            <template>
              <label for="username">Username</label>
              <input id="username" v-model="username" />
            </template>
            """;
        Path file = writeComponent(dir, "SignupForm.vue", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Username"), labels.get("username"));
    }

    @Test
    void resolvesLabelViaAriaLabel(@TempDir Path dir) throws IOException {
        String src = """
            <template>
              <input v-model="form.query" aria-label="Search products" />
            </template>
            """;
        Path file = writeComponent(dir, "SearchBox.vue", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Search products"), labels.get("query"));
    }

    @Test
    void resolvesLabelViaNearestPrecedingLabel_whenNoForIdPairing(@TempDir Path dir) throws IOException {
        String src = """
            <template>
              <div>
                <label>Password</label>
                <input type="password" v-model="form.password" />
              </div>
            </template>
            """;
        Path file = writeComponent(dir, "SignupForm.vue", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Password"), labels.get("password"));
    }

    @Test
    void modifiedVModel_lazyOrTrim_stillResolvesFieldName(@TempDir Path dir) throws IOException {
        String src = """
            <template>
              <label for="bio">Biography</label>
              <input id="bio" v-model.lazy="form.bio" />
            </template>
            """;
        Path file = writeComponent(dir, "ProfileForm.vue", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertEquals(FieldLabel.ofLiteral("Biography"), labels.get("bio"));
    }

    @Test
    void noI18nKeyDetection_labelWithInterpolation_yieldsNoLabel(@TempDir Path dir) throws IOException {
        // {{ $t('form.email.label') }} is a JS expression, not plain text — skipped rather than
        // guessed at, since Vue has no i18n catalog support in this codebase.
        String src = """
            <template>
              <label for="email">{{ $t('form.email.label') }}</label>
              <input id="email" v-model="form.email" />
            </template>
            """;
        Path file = writeComponent(dir, "TranslatedForm.vue", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertFalse(labels.containsKey("email"));
    }

    @Test
    void dynamicVModel_notResolved(@TempDir Path dir) throws IOException {
        // v-model="form[fieldNameVar]" — bracket/computed access isn't a literal path.
        String src = """
            <template>
              <input v-model="form[fieldNameVar]" />
            </template>
            """;
        Path file = writeComponent(dir, "DynamicForm.vue", src);

        Map<String, FieldLabel> labels = extractor.extract(file.toString());

        assertTrue(labels.isEmpty());
    }

    @Test
    void returnsEmptyMapForNonexistentFile() {
        Map<String, FieldLabel> labels = extractor.extract("/nonexistent.vue");
        assertTrue(labels.isEmpty());
    }
}
