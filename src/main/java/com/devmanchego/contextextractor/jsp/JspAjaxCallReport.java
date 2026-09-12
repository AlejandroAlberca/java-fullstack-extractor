package com.devmanchego.contextextractor.jsp;

import com.devmanchego.contextextractor.jsp.JQueryAjaxCallExtractor.CallSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The AJAX call sites of one JSP view's bundle (Phase 03), for its page document: every site of
 * the view's own entry module in full — extracted, or unparseable with the reason — and the
 * shared modules the bundle also pulls in, summarised per module so the same shared code is not
 * repeated in full on every page that imports it.
 */
public final class JspAjaxCallReport {

    private static final Logger log = LoggerFactory.getLogger(JspAjaxCallReport.class);

    /** A shared module of the bundle: how many of its sites were extracted, and each one that wasn't. */
    public record ModuleSummary(String module, int extracted, List<String> unparseable) {
        public ModuleSummary {
            unparseable = List.copyOf(unparseable);
        }
    }

    public record Result(String unboundReason, String entryModule, List<CallSite> entryCalls,
                         List<ModuleSummary> sharedModules) {
        public Result {
            entryCalls = List.copyOf(entryCalls);
            sharedModules = List.copyOf(sharedModules);
        }

        static Result unbound(String reason) {
            return new Result(reason, null, List.of(), List.of());
        }

        public boolean bound() { return unboundReason == null; }
    }

    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

    private final JQueryAjaxCallExtractor extractor = new JQueryAjaxCallExtractor();

    public Result of(String jspFilePath) {
        if (jspFilePath == null || JspRouteReconstructor.UNRESOLVED_FILE.equals(jspFilePath)) {
            return Result.unbound("no JSP view backs this route");
        }
        Path jsp;
        try {
            jsp = Path.of(jspFilePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return Result.unbound("invalid view path");
        }
        if (!Files.isRegularFile(jsp)) return Result.unbound("no JSP view backs this route");
        String key;
        try {
            key = jsp + "|" + Files.getLastModifiedTime(jsp).toMillis();
        } catch (IOException e) {
            key = jsp.toString();
        }
        return CACHE.computeIfAbsent(key, k -> {
            try {
                return compute(jsp);
            } catch (Exception e) {
                log.warn("JspAjaxCallReport: could not report {}: {}", jsp, e.getMessage());
                return Result.unbound("call-site extraction failed: " + e.getMessage());
            }
        });
    }

    private Result compute(Path jsp) {
        Path webapp = JspFileParser.locateWebappRoot(jsp);
        if (webapp == null) return Result.unbound("no web application root (WEB-INF) above this view");
        JspPage page = new JspFileParser().parse(jsp, webapp);
        JspBundleExtractor.BoundView view = JspBundleExtractor.bindView(jsp, webapp, page);
        if (!view.bound()) return Result.unbound(view.unboundReason());

        List<CallSite> entryCalls = List.of();
        List<ModuleSummary> shared = new ArrayList<>();
        for (Path module : view.closure()) {
            String content;
            try {
                content = Files.readString(module, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            List<CallSite> sites = extractor.extract(content);
            if (module.equals(view.entry())) {
                entryCalls = sites;
            } else if (!sites.isEmpty()) {
                shared.add(new ModuleSummary(rel(module, view.frontendRoot()),
                        (int) sites.stream().filter(CallSite::extracted).count(),
                        sites.stream().filter(s -> !s.extracted())
                                .map(s -> "line " + s.line() + " — " + s.unparseableReason())
                                .collect(Collectors.toList())));
            }
        }
        return new Result(null, rel(view.entry(), view.frontendRoot()), entryCalls, shared);
    }

    private static String rel(Path p, Path root) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return p.toString().replace('\\', '/');
        }
    }
}
