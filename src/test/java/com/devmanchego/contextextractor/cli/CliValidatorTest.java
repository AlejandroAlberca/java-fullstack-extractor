package com.devmanchego.contextextractor.cli;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class CliValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void validMinimalArgs_defaultOutputFileInFullSpecsDir() {
        Path java    = tempDir.resolve("java-project");
        Path angular = tempDir.resolve("angular-project");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString()
        });

        assertEquals(java.toAbsolutePath().normalize(),    result.getJavaProjectPath());
        assertEquals(angular.toAbsolutePath().normalize(), result.getAngularProjectPath());
        assertTrue(result.getOutputFile().toString().endsWith("full-stack-spec.md"));
        assertEquals("full_specs", result.getOutputFile().getParent().getFileName().toString());
    }

    @Test
    void outputFileNameOnly_writtenUnderFullSpecsInCurrentDir() {
        Path java    = tempDir.resolve("java-project");
        Path angular = tempDir.resolve("angular-project");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(), "my-spec.md"
        });

        assertTrue(result.getOutputFile().toString().endsWith("my-spec.md"));
        assertEquals("full_specs", result.getOutputFile().getParent().getFileName().toString());
    }

    @Test
    void outputFileWithDirectory_fullSpecsAndIndexedSpecsCreated() {
        Path java    = tempDir.resolve("java-project");
        Path angular = tempDir.resolve("angular-project");
        Path outFile = tempDir.resolve("out").resolve("nested").resolve("api-spec.md");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(), outFile.toString()
        });

        Path baseDir = outFile.getParent();
        assertTrue(baseDir.resolve("full_specs").toFile().exists(), "full_specs must be created");
        assertTrue(baseDir.resolve("indexed_specs").toFile().exists(), "indexed_specs must be created");
        assertEquals(baseDir.resolve("full_specs").resolve("api-spec.md").toAbsolutePath().normalize(),
                result.getOutputFile());
        assertEquals(baseDir.resolve("index_specs.md").toAbsolutePath().normalize(),
                result.getRootIndexFile());
    }

    @Test
    void outputDirFlag_routesOutputUnderGivenBaseDirectory() {
        Path java      = tempDir.resolve("java-project");
        Path angular   = tempDir.resolve("angular-project");
        Path outputDir = tempDir.resolve("reports");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(), "api-spec.md",
                "--output-dir", outputDir.toString()
        });

        Path baseDir = outputDir.toAbsolutePath().normalize();
        assertEquals(baseDir, result.getBaseOutputDir());
        assertEquals(baseDir.resolve("full_specs").resolve("api-spec.md"), result.getOutputFile());
        assertEquals(baseDir.resolve("indexed_specs"), result.getIndexedSpecsDir());
        assertEquals(baseDir.resolve("index_specs.md"), result.getRootIndexFile());
        assertTrue(baseDir.resolve("full_specs").toFile().exists());
        assertTrue(baseDir.resolve("indexed_specs").toFile().exists());
    }

    @Test
    void outputFileWithoutMdExtension_extensionAppended() {
        Path java    = tempDir.resolve("java-project");
        Path angular = tempDir.resolve("angular-project");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(), "my-spec"
        });

        assertTrue(result.getOutputFile().toString().endsWith("my-spec.md"));
    }

    @Test
    void tooFewArgs_throwsCliException() {
        assertThrows(CliValidator.CliException.class, () ->
                CliValidator.validate(new String[]{"only-one-arg"}));
    }

    @Test
    void nonExistentJavaPath_throwsCliException() {
        assertThrows(CliValidator.CliException.class, () ->
                CliValidator.validate(new String[]{"/nonexistent/java", "/nonexistent/angular"}));
    }

    @Test
    void commonAncestor_siblingDirectories_returnsParent() {
        Path backend  = Paths.get("/workspace/project/backend").toAbsolutePath().normalize();
        Path frontend = Paths.get("/workspace/project/frontend").toAbsolutePath().normalize();
        Path expected = Paths.get("/workspace/project").toAbsolutePath().normalize();

        assertEquals(expected, CliValidator.commonAncestor(backend, frontend));
    }

    @Test
    void commonAncestor_oneIsAncestorOfOther_returnsAncestor() {
        Path root  = Paths.get("/workspace/project").toAbsolutePath().normalize();
        Path child = Paths.get("/workspace/project/backend/src").toAbsolutePath().normalize();

        assertEquals(root, CliValidator.commonAncestor(root, child));
    }

    @Test
    void validArgs_rootPathIsCommonAncestor() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString()
        });

        assertEquals(tempDir.toAbsolutePath().normalize(), result.getRootPath());
    }

    @Test
    void urlPrefixMap_parsedFromFlags() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(),
                "--url-prefix-map", "applicationConfigService.getEndpointFor(API_ROOT_V5)=/api/v5/",
                "--url-prefix-map", "{other}{method}=/other/"
        });

        assertEquals(2, result.getUrlPrefixMap().size());
        assertTrue(result.getUrlPrefixMap().containsValue("/api/v5/"));
        assertTrue(result.getUrlPrefixMap().containsValue("/other/"));
    }

    @Test
    void urlPrefixMap_afterOutputFilePath_bothParsed() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(),
                tempDir.resolve("spec.md").toString(),
                "--url-prefix-map", "svc.get(CONST)=/api/"
        });

        assertTrue(result.getOutputFile().toString().endsWith("spec.md"));
        assertEquals(1, result.getUrlPrefixMap().size());
    }

    @Test
    void indexDetailThreshold_defaultsToThree() {
        Path java    = tempDir.resolve("java-project");
        Path angular = tempDir.resolve("angular-project");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString()
        });

        assertEquals(3, result.getIndexDetailThreshold());
    }

    @Test
    void indexDetailThreshold_flagOverridesDefault() {
        Path java    = tempDir.resolve("java-project");
        Path angular = tempDir.resolve("angular-project");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(), "spec.md",
                "--index-detail-threshold", "10"
        });

        assertEquals(10, result.getIndexDetailThreshold());
    }

    @Test
    void indexDetailThreshold_invalidValue_throwsCliException() {
        Path java    = tempDir.resolve("java-project");
        Path angular = tempDir.resolve("angular-project");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        assertThrows(CliValidator.CliException.class, () -> CliValidator.validate(new String[]{
                java.toString(), angular.toString(), "spec.md",
                "--index-detail-threshold", "not-a-number"
        }));
    }

    @Test
    void resolveOutput_nameOnly_usesCurrentDirWithFullSpecsSubdir() {
        CliValidator.ResolvedOutput result = CliValidator.resolveOutput("api-spec.md", null);
        assertTrue(result.outputFile().isAbsolute());
        assertEquals("api-spec.md", result.outputFile().getFileName().toString());
        assertEquals("full_specs", result.outputFile().getParent().getFileName().toString());
    }

    @Test
    void resolveOutput_withDirectory_fullAndIndexedSpecsCreated() {
        Path outFile = tempDir.resolve("subdir").resolve("spec.md");
        CliValidator.ResolvedOutput result = CliValidator.resolveOutput(outFile.toString(), null);
        Path baseDir = outFile.getParent();
        assertTrue(baseDir.resolve("full_specs").toFile().exists());
        assertTrue(baseDir.resolve("indexed_specs").toFile().exists());
        assertEquals(baseDir.resolve("full_specs").resolve("spec.md").toAbsolutePath().normalize(),
                result.outputFile());
    }

    @Test
    void resolveOutput_withOutputDirFlag_baseDirIsFlagValue() {
        Path outputDir = tempDir.resolve("centralized");
        CliValidator.ResolvedOutput result = CliValidator.resolveOutput("spec.md",
                CliValidator.resolveDirectory(outputDir.toString(), "--output-dir"));
        assertEquals(outputDir.toAbsolutePath().normalize(), result.baseOutputDir());
        assertEquals(outputDir.toAbsolutePath().normalize().resolve("full_specs").resolve("spec.md"),
                result.outputFile());
    }

    @Test
    void flowDiagramsDir_parsedAndDirectoryCreated() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        Path diagDir = tempDir.resolve("diagrams").resolve("flows");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(),
                "--flow-diagrams-dir", diagDir.toString()
        });

        assertTrue(result.getFlowDiagramsDir().isPresent());
        assertEquals(diagDir.toAbsolutePath().normalize(), result.getFlowDiagramsDir().get());
        assertTrue(diagDir.toFile().exists(), "Directory must be created if absent");
    }

    @Test
    void noFlowDiagramsDir_optionalIsEmpty() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString()
        });

        assertTrue(result.getFlowDiagramsDir().isEmpty());
    }

    @Test
    void sequenceDiagramsDir_parsedAndDirectoryCreated() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        Path seqDir  = tempDir.resolve("diagrams").resolve("sequence");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(),
                "--sequence-diagrams-dir", seqDir.toString()
        });

        assertTrue(result.getSequenceDiagramsDir().isPresent());
        assertEquals(seqDir.toAbsolutePath().normalize(), result.getSequenceDiagramsDir().get());
        assertTrue(seqDir.toFile().exists(), "Directory must be created if absent");
    }

    @Test
    void noSequenceDiagramsDir_optionalIsEmpty() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString()
        });

        assertTrue(result.getSequenceDiagramsDir().isEmpty());
    }

    @Test
    void classDiagramsDir_parsedAndDirectoryCreated() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        Path clsDir  = tempDir.resolve("diagrams").resolve("classes");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString(),
                "--class-diagrams-dir", clsDir.toString()
        });

        assertTrue(result.getClassDiagramsDir().isPresent());
        assertEquals(clsDir.toAbsolutePath().normalize(), result.getClassDiagramsDir().get());
        assertTrue(clsDir.toFile().exists(), "Directory must be created if absent");
    }

    @Test
    void noClassDiagramsDir_optionalIsEmpty() {
        Path java    = tempDir.resolve("backend");
        Path angular = tempDir.resolve("frontend");
        java.toFile().mkdirs();
        angular.toFile().mkdirs();

        CliArguments result = CliValidator.validate(new String[]{
                java.toString(), angular.toString()
        });

        assertTrue(result.getClassDiagramsDir().isEmpty());
    }
}
