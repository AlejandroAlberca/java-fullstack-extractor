package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.common.ValidatorInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JspFormValidationExtractorTest {

    @TempDir
    Path tempDir;

    private final JspFormValidationExtractor extractor = new JspFormValidationExtractor();

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void html5RequiredAttribute_detected() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", "<input id=\"code\" required/>");

        Map<String, List<ValidatorInfo>> result = extractor.extract(jsp.toString());

        assertTrue(result.containsKey("code"));
        assertEquals("required", result.get("code").get(0).name());
    }

    @Test
    void markerClassOnLabel_detectsRequired() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="indice" class="mandatory"><span class="champ-obligatoire">Indice</span></label>
                <input id="indice"/>
                """);

        Map<String, List<ValidatorInfo>> result = extractor.extract(jsp.toString());

        assertTrue(result.containsKey("indice"));
    }

    @Test
    void markerClassOnDescendantSpan_alsoDetected() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="code">Code <span class="required-star">*</span></label>
                <input id="code"/>
                """);

        Map<String, List<ValidatorInfo>> result = extractor.extract(jsp.toString());

        assertTrue(result.containsKey("code"));
    }

    @Test
    void englishRequiredClassName_alsoRecognised() throws IOException {
        // The class-name heuristic isn't hardcoded to French — "required"/"mandatory" match
        // just as well as the target profile's own "obligatoire".
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="email" class="required-field">Email</label>
                <input id="email"/>
                """);

        Map<String, List<ValidatorInfo>> result = extractor.extract(jsp.toString());

        assertTrue(result.containsKey("email"));
    }

    @Test
    void fieldWithNoRequiredSignal_notInResult() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="optional">Optional field</label>
                <input id="optional"/>
                """);

        Map<String, List<ValidatorInfo>> result = extractor.extract(jsp.toString());

        assertFalse(result.containsKey("optional"));
    }

    @Test
    void unrelatedLabelWithMarkerClass_doesNotFalselyMarkAnotherField() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="required" class="mandatory">Field A</label>
                <input id="required"/>
                <input id="unrelated"/>
                """);

        Map<String, List<ValidatorInfo>> result = extractor.extract(jsp.toString());

        assertTrue(result.containsKey("required"));
        assertFalse(result.containsKey("unrelated"));
    }

    @Test
    void requirednessResolvedAcrossAStaticInclude() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/form.jsp",
                "<label for=\"x\" class=\"mandatory\">X</label><input id=\"x\"/>");
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp",
                "<%@ include file=\"/WEB-INF/jsp/fragments/form.jsp\" %>");

        Map<String, List<ValidatorInfo>> result = extractor.extract(jsp.toString());

        assertTrue(result.containsKey("x"));
    }

    @Test
    void nonExistentFile_returnsEmptyMap_neverThrows() {
        assertDoesNotThrow(() -> assertTrue(extractor.extract(tempDir.resolve("nope.jsp").toString()).isEmpty()));
    }
}
