package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.callgraph.CallNode;
import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.matching.MatchedFlow;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exports class diagrams as PNG files using the embedded PlantUML engine.
 *
 * No external tools required — PlantUML uses its built-in Smetana layout engine
 * (a Java port of Graphviz) when Graphviz is not installed on the system.
 *
 * Call {@link #export} once per run when {@code --class-diagrams-dir} is provided.
 */
public final class ClassDiagramPngExporter {

    private static final Logger log = LoggerFactory.getLogger(ClassDiagramPngExporter.class);

    private final ClassDiagramRenderer renderer = new ClassDiagramRenderer();

    /**
     * Generates one PNG file per matched flow in {@code outputDir}.
     *
     * @return list of generated file paths (for console reporting)
     */
    public List<Path> export(List<MatchedFlow> flows,
                             Map<EndpointInfo, CallNode> callGraphs,
                             Path outputDir) throws IOException {
        // Ensure headless mode for AWT (required on CI/servers without a display)
        System.setProperty("java.awt.headless", "true");

        List<Path> generated = new ArrayList<>();
        int flowNum = 1;

        for (MatchedFlow flow : flows) {
            CallNode root = callGraphs.get(flow.getJavaEndpoint());
            if (root == null) continue;

            String method = flow.getAngularCall().getHttpVerb().name();
            String path   = flow.getJavaEndpoint().getPathTemplate();
            String title  = method + " " + path;
            String puml   = renderer.buildPlantUmlClassSource(title, flow, root);
            String slug   = toSlug("cls-" + flowNum + "-" + method + "-" + path);
            Path   outFile = outputDir.resolve(slug + ".png");

            writePng(puml, outFile);
            generated.add(outFile);
            flowNum++;
        }

        return generated;
    }

    private void writePng(String pumlSource, Path outFile) throws IOException {
        try (OutputStream os = Files.newOutputStream(outFile)) {
            // No global sanitization here: a blind <,> replacement corrupts PlantUML
            // arrows (..>, --|>). Label/type escaping is the renderer's responsibility.
            SourceStringReader reader = new SourceStringReader(pumlSource);
            reader.outputImage(os, new FileFormatOption(FileFormat.PNG));
            log.debug("PNG written: {}", outFile);
        } catch (IOException e) {
            log.error("Failed to write PNG {}: {}", outFile, e.getMessage());
            throw e;
        }
    }

    /** Converts a title string to a safe filename slug (lowercase, hyphens, no special chars). */
    private static String toSlug(String title) {
        return title.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
    }
}
