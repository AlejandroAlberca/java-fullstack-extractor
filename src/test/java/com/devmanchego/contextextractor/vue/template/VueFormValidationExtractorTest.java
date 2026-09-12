package com.devmanchego.contextextractor.vue.template;

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

class VueFormValidationExtractorTest {

    private final VueFormValidationExtractor extractor = new VueFormValidationExtractor();

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
    void extractsInlineValidationRules(@TempDir Path dir) throws IOException {
        String src = """
            <script>
            export default {
              data() {
                return {
                  rules: {
                    name: ['required', 'minLength:3']
                  }
                };
              }
            };
            </script>
            """;
        Path file = writeComponent(dir, "form.vue", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertEquals(1, rules.size());
        assertEquals(List.of("required", "minLength(3)"), rawTexts(rules.get("name")));
    }

    @Test
    void ignoresComponentsWithoutValidation(@TempDir Path dir) throws IOException {
        String src = "<template><div>{{ message }}</div></template>";
        Path file = writeComponent(dir, "display.vue", src);

        Map<String, List<ValidatorInfo>> rules = extractor.extract(file.toString());

        assertTrue(rules.isEmpty());
    }

    @Test
    void returnsEmptyMapForNonexistentFile() {
        Map<String, List<ValidatorInfo>> rules = extractor.extract("/nonexistent.vue");
        assertTrue(rules.isEmpty());
    }
}
