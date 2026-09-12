package com.devmanchego.contextextractor.vue;

import com.devmanchego.contextextractor.angular.model.AngularProject;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VueJvmExtractorTest {

    @TempDir
    Path tempDir;

    private void writeFile(String relativePath, String content) throws IOException {
        Path target = tempDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void extractsAxiosGetFromVueScriptBlock() throws IOException {
        writeFile("src/views/UserView.vue",
                "<template><div/></template>\n" +
                "<script lang=\"ts\">\n" +
                "import axios from 'axios';\n" +
                "export default {\n" +
                "  async mounted() {\n" +
                "    const res = await axios.get('/api/users');\n" +
                "  }\n" +
                "}\n" +
                "</script>");

        AngularProject result = new VueJvmExtractor(tempDir).extract();
        assertFalse(result.getServices().isEmpty());
        var calls = result.getServices().get(0).getHttpCalls();
        assertEquals(HttpVerb.GET, calls.get(0).getHttpVerb());
        assertEquals("/api/users", calls.get(0).getUrlTemplate());
    }

    @Test
    void extractsVue2DollarHttp() throws IOException {
        writeFile("src/views/Product.vue",
                "<script>\nexport default {\n" +
                "  methods: {\n" +
                "    fetchProducts() {\n" +
                "      this.$http.get('/api/products').then(r => this.items = r.data);\n" +
                "    }\n" +
                "  }\n" +
                "}\n</script>");

        AngularProject result = new VueJvmExtractor(tempDir).extract();
        assertFalse(result.getServices().isEmpty());
        assertEquals(HttpVerb.GET, result.getServices().get(0).getHttpCalls().get(0).getHttpVerb());
    }

    @Test
    void extractsComposableTsFile() throws IOException {
        writeFile("src/composables/useProducts.ts",
                "import axios from 'axios';\n" +
                "export function useProducts() {\n" +
                "  const load = () => axios.get('/api/products');\n" +
                "  return { load };\n" +
                "}\n");

        AngularProject result = new VueJvmExtractor(tempDir).extract();
        assertFalse(result.getServices().isEmpty());
        assertEquals("/api/products", result.getServices().get(0).getHttpCalls().get(0).getUrlTemplate());
    }

    @Test
    void extractsInterfaceFromVueScript() throws IOException {
        writeFile("src/types/Product.vue",
                "<script lang=\"ts\">\n" +
                "export interface Product {\n" +
                "  id: number;\n" +
                "  name: string;\n" +
                "}\n" +
                "</script>");

        AngularProject result = new VueJvmExtractor(tempDir).extract();
        assertFalse(result.getModels().isEmpty());
        assertEquals("Product", result.getModels().get(0).getName());
    }

    @Test
    void emptyDirectory_returnsEmptyProject() throws IOException {
        AngularProject result = new VueJvmExtractor(tempDir).extract();
        assertTrue(result.getServices().isEmpty());
        assertTrue(result.getModels().isEmpty());
    }

    @Test
    void axiosPost_capturedFromComposable() throws IOException {
        writeFile("src/composables/useAuth.ts",
                "import axios from 'axios';\n" +
                "export function useAuth() {\n" +
                "  const login = (creds) => axios.post('/api/auth/login', creds);\n" +
                "  return { login };\n" +
                "}\n");

        AngularProject result = new VueJvmExtractor(tempDir).extract();
        assertFalse(result.getServices().isEmpty());
        assertEquals(HttpVerb.POST, result.getServices().get(0).getHttpCalls().get(0).getHttpVerb());
    }
}
