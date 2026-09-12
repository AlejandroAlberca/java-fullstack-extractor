package com.devmanchego.contextextractor.react.template;

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

class ReactFormValidationExtractorTest {

    private final ReactFormValidationExtractor extractor = new ReactFormValidationExtractor();

    private Path writeComponent(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    /** rawText of each validator, in order — the display string shown in documentation. */
    private List<String> rawTexts(List<ValidatorInfo> validators) {
        return validators.stream().map(ValidatorInfo::rawText).collect(Collectors.toList());
    }

    @Test
    void extractsZodValidators(@TempDir Path dir) throws IOException {
        String src = """
            import { z } from 'zod';

            const schema = z.object({
              name: z.string().min(3),
              email: z.string().email(),
              age: z.number().min(18)
            });
            """;
        Path file = writeComponent(dir, "form.tsx", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(3, rules.size());
        assertEquals(List.of("min(3)"), rawTexts(rules.get("name")));
        assertEquals(List.of("email"), rawTexts(rules.get("email")));
        assertEquals(List.of("min(18)"), rawTexts(rules.get("age")));
    }

    @Test
    void extractsYupValidators(@TempDir Path dir) throws IOException {
        String src = """
            import * as yup from 'yup';

            const schema = yup.object({
              username: yup.string().required().min(5),
              password: yup.string().required()
            });
            """;
        Path file = writeComponent(dir, "form.tsx", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(2, rules.size());
        assertEquals(List.of("required", "min(5)"), rawTexts(rules.get("username")));
        assertEquals(List.of("required"), rawTexts(rules.get("password")));
    }

    @Test
    void extractsFormikValidationSchema(@TempDir Path dir) throws IOException {
        String src = """
            import { Formik } from 'formik';
            import * as yup from 'yup';

            export function UserForm() {
              return (
                <Formik
                  validationSchema={yup.object({
                    firstName: yup.string().required(),
                    email: yup.string().email()
                  })}
                />
              );
            }
            """;
        Path file = writeComponent(dir, "UserForm.tsx", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(2, rules.size());
        assertEquals(List.of("required"), rawTexts(rules.get("firstName")));
        assertEquals(List.of("email"), rawTexts(rules.get("email")));
    }

    @Test
    void ignoresComponentsWithoutSchemas(@TempDir Path dir) throws IOException {
        String src = "export function DisplayUser() { return <div>User</div>; }";
        Path file = writeComponent(dir, "Display.tsx", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertTrue(rules.isEmpty());
    }

    @Test
    void extractsMultipleSchemas(@TempDir Path dir) throws IOException {
        String src = """
            const loginSchema = z.object({ email: z.string().email() });
            const signupSchema = z.object({ password: z.string().min(8) });
            """;
        Path file = writeComponent(dir, "auth.tsx", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(2, rules.size());
        assertTrue(rules.containsKey("email"));
        assertTrue(rules.containsKey("password"));
    }

    @Test
    void returnsEmptyMapForNonexistentFile() {
        Map<String, List<ValidatorInfo>> rules = extractor.extract("/nonexistent.tsx");
        assertTrue(rules.isEmpty());
    }
}
