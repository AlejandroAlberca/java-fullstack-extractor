package com.devmanchego.contextextractor.angular.webpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebpackConstantReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void definePlugin_literalString_extracted() throws IOException {
        writeFile("webpack.custom.js", """
                new webpack.DefinePlugin({
                  API_ROOT_V5: JSON.stringify('api/v5/'),
                  SERVER_VERSION: JSON.stringify("2.3.0"),
                });
                """);

        Map<String, String> constants = WebpackConstantReader.read(tempDir);

        assertEquals("api/v5/", constants.get("API_ROOT_V5"));
        assertEquals("2.3.0",   constants.get("SERVER_VERSION"));
    }

    @Test
    void definePlugin_runtimeExpression_skipped() throws IOException {
        writeFile("webpack.custom.js", """
                new webpack.DefinePlugin({
                  SERVER_API_URL: JSON.stringify(environment.SERVER_API_URL),
                  I18N_HASH: JSON.stringify(languagesHash.hash),
                });
                """);

        Map<String, String> constants = WebpackConstantReader.read(tempDir);

        assertFalse(constants.containsKey("SERVER_API_URL"),
                "Runtime expression must not be extracted");
        assertFalse(constants.containsKey("I18N_HASH"),
                "Runtime expression must not be extracted");
    }

    @Test
    void mixedEntries_onlyLiteralsExtracted() throws IOException {
        writeFile("webpack.custom.js", """
                new webpack.DefinePlugin({
                  API_ROOT_V5: JSON.stringify('api/v5/'),
                  SERVER_API_URL: JSON.stringify(environment.SERVER_API_URL),
                  BUILD_DATE: JSON.stringify('2026-06-25'),
                });
                """);

        Map<String, String> constants = WebpackConstantReader.read(tempDir);

        assertEquals(2, constants.size());
        assertEquals("api/v5/",    constants.get("API_ROOT_V5"));
        assertEquals("2026-06-25", constants.get("BUILD_DATE"));
    }

    @Test
    void noWebpackFile_returnsEmptyMap() {
        Map<String, String> constants = WebpackConstantReader.read(tempDir);
        assertTrue(constants.isEmpty(), "No webpack file → empty map");
    }

    @Test
    void nonWebpackJsFile_ignored() throws IOException {
        writeFile("custom.js", """
                new webpack.DefinePlugin({
                  API_ROOT_V5: JSON.stringify('api/v5/'),
                });
                """);

        Map<String, String> constants = WebpackConstantReader.read(tempDir);
        assertTrue(constants.isEmpty(), "File not starting with 'webpack' must be ignored");
    }

    @Test
    void subDirectory_withinMaxDepth_scanned() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("webpack"));
        Files.writeString(sub.resolve("webpack.config.js"), """
                new webpack.DefinePlugin({
                  DEEP_CONST: JSON.stringify('deep-value'),
                });
                """);

        Map<String, String> constants = WebpackConstantReader.read(tempDir);
        assertEquals("deep-value", constants.get("DEEP_CONST"));
    }

    // -----------------------------------------------------------------------

    private void writeFile(String name, String content) throws IOException {
        Files.writeString(tempDir.resolve(name), content);
    }
}
