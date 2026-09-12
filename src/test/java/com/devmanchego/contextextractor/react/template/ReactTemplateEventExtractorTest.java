package com.devmanchego.contextextractor.react.template;

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

class ReactTemplateEventExtractorTest {

    private final ReactTemplateEventExtractor extractor = new ReactTemplateEventExtractor();

    private Path writeComponent(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void clickHandlerThatCallsApi_isBusinessTrigger(@TempDir Path dir) throws IOException {
        String src = """
            export function ItemList() {
              const deleteItem = async (id: number) => {
                await api.delete(`/items/${id}`);
              };

              return <button onClick={() => deleteItem(123)}>Delete</button>;
            }
            """;
        Path file = writeComponent(dir, "ItemList.tsx", src);
        Map<String, String> descriptors = Map.of("delete", "DELETE /items/{id}");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertEquals(1, events.size());
        assertTrue(events.get(0).description().contains("DELETE /items/{id}"));
    }

    @Test
    void formSubmitHandlerWithFetch_isBusinessTrigger(@TempDir Path dir) throws IOException {
        String src = """
            export function LoginForm() {
              const handleSubmit = async (data) => {
                await fetch('/api/login', { method: 'POST', body: JSON.stringify(data) });
              };

              return <form onSubmit={handleSubmit}><button type="submit">Login</button></form>;
            }
            """;
        Path file = writeComponent(dir, "LoginForm.tsx", src);
        Map<String, String> descriptors = Map.of("fetch", "POST /api/login");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertEquals(1, events.size());
        assertEquals("Form submit → POST /api/login", events.get(0).description());
    }

    @Test
    void handlersWithoutHttpCalls_areIgnored(@TempDir Path dir) throws IOException {
        String src = """
            export function Counter() {
              const handleClick = () => {
                console.log('Clicked');
              };

              const handleChange = (e) => {
                setCount(e.target.value);
              };

              return <>
                <button onClick={handleClick}>Click</button>
                <input onChange={handleChange} />
              </>;
            }
            """;
        Path file = writeComponent(dir, "Counter.tsx", src);
        Map<String, String> descriptors = Map.of("post", "POST /api/data");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertTrue(events.isEmpty(), "handlers without HTTP calls must be ignored");
    }

    @Test
    void emptyDescriptorMap_yieldsNoEvents(@TempDir Path dir) throws IOException {
        String src = """
            export function Item() {
              const deleteItem = async (id) => { await api.delete(`/items/${id}`); };
              return <button onClick={() => deleteItem(1)}>Delete</button>;
            }
            """;
        Path file = writeComponent(dir, "Item.tsx", src);

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), Map.of());

        assertTrue(events.isEmpty());
    }

    @Test
    void directMethodReferences_work(@TempDir Path dir) throws IOException {
        String src = """
            export function ActionBar() {
              const saveData = async () => {
                await api.put('/data', {});
              };

              return <button onClick={saveData}>Save</button>;
            }
            """;
        Path file = writeComponent(dir, "ActionBar.tsx", src);
        Map<String, String> descriptors = Map.of("put", "PUT /data");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertEquals(1, events.size());
        assertTrue(events.get(0).description().contains("PUT /data"));
    }

    @Test
    void duplicateBindingsForSameTrigger_areDeduplicated(@TempDir Path dir) throws IOException {
        String src = """
            export function ItemList() {
              const handleDelete = async (id) => {
                await api.delete(`/items/${id}`);
              };

              return <>
                <button onClick={() => handleDelete(1)}>Delete</button>
                <button onClick={() => handleDelete(1)}>Remove</button>
              </>;
            }
            """;
        Path file = writeComponent(dir, "ItemList.tsx", src);
        Map<String, String> descriptors = Map.of("delete", "DELETE /items/{id}");

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertEquals(1, events.size(), "identical triggers should deduplicate");
    }

    @Test
    void multipleHandlers_eachExtracted(@TempDir Path dir) throws IOException {
        String src = """
            export function UserActions() {
              const saveUser = async () => { await api.put('/users', {}); };
              const deleteUser = async () => { await api.delete('/users/1'); };

              return <>
                <button onClick={saveUser}>Save</button>
                <button onClick={deleteUser}>Delete</button>
              </>;
            }
            """;
        Path file = writeComponent(dir, "UserActions.tsx", src);
        Map<String, String> descriptors = Map.of(
            "put", "PUT /users",
            "delete", "DELETE /users/1"
        );

        List<TemplateEventExtractorStrategy.Event> events = extractor.extract(file.toString(), descriptors);

        assertEquals(2, events.size());
    }
}
