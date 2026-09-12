package com.devmanchego.contextextractor;

import com.devmanchego.contextextractor.java.parser.JavaParserFactory;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Application#parseJavaProject} excludes Maven's own {@code target/} build output — but
 * only as an actual path segment of the project itself, never as a substring match against the
 * whole absolute path. The old substring check silently excluded a project checked out under any
 * ancestor directory that happened to contain "target" — including this very test suite's own
 * fixtures, which live under {@code target/test-classes/} at runtime (see
 * {@code JspJQueryEndToEndTest}, which first caught this against a real fixture).
 */
class ParseJavaProjectTest {

    @TempDir
    Path tempDir;

    // Not a field initializer: @TempDir injection happens after instance construction.
    private JavaParser parser() { return JavaParserFactory.create(tempDir, List.of()); }

    private Path write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void aRealMavenTargetDirectory_isExcluded() throws IOException {
        write("target/generated-sources/Generated.java", "public class Generated {}");
        write("src/main/java/com/example/Real.java", "package com.example; public class Real {}");

        List<CompilationUnit> result = Application.parseJavaProject(tempDir, parser());

        assertEquals(1, result.size());
        assertTrue(result.get(0).getStorage().get().getPath().toString().contains("Real.java"));
    }

    @Test
    void aProjectPathThatMerelyContainsTheSubstringTarget_isNeverExcluded() throws IOException {
        // The exact bug: "target" as a substring anywhere in the absolute path (an ancestor
        // directory, a project named "target-app") is not the same as an actual target/ build
        // directory of this project, and must not be treated as one.
        Path root = tempDir.resolve("target-app-checkout");
        Files.createDirectories(root);
        Path file = root.resolve("src/main/java/com/example/Controller.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.example; public class Controller {}");

        List<CompilationUnit> result = Application.parseJavaProject(root, parser());

        assertEquals(1, result.size(), "a project whose own path merely contains \"target\" must still be scanned");
    }

    @Test
    void aFileNamedTargetDotJava_isNeverExcluded() throws IOException {
        // "target" as the file's own name (not a directory segment) must not trip the exclusion.
        write("src/main/java/com/example/Target.java", "package com.example; public class Target {}");

        List<CompilationUnit> result = Application.parseJavaProject(tempDir, parser());

        assertEquals(1, result.size());
    }
}
