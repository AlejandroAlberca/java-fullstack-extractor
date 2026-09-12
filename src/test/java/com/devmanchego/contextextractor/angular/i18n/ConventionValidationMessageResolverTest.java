package com.devmanchego.contextextractor.angular.i18n;

import com.devmanchego.contextextractor.common.ValidationMessage;
import com.devmanchego.contextextractor.common.ValidationMessageStatus;
import com.devmanchego.contextextractor.common.ValidatorInfo;
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
 * Test-first fixtures for {@link ConventionValidationMessageResolver}: given the validators
 * already extracted for a field (from {@link com.devmanchego.contextextractor.common.FormValidationExtractorStrategy}),
 * derive the key {@code errors.<field>.<validatorName>} and resolve it against an
 * {@link AngularI18nCatalog}. Each case below states the exact expected tuple
 * (field, validator, key, status, text) before any implementation exists.
 */
class ConventionValidationMessageResolverTest {

    private final ConventionValidationMessageResolver resolver = new ConventionValidationMessageResolver();

    private AngularI18nCatalog catalogFromJson(Path dir, String json) throws IOException {
        Path i18nDir = dir.resolve("assets/i18n");
        Files.createDirectories(i18nDir);
        Files.writeString(i18nDir.resolve("en.json"), json, StandardCharsets.UTF_8);
        return AngularI18nCatalog.load(dir);
    }

    // 1. Single validator, key present in catalog → RESOLVED with the exact message text.
    @Test
    void resolvesKeyPresentInCatalog(@TempDir Path dir) throws IOException {
        AngularI18nCatalog catalog = catalogFromJson(dir, """
                { "errors": { "email": { "required": "Email is required" } } }
                """);
        Map<String, List<ValidatorInfo>> formRules = Map.of(
                "email", List.of(ValidatorInfo.simple("required")));

        Map<String, Map<String, ValidationMessage>> result = resolver.resolve(formRules, catalog);

        ValidationMessage msg = result.get("email").get("required");
        assertEquals(ValidationMessageStatus.RESOLVED, msg.status());
        assertEquals("Email is required", msg.text());
        assertEquals("errors.email.required", msg.i18nKey());
    }

    // 2. Validator whose conventional key has no entry in the catalog → NOT_FOUND, no text/key leaked.
    @Test
    void missingKeyYieldsNotFound(@TempDir Path dir) throws IOException {
        AngularI18nCatalog catalog = catalogFromJson(dir, """
                { "errors": { "email": { "required": "Email is required" } } }
                """);
        Map<String, List<ValidatorInfo>> formRules = Map.of(
                "email", List.of(ValidatorInfo.simple("email")));  // "email" format validator, not "required"

        Map<String, Map<String, ValidationMessage>> result = resolver.resolve(formRules, catalog);

        ValidationMessage msg = result.get("email").get("email");
        assertEquals(ValidationMessageStatus.NOT_FOUND, msg.status());
        assertNull(msg.text());
        assertNull(msg.i18nKey());
    }

    // 3. Mixed outcome within the same field: one validator resolves, the sibling does not.
    @Test
    void mixedResolutionWithinSameField(@TempDir Path dir) throws IOException {
        AngularI18nCatalog catalog = catalogFromJson(dir, """
                { "errors": { "password": { "required": "Password is required" } } }
                """);
        Map<String, List<ValidatorInfo>> formRules = Map.of(
                "password", List.of(
                        ValidatorInfo.simple("required"),
                        ValidatorInfo.withArgs("minlength", "8")));

        Map<String, Map<String, ValidationMessage>> result = resolver.resolve(formRules, catalog);

        assertEquals(ValidationMessageStatus.RESOLVED, result.get("password").get("required").status());
        assertEquals(ValidationMessageStatus.NOT_FOUND, result.get("password").get("minlength").status());
    }

    // 4. The convention key uses the bare validator name — arguments must never leak into the key.
    @Test
    void keyUsesValidatorNameWithoutArguments(@TempDir Path dir) throws IOException {
        AngularI18nCatalog catalog = catalogFromJson(dir, """
                { "errors": { "username": { "minlength": "Username is too short" } } }
                """);
        Map<String, List<ValidatorInfo>> formRules = Map.of(
                "username", List.of(ValidatorInfo.withArgs("minlength", "3")));

        Map<String, Map<String, ValidationMessage>> result = resolver.resolve(formRules, catalog);

        ValidationMessage msg = result.get("username").get("minlength");
        assertEquals("errors.username.minlength", msg.i18nKey(),
                "the '3' argument must not appear in the derived key");
        assertEquals(ValidationMessageStatus.RESOLVED, msg.status());
    }

    // 5. Empty catalog → every validator resolves to NOT_FOUND, nothing throws.
    @Test
    void emptyCatalogYieldsAllNotFound() {
        Map<String, List<ValidatorInfo>> formRules = Map.of(
                "email", List.of(ValidatorInfo.simple("required"), ValidatorInfo.simple("email")));

        Map<String, Map<String, ValidationMessage>> result = resolver.resolve(formRules, AngularI18nCatalog.empty());

        assertEquals(ValidationMessageStatus.NOT_FOUND, result.get("email").get("required").status());
        assertEquals(ValidationMessageStatus.NOT_FOUND, result.get("email").get("email").status());
    }

    // 6. Multiple independent fields are resolved independently.
    @Test
    void multipleFieldsResolvedIndependently(@TempDir Path dir) throws IOException {
        AngularI18nCatalog catalog = catalogFromJson(dir, """
                {
                  "errors": {
                    "firstName": { "required": "First name is required" },
                    "email": { "required": "Email is required", "email": "Invalid email format" }
                  }
                }
                """);
        Map<String, List<ValidatorInfo>> formRules = Map.of(
                "firstName", List.of(ValidatorInfo.simple("required")),
                "email", List.of(ValidatorInfo.simple("required"), ValidatorInfo.simple("email")));

        Map<String, Map<String, ValidationMessage>> result = resolver.resolve(formRules, catalog);

        assertEquals("First name is required", result.get("firstName").get("required").text());
        assertEquals("Email is required", result.get("email").get("required").text());
        assertEquals("Invalid email format", result.get("email").get("email").text());
    }

    // 7. Empty formRules map → empty result, no NPE.
    @Test
    void emptyFormRulesYieldsEmptyResult() {
        Map<String, Map<String, ValidationMessage>> result = resolver.resolve(Map.of(), AngularI18nCatalog.empty());
        assertTrue(result.isEmpty());
    }
}
