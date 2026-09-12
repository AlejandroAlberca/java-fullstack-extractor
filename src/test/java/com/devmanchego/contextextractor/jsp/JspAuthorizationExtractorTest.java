package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.java.security.SecurityRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JspAuthorizationExtractorTest {

    @TempDir
    Path tempDir;

    private final JspAuthorizationExtractor extractor = new JspAuthorizationExtractor();

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void secAuthorize_producesAUiFragmentRule() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/admin/page.jsp", """
                <%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
                <sec:authorize access="hasPermission('','ADMINISTRATION')">
                <button id="importBtn">Import</button>
                </sec:authorize>
                """);

        List<SecurityRule> rules = extractor.extract(jsp.toString(), "/admin/page");

        assertEquals(1, rules.size());
        SecurityRule rule = rules.get(0);
        assertEquals(SecurityRule.RuleStrategy.UI_FRAGMENT, rule.strategy());
        assertEquals(Set.of("ADMINISTRATION"), rule.requiredRoles());
        assertEquals("/admin/page", rule.urlPattern());
        assertFalse(rule.isPublic());
    }

    @Test
    void multipleFragments_eachReportedIndividually() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
                <sec:authorize access="hasPermission('','ACHAT')">A</sec:authorize>
                <sec:authorize access="hasPermission('','REPORTING')">B</sec:authorize>
                """);

        List<SecurityRule> rules = extractor.extract(jsp.toString(), "/page");

        assertEquals(2, rules.size());
        assertTrue(rules.stream().anyMatch(r -> r.requiredRoles().contains("ACHAT")));
        assertTrue(rules.stream().anyMatch(r -> r.requiredRoles().contains("REPORTING")));
    }

    @Test
    void nestedFragments_eachReportedAtItsOwnLevel() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
                <sec:authorize access="hasPermission('','ACHAT')">
                  <sec:authorize access="hasPermission('','INTERNE')">
                    <a href="#">Historique</a>
                  </sec:authorize>
                </sec:authorize>
                """);

        List<SecurityRule> rules = extractor.extract(jsp.toString(), "/page");

        assertEquals(2, rules.size(), "Each block is its own rule, at the permission it itself declares");
        assertTrue(rules.stream().anyMatch(r -> r.requiredRoles().equals(Set.of("ACHAT"))));
        assertTrue(rules.stream().anyMatch(r -> r.requiredRoles().equals(Set.of("INTERNE"))));
    }

    @Test
    void pageWithNoAuthorizeTags_producesNoRules() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", "<div>no auth here</div>");

        assertTrue(extractor.extract(jsp.toString(), "/page").isEmpty());
    }

    @Test
    void unparseableAccessExpression_notDroppedAsPublic() throws IOException {
        Path jsp = write("src/main/webapp/WEB-INF/jsp/page.jsp", """
                <%@ taglib prefix="sec" uri="http://www.springframework.org/security/tags" %>
                <sec:authorize access="@customEvaluator.check(#id)">gated</sec:authorize>
                """);

        List<SecurityRule> rules = extractor.extract(jsp.toString(), "/page");

        assertEquals(1, rules.size());
        assertFalse(rules.get(0).isPublic(), "An unparseable expression must never read as public");
        assertTrue(rules.get(0).hasUnparsedExpression());
    }

    @Test
    void nonExistentFile_returnsEmptyList_neverThrows() {
        assertDoesNotThrow(() -> assertTrue(extractor.extract(tempDir.resolve("nope.jsp").toString(), "/x").isEmpty()));
    }
}
