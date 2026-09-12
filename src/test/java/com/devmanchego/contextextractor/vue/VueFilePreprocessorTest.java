package com.devmanchego.contextextractor.vue;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VueFilePreprocessorTest {

    @Test
    void extractsPlainScriptBlock() {
        String vue = "<template><div/></template>\n<script>\nexport default {};\n</script>";
        Optional<VueFilePreprocessor.ScriptBlock> result = VueFilePreprocessor.extract(vue);
        assertTrue(result.isPresent());
        assertTrue(result.get().source().contains("export default"));
        assertFalse(result.get().isTypeScript());
    }

    @Test
    void extractsScriptSetupBlock() {
        String vue = "<template><div/></template>\n<script setup>\nconst x = 1;\n</script>";
        Optional<VueFilePreprocessor.ScriptBlock> result = VueFilePreprocessor.extract(vue);
        assertTrue(result.isPresent());
        assertTrue(result.get().source().contains("const x = 1"));
    }

    @Test
    void detectsTypeScriptLang() {
        String vue = "<script lang=\"ts\">\nconst x: number = 1;\n</script>";
        Optional<VueFilePreprocessor.ScriptBlock> result = VueFilePreprocessor.extract(vue);
        assertTrue(result.isPresent());
        assertTrue(result.get().isTypeScript());
    }

    @Test
    void detectsTypeScriptFullLang() {
        String vue = "<script lang='typescript'>\nconst x = 1;\n</script>";
        Optional<VueFilePreprocessor.ScriptBlock> result = VueFilePreprocessor.extract(vue);
        assertTrue(result.isPresent());
        assertTrue(result.get().isTypeScript());
    }

    @Test
    void noScriptBlock_returnsEmpty() {
        String vue = "<template><div/></template>";
        assertTrue(VueFilePreprocessor.extract(vue).isEmpty());
    }

    @Test
    void scriptWithSetupAndLang() {
        String vue = "<script setup lang=\"ts\">\nimport { ref } from 'vue'\n</script>";
        Optional<VueFilePreprocessor.ScriptBlock> result = VueFilePreprocessor.extract(vue);
        assertTrue(result.isPresent());
        assertTrue(result.get().isTypeScript());
        assertTrue(result.get().source().contains("import"));
    }

    @Test
    void emptyScriptBlock_isPresent() {
        String vue = "<script></script>";
        Optional<VueFilePreprocessor.ScriptBlock> result = VueFilePreprocessor.extract(vue);
        assertTrue(result.isPresent());
    }
}
