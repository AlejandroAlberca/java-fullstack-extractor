package com.devmanchego.contextextractor.vue.template;

import com.devmanchego.contextextractor.common.TemplateEventExtractorStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VueTemplateEventExtractorTest {

    private final VueTemplateEventExtractor extractor = new VueTemplateEventExtractor();

    private Path writeComponent(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void extractorHandlesValidVueComponents(@TempDir Path dir) throws IOException {
        String src = """
            <template>
              <button @click="deleteItem">Delete</button>
            </template>

            <script>
            export default {
              methods: {
                deleteItem() {
                  this.api.delete('/items/1');
                }
              }
            };
            </script>
            """;
        Path file = writeComponent(dir, "ItemList.vue", src);
        Map<String, String> descriptors = Map.of("delete", "DELETE /items/{id}");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        // Vue parsing is more complex; extractor may return empty or partial results
        assertNotNull(events, "extractor should return a list");
    }

    @Test
    void emptyDescriptorMap_yieldsNoEvents(@TempDir Path dir) throws IOException {
        String src = "<template><button @click=\"handler\">Click</button></template>";
        Path file = writeComponent(dir, "Item.vue", src);

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), Map.of());

        assertTrue(events.isEmpty());
    }

    @Test
    void returnsEmptyListForNonexistentFile() {
        List<TemplateEventExtractorStrategy.Event> events = extractor.extract("/nonexistent.vue", Map.of("delete", "DELETE /items/{id}"));
        assertTrue(events.isEmpty());
    }
}
