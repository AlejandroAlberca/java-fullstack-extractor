package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.callgraph.CallNode;
import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.ScheduledJobInfo;
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
 * Exports flow diagrams as PNG files using the embedded PlantUML engine.
 *
 * No external tools required — PlantUML uses its built-in Smetana layout engine
 * (a Java port of Graphviz) when Graphviz is not installed on the system.
 *
 * Call {@link #export} once per run when {@code --flow-diagrams-dir} is provided.
 */
public final class FlowDiagramPngExporter {

    private static final Logger log = LoggerFactory.getLogger(FlowDiagramPngExporter.class);

    private final FlowDiagramRenderer renderer = new FlowDiagramRenderer();

    /**
     * Generates one PNG file per matched flow and per scheduled job in {@code outputDir}.
     *
     * @return list of generated file paths (for console reporting)
     */
    public List<Path> export(List<MatchedFlow> flows,
                             Map<EndpointInfo, CallNode> callGraphs,
                             Map<ScheduledJobInfo, CallNode> jobCallGraphs,
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
            String puml   = renderer.buildPlantUmlSource(title, root);
            String slug   = toSlug("flow-" + flowNum + "-" + method + "-" + path);
            Path   outFile = outputDir.resolve(slug + ".png");

            writePng(puml, outFile);
            generated.add(outFile);
            flowNum++;
        }

        int jobNum = 1;
        for (Map.Entry<ScheduledJobInfo, CallNode> entry : jobCallGraphs.entrySet()) {
            ScheduledJobInfo job  = entry.getKey();
            CallNode         root = entry.getValue();
            String title = job.getClassName() + "::" + job.getMethodName();
            String puml  = renderer.buildPlantUmlSource(title, root);
            String slug  = toSlug("job-" + jobNum + "-" + job.getMethodName());
            Path   outFile = outputDir.resolve(slug + ".png");

            writePng(puml, outFile);
            generated.add(outFile);
            jobNum++;
        }

        return generated;
    }

    /**
     * Same as {@link #export} but uses the unfiltered PlantUML source
     * ({@link FlowDiagramRenderer#buildPlantUmlSourceFull}) — all top-level
     * if/else conditions are included, loops still excluded.
     */
    public List<Path> exportFull(List<MatchedFlow> flows,
                                 Map<EndpointInfo, CallNode> callGraphs,
                                 Map<ScheduledJobInfo, CallNode> jobCallGraphs,
                                 Path outputDir) throws IOException {
        System.setProperty("java.awt.headless", "true");

        List<Path> generated = new ArrayList<>();
        int flowNum = 1;

        for (MatchedFlow flow : flows) {
            CallNode root = callGraphs.get(flow.getJavaEndpoint());
            if (root == null) continue;

            String method  = flow.getAngularCall().getHttpVerb().name();
            String path    = flow.getJavaEndpoint().getPathTemplate();
            String title   = method + " " + path;
            String puml    = renderer.buildPlantUmlSourceFull(title, root);
            String slug    = toSlug("flow-full-" + flowNum + "-" + method + "-" + path);
            Path   outFile = outputDir.resolve(slug + ".png");

            writePng(puml, outFile);
            generated.add(outFile);
            flowNum++;
        }

        int jobNum = 1;
        for (Map.Entry<ScheduledJobInfo, CallNode> entry : jobCallGraphs.entrySet()) {
            ScheduledJobInfo job  = entry.getKey();
            CallNode         root = entry.getValue();
            String title   = job.getClassName() + "::" + job.getMethodName();
            String puml    = renderer.buildPlantUmlSourceFull(title, root);
            String slug    = toSlug("job-full-" + jobNum + "-" + job.getMethodName());
            Path   outFile = outputDir.resolve(slug + ".png");

            writePng(puml, outFile);
            generated.add(outFile);
            jobNum++;
        }

        return generated;
    }

    private void writePng(String pumlSource, Path outFile) throws IOException {
        try (OutputStream os = Files.newOutputStream(outFile)) {
            // No global sanitization here: a blind <,> replacement corrupts PlantUML
            // arrows (->, -->). Label escaping is the renderer's responsibility.
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
