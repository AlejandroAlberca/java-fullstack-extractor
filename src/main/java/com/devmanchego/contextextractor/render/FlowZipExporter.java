package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.callgraph.CallNode;
import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.devmanchego.contextextractor.java.model.ScheduledJobInfo;
import com.devmanchego.contextextractor.matching.MatchedFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports the full-flow diagrams (all top-level if/else, loops excluded) as a ZIP archive.
 *
 * ZIP structure:
 * <pre>
 *   index.md
 *   flows/
 *     flow-001-get-api-v1-foo.md
 *     flow-002-post-api-v1-foo.md
 *     ...
 *   batches/
 *     batch-001-myjob-runjob.md
 *     ...
 * </pre>
 *
 * Each individual file contains only the flow title and the Mermaid diagram.
 * {@code index.md} lists all flows and jobs with relative links navigable after extraction.
 */
public final class FlowZipExporter {

    private static final Logger log = LoggerFactory.getLogger(FlowZipExporter.class);

    private final FlowDiagramRenderer renderer = new FlowDiagramRenderer();

    /**
     * Builds and writes the ZIP archive to {@code outputZip}.
     *
     * @return number of individual flow/job files written inside the ZIP
     */
    public int export(List<MatchedFlow> flows,
                      Map<EndpointInfo, CallNode> callGraphs,
                      Map<ScheduledJobInfo, CallNode> jobCallGraphs,
                      Path outputZip) throws IOException {

        Built built = buildItems(flows, callGraphs, jobCallGraphs);

        Files.createDirectories(outputZip.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputZip),
                                                        StandardCharsets.UTF_8)) {
            writeEntry(zos, "index.md", built.indexMd());
            for (ZipItem item : built.flowItems())  writeEntry(zos, item.zipPath(), item.content());
            for (ZipItem item : built.jobItems())   writeEntry(zos, item.zipPath(), item.content());
        }

        int total = built.flowItems().size() + built.jobItems().size();
        log.info("FlowZipExporter: wrote {} entries to {}", total + 1, outputZip);
        return total;
    }

    /**
     * Writes the same content as {@link #export} directly as a plain directory tree
     * (no ZIP), rooted at {@code targetDir}:
     * <pre>
     *   targetDir/index-spec-flows.md
     *   targetDir/flows/flow-001-*.md
     *   targetDir/batches/batch-001-*.md
     * </pre>
     */
    public int exportToDirectory(List<MatchedFlow> flows,
                                 Map<EndpointInfo, CallNode> callGraphs,
                                 Map<ScheduledJobInfo, CallNode> jobCallGraphs,
                                 Path targetDir) throws IOException {
        return exportToDirectory(flows, callGraphs, jobCallGraphs, targetDir, null);
    }

    /**
     * Same as {@link #exportToDirectory(List, Map, Map, Path)}, but each flow document gets
     * an appended {@code ## Related} block from {@code relatedBlockFn} (may return an empty
     * string). Pass {@code null} to skip cross-reference sections entirely.
     */
    public int exportToDirectory(List<MatchedFlow> flows,
                                 Map<EndpointInfo, CallNode> callGraphs,
                                 Map<ScheduledJobInfo, CallNode> jobCallGraphs,
                                 Path targetDir,
                                 java.util.function.Function<MatchedFlow, String> relatedBlockFn)
            throws IOException {

        Built built = buildItems(flows, callGraphs, jobCallGraphs);

        Files.createDirectories(targetDir);
        writeFile(targetDir, "index-spec-flows.md", built.indexMd());
        for (ZipItem item : built.flowItems()) {
            String content = item.content();
            if (relatedBlockFn != null && item.flow() != null) {
                String related = relatedBlockFn.apply(item.flow());
                if (related != null && !related.isBlank()) content += "\n" + related;
            }
            writeFile(targetDir, item.zipPath(), content);
        }
        for (ZipItem item : built.jobItems())  writeFile(targetDir, item.zipPath(), item.content());

        int total = built.flowItems().size() + built.jobItems().size();
        log.info("FlowZipExporter: wrote {} entries to {}", total + 1, targetDir);
        return total;
    }

    /**
     * Ordered flow references (flow, slug, path within the flows directory) using the exact
     * same numbering as {@link #exportToDirectory}, so callers can register flow nodes for
     * cross-referencing and resolve each flow's detail-document path consistently.
     */
    public List<FlowRef> flowRefs(List<MatchedFlow> flows, Map<EndpointInfo, CallNode> callGraphs) {
        List<FlowRef> refs = new ArrayList<>();
        int flowNum = 1;
        for (MatchedFlow flow : flows) {
            if (callGraphs.get(flow.getJavaEndpoint()) == null) continue;
            String method = flow.getAngularCall().getHttpVerb().name();
            String path   = flow.getJavaEndpoint().getPathTemplate();
            String slug   = toSlug("flow-" + pad(flowNum) + "-" + method + "-" + path);
            refs.add(new FlowRef(flow, slug, "flows/" + slug + ".md", method + " " + path));
            flowNum++;
        }
        return refs;
    }

    /** One flow's identity for cross-referencing: {@code relPathWithinDir} is under the flows dir. */
    public record FlowRef(MatchedFlow flow, String slug, String relPathWithinDir, String label) {}

    private Built buildItems(List<MatchedFlow> flows,
                             Map<EndpointInfo, CallNode> callGraphs,
                             Map<ScheduledJobInfo, CallNode> jobCallGraphs) {
        List<ZipItem> flowItems = new ArrayList<>();
        List<ZipItem> jobItems  = new ArrayList<>();

        int flowNum = 1;
        for (MatchedFlow flow : flows) {
            CallNode root = callGraphs.get(flow.getJavaEndpoint());
            if (root == null) continue;

            String method = flow.getAngularCall().getHttpVerb().name();
            String path   = flow.getJavaEndpoint().getPathTemplate();
            String title  = "Flow " + flowNum + ": " + method + " " + path;
            String slug   = toSlug("flow-" + pad(flowNum) + "-" + method + "-" + path);
            String entry  = "flows/" + slug + ".md";
            String content = buildFlowMd(title, method + " " + path, root);

            flowItems.add(new ZipItem(title, entry, content, flow));
            flowNum++;
        }

        int jobNum = 1;
        for (Map.Entry<ScheduledJobInfo, CallNode> e : jobCallGraphs.entrySet()) {
            ScheduledJobInfo job  = e.getKey();
            CallNode         root = e.getValue();
            String simpleClass = simpleClassName(job.getClassName());
            String title  = "Batch " + jobNum + ": " + simpleClass + "::" + job.getMethodName();
            String slug   = toSlug("batch-" + pad(jobNum) + "-" + simpleClass + "-" + job.getMethodName());
            String entry  = "batches/" + slug + ".md";
            String diagramTitle = simpleClass + "::" + job.getMethodName();
            String content = buildFlowMd(title, diagramTitle, root);

            jobItems.add(new ZipItem(title, entry, content, null));
            jobNum++;
        }

        return new Built(flowItems, jobItems, buildIndex(flowItems, jobItems));
    }

    private record Built(List<ZipItem> flowItems, List<ZipItem> jobItems, String indexMd) {}

    // -----------------------------------------------------------------------
    // Content builders
    // -----------------------------------------------------------------------

    private String buildFlowMd(String title, String diagramTitle, CallNode root) {
        String mermaid = renderer.buildMermaidSourceFull(diagramTitle, root);
        return "## " + title + "\n\n"
             + "### Mermaid\n\n"
             + "```mermaid\n"
             + mermaid
             + "```\n";
    }

    private static String buildIndex(List<ZipItem> flows, List<ZipItem> jobs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# API Flows Index\n\n");

        sb.append("## API Flows\n\n");
        if (flows.isEmpty()) {
            sb.append("*No matched flows found.*\n");
        } else {
            for (ZipItem item : flows) {
                sb.append("- [").append(item.title()).append("](").append(item.zipPath()).append(")\n");
            }
        }

        sb.append("\n## Scheduled Jobs\n\n");
        if (jobs.isEmpty()) {
            sb.append("*No scheduled jobs found.*\n");
        } else {
            for (ZipItem item : jobs) {
                sb.append("- [").append(item.title()).append("](").append(item.zipPath()).append(")\n");
            }
        }

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // ZIP helpers
    // -----------------------------------------------------------------------

    private static void writeEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void writeFile(Path targetDir, String relativePath, String content) throws IOException {
        Path file = targetDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Naming helpers
    // -----------------------------------------------------------------------

    private static String toSlug(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static String pad(int n) {
        return String.format("%03d", n);
    }

    private static String simpleClassName(String fqn) {
        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
    }

    // -----------------------------------------------------------------------
    // Internal record
    // -----------------------------------------------------------------------

    private record ZipItem(String title, String zipPath, String content, MatchedFlow flow) {}

    // -----------------------------------------------------------------------
    // Static path helper (mirrors FlowDiagramRenderer convention)
    // -----------------------------------------------------------------------

    /**
     * Derives the ZIP output path from the main output file.
     * {@code /path/to/api-spec.md} → {@code /path/to/api-spec-flows-full.zip}
     */
    public static Path zipOutputPath(Path mainOutputFile) {
        String name = mainOutputFile.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return mainOutputFile.resolveSibling(base + "-flows-full.zip");
    }
}
