package com.devmanchego.contextextractor.render;

import java.nio.file.Path;
import java.util.List;

/**
 * Renders {@code index_specs.md} — the single entry point at the base output directory,
 * linking to every document in {@code full_specs/} (complete, monolithic documents) and
 * {@code indexed_specs/} (per-element indexes with drill-down detail documents).
 *
 * <p>This is the file an AI assistant should be pointed at first: it never grows with
 * the size of the analyzed application, only the number of documents produced.
 */
public final class RootIndexRenderer {

    /** One link entry: a human-readable title and the file it points to. */
    public record Entry(String title, Path file) {}

    public String render(Path baseDir, List<Entry> fullSpecs, List<Entry> indexedSpecs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Index Specs\n\n");
        sb.append("Central navigation index for all generated documentation. ")
          .append("Start here — this file stays small regardless of application size.\n\n");

        sb.append("## Full Specs\n");
        sb.append("*Complete, monolithic documents — read directly, or as a fallback ")
          .append("when no per-element index exists yet for a given area.*\n\n");
        appendLinks(sb, baseDir, fullSpecs);

        sb.append("\n## Indexed Specs\n");
        sb.append("*Per-element indexes with drill-down detail documents — load only what's ")
          .append("needed instead of the full document, to keep AI context usage low.*\n\n");
        appendLinks(sb, baseDir, indexedSpecs);

        return sb.toString();
    }

    private void appendLinks(StringBuilder sb, Path baseDir, List<Entry> entries) {
        if (entries.isEmpty()) {
            sb.append("*None generated.*\n");
            return;
        }
        for (Entry entry : entries) {
            sb.append("- [").append(entry.title()).append("](")
              .append(relativeLink(baseDir, entry.file())).append(")\n");
        }
    }

    private String relativeLink(Path baseDir, Path file) {
        return baseDir.relativize(file).toString().replace('\\', '/');
    }
}
