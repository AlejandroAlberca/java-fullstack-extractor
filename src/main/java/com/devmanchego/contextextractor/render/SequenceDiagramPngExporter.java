package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.callgraph.CallNode;
import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.ScheduledJobInfo;
import com.devmanchego.contextextractor.matching.MatchedFlow;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
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
 * Exports sequence diagrams as PNG files using the embedded PlantUML engine.
 *
 * No external tools required — PlantUML uses its built-in Smetana layout engine
 * (a Java port of Graphviz) when Graphviz is not installed on the system.
 *
 * Call {@link #export} once per run when {@code --sequence-diagrams-dir} is provided.
 */
public final class SequenceDiagramPngExporter {

    private static final Logger log = LoggerFactory.getLogger(SequenceDiagramPngExporter.class);

    private final SequenceDiagramRenderer renderer = new SequenceDiagramRenderer();

    /**
     * Generates one PNG file per matched flow (with a call graph) and per scheduled job.
     *
     * @return list of generated file paths (for console reporting)
     */
    public List<Path> export(List<MatchedFlow> flows,
                             Map<EndpointInfo, CallNode> callGraphs,
                             Map<ScheduledJobInfo, CallNode> jobCallGraphs,
                             Path outputDir) throws IOException {
        System.setProperty("java.awt.headless", "true");

        List<Path> generated = new ArrayList<>();
        int flowNum = 1;

        for (MatchedFlow flow : flows) {
            CallNode root = callGraphs.get(flow.getJavaEndpoint());
            if (root == null) continue;

            String method = flow.getAngularCall().getHttpVerb().name();
            String path   = flow.getJavaEndpoint().getPathTemplate();
            String title  = method + " " + path;
            String puml   = renderer.buildPlantUmlSequenceSource(title, flow, root);
            String slug   = toSlug("seq-" + flowNum + "-" + method + "-" + path);
            Path   outFile = outputDir.resolve(slug + ".png");

            writePng(puml, outFile);
            generated.add(outFile);
            flowNum++;
        }

        int jobNum = 1;
        for (Map.Entry<ScheduledJobInfo, CallNode> entry : jobCallGraphs.entrySet()) {
            ScheduledJobInfo job  = entry.getKey();
            CallNode         root = entry.getValue();
            String simpleClass = job.getClassName().contains(".")
                    ? job.getClassName().substring(job.getClassName().lastIndexOf('.') + 1)
                    : job.getClassName();
            String title   = simpleClass + "::" + job.getMethodName();
            String puml    = renderer.buildPlantUmlSequenceSourceForJob(title, job, root);
            String slug    = toSlug("seq-job-" + jobNum + "-" + job.getMethodName());
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
            log.debug("Sequence PNG written: {}", outFile);
        } catch (IOException e) {
            log.error("Failed to write sequence PNG {}: {}", outFile, e.getMessage());
            throw e;
        }
    }

    private static String toSlug(String title) {
        return title.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
    }
}
