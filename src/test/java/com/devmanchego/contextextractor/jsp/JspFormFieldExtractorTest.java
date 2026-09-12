package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.common.FieldLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JspFormFieldExtractorTest {

    @TempDir
    Path tempDir;

    private final JspFormFieldExtractor extractor = new JspFormFieldExtractor();

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    // -----------------------------------------------------------------------
    // Field identification — input, select, textarea, keyed by id
    // -----------------------------------------------------------------------

    @Test
    void inputSelectAndTextarea_allExtracted() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <html><body>
                <label for="code">Code</label><input id="code" type="text"/>
                <label for="entite">Entite</label><select id="entite"></select>
                <label for="notes">Notes</label><textarea id="notes"></textarea>
                </body></html>
                """);

        Map<String, FieldLabel> labels = extractor.extract(jsp.toString());

        assertEquals(3, labels.size());
        assertTrue(labels.containsKey("code"));
        assertTrue(labels.containsKey("entite"));
        assertTrue(labels.containsKey("notes"));
    }

    @Test
    void fieldsWithoutAnId_areNotExtracted() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp",
                "<input type=\"text\" name=\"noId\"/>");

        assertTrue(extractor.extract(jsp.toString()).isEmpty());
    }

    @Test
    void otherElements_neverExtractedAsFields() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp",
                "<div id=\"notAField\">content</div><button id=\"alsoNot\">Go</button>");

        assertTrue(extractor.extract(jsp.toString()).isEmpty());
    }

    @Test
    void duplicateId_extractedOnce() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp",
                "<input id=\"x\"/><input id=\"x\"/>");

        assertEquals(1, extractor.extract(jsp.toString()).size());
    }

    // -----------------------------------------------------------------------
    // Label resolution — label[for] only
    // -----------------------------------------------------------------------

    @Test
    void labelForAssociation_resolvesLiteralLabel() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="poleAcheteur">Pôle Acheteur</label>
                <select id="poleAcheteur"></select>
                """);

        FieldLabel label = extractor.extract(jsp.toString()).get("poleAcheteur");

        assertNotNull(label);
        assertEquals("Pôle Acheteur", label.literal());
        assertFalse(label.hasI18nKey());
    }

    @Test
    void fieldWithNoMatchingLabel_emittedWithNullLabel_neverDropped() throws IOException {
        // Acceptance: "Fields whose label cannot be resolved are emitted with a null label,
        // never dropped." — the key must exist; only the value is null.
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", "<input id=\"orphan\"/>");

        Map<String, FieldLabel> labels = extractor.extract(jsp.toString());

        assertTrue(labels.containsKey("orphan"), "The field must still be a key in the map");
        assertNull(labels.get("orphan"));
    }

    @Test
    void unrelatedLabel_doesNotAccidentallyMatch() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="other">Other field</label>
                <input id="mine"/>
                """);

        Map<String, FieldLabel> labels = extractor.extract(jsp.toString());

        assertTrue(labels.containsKey("mine"));
        assertNull(labels.get("mine"));
    }

    @Test
    void requiredMarkerSpan_wrapsTheLabelTextItself_notExcluded() throws IOException {
        // The target profile's real convention: the marker class wraps the field's actual
        // name, not a separate throwaway symbol — the resolved label is the full text, with
        // only the trailing ':' separator stripped.
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="indice" class="mandatory"><span class="champ-obligatoire">Indice</span> :</label>
                <input id="indice"/>
                """);

        FieldLabel label = extractor.extract(jsp.toString()).get("indice");

        assertNotNull(label);
        assertEquals("Indice", label.literal());
    }

    @Test
    void trailingAsteriskSeparator_alsoStripped() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <label for="code">Code *</label>
                <input id="code"/>
                """);

        FieldLabel label = extractor.extract(jsp.toString()).get("code");

        assertNotNull(label);
        assertEquals("Code", label.literal());
    }

    @Test
    void labelResolvedAcrossAStaticInclude() throws IOException {
        write("src/main/webapp/WEB-INF/jsp/fragments/form.jsp", """
                <label for="libelle">Libellé</label>
                <input id="libelle"/>
                """);
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp",
                "<%@ include file=\"/WEB-INF/jsp/fragments/form.jsp\" %>");

        FieldLabel label = extractor.extract(jsp.toString()).get("libelle");

        assertNotNull(label);
        assertEquals("Libellé", label.literal());
    }

    // -----------------------------------------------------------------------
    // Sections — headings and panel headers
    // -----------------------------------------------------------------------

    @Test
    void headingElements_extractedAsSections() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <h2>PRESCRIPTEUR</h2>
                <p>content</p>
                <h3>Details</h3>
                """);

        List<String> sections = extractor.extractSections(jsp.toString());

        assertEquals(List.of("PRESCRIPTEUR", "Details"), sections);
    }

    @Test
    void panelHeading_extractedAsASection() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <div class="panel panel-info">
                  <div class="panel-heading">
                    <h3 class="center">Fiche Accord Projet</h3>
                  </div>
                </div>
                """);

        List<String> sections = extractor.extractSections(jsp.toString());

        // The panel-heading wraps an h3 — only the more specific inner heading is kept, not
        // both, to avoid reporting the same section text twice.
        assertEquals(List.of("Fiche Accord Projet"), sections);
    }

    @Test
    void consecutiveDuplicateSections_collapsed() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <div class="panel-heading">Achats</div>
                <h2>Achats</h2>
                """);

        List<String> sections = extractor.extractSections(jsp.toString());

        assertEquals(List.of("Achats"), sections);
    }

    // -----------------------------------------------------------------------
    // Robustness
    // -----------------------------------------------------------------------

    @Test
    void nonExistentFile_returnsEmptyMap_neverThrows() {
        assertDoesNotThrow(() -> {
            Map<String, FieldLabel> labels = extractor.extract(tempDir.resolve("nope.jsp").toString());
            assertTrue(labels.isEmpty());
        });
    }

    @Test
    void unresolvedComponentFilePath_returnsEmptyMap() {
        assertDoesNotThrow(() -> assertTrue(extractor.extract("(unresolved)").isEmpty()));
    }
}
