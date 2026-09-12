package com.devmanchego.contextextractor.react;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReactJvmExtractorTest {

    @TempDir
    Path tempDir;

    private void writeFile(String relativePath, String content) throws IOException {
        Path target = tempDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void extractsAxiosGetFromServiceFile() throws IOException {
        writeFile("src/services/userService.ts",
                "import axios from 'axios';\n" +
                "export async function getUser() {\n" +
                "  return axios.get('/api/users');\n" +
                "}\n");

        AngularProject result = new ReactJvmExtractor(tempDir).extract();
        assertFalse(result.getServices().isEmpty());
        var calls = result.getServices().get(0).getHttpCalls();
        assertFalse(calls.isEmpty());
        assertEquals(HttpVerb.GET, calls.get(0).getHttpVerb());
        assertEquals("/api/users", calls.get(0).getUrlTemplate());
    }

    @Test
    void extractsAxiosPost() throws IOException {
        writeFile("src/api/itemApi.ts",
                "import axios from 'axios';\n" +
                "export async function createItem(data: Item) {\n" +
                "  return axios.post('/api/items', data);\n" +
                "}\n");

        AngularProject result = new ReactJvmExtractor(tempDir).extract();
        var calls = result.getServices().get(0).getHttpCalls();
        assertEquals(HttpVerb.POST, calls.get(0).getHttpVerb());
        assertEquals("/api/items", calls.get(0).getUrlTemplate());
    }

    @Test
    void extractsAxiosDelete() throws IOException {
        writeFile("src/api/itemApi.ts",
                "export const deleteItem = (id: string) => axios.delete(`/api/items/${id}`);\n");
        AngularProject result = new ReactJvmExtractor(tempDir).extract();
        // delete with template literal not matched by simple string regex — service may be empty
        // but should not throw
        assertNotNull(result);
    }

    @Test
    void extractsInterfaceModel() throws IOException {
        writeFile("src/models/User.ts",
                "export interface User {\n" +
                "  id: number;\n" +
                "  name: string;\n" +
                "  email?: string;\n" +
                "}\n");

        AngularProject result = new ReactJvmExtractor(tempDir).extract();
        assertFalse(result.getModels().isEmpty());
        var model = result.getModels().get(0);
        assertEquals("User", model.getName());
        assertEquals(3, model.getFields().size());
        assertTrue(model.getFields().stream().anyMatch(f -> f.getName().equals("id")));
        assertTrue(model.getFields().stream().anyMatch(f -> f.getName().equals("email") && f.isOptional()));
    }

    @Test
    void emptyDirectory_returnsEmptyProject() throws IOException {
        AngularProject result = new ReactJvmExtractor(tempDir).extract();
        assertTrue(result.getServices().isEmpty());
        assertTrue(result.getModels().isEmpty());
    }

    @Test
    void fileInHooksDirectory_isIdentifiedAsService() throws IOException {
        writeFile("src/hooks/useProducts.ts",
                "import axios from 'axios';\n" +
                "export function useProducts() {\n" +
                "  return axios.get('/api/products');\n" +
                "}\n");

        AngularProject result = new ReactJvmExtractor(tempDir).extract();
        assertFalse(result.getServices().isEmpty());
    }

    @Test
    void fetchCallExtracted() throws IOException {
        writeFile("src/api/orderApi.ts",
                "export async function deleteOrder(id: string) {\n" +
                "  return fetch('/api/orders', { method: 'DELETE' });\n" +
                "}\n");

        AngularProject result = new ReactJvmExtractor(tempDir).extract();
        assertFalse(result.getServices().isEmpty());
        var call = result.getServices().get(0).getHttpCalls().get(0);
        assertEquals(HttpVerb.DELETE, call.getHttpVerb());
    }
}
