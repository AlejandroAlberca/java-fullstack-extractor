package com.devmanchego.contextextractor.angular.nodebridge;

import com.devmanchego.contextextractor.angular.model.*;
import com.devmanchego.contextextractor.java.model.HttpVerb;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Strategy A: invokes the bundled ts-morph Node.js script as a subprocess,
 * reads the structured JSON payload from stdout, and converts it to the
 * shared {@link AngularProject} intermediate model.
 */
public final class NodeBridge {

    private static final Logger log = LoggerFactory.getLogger(NodeBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 120;

    private final String nodeExecutable;

    public NodeBridge(String nodeExecutable) {
        this.nodeExecutable = nodeExecutable;
    }

    /**
     * Runs the ts-morph extractor against {@code angularProjectPath} and returns
     * the parsed Angular project model.
     *
     * @throws IOException if the subprocess fails or produces invalid JSON.
     */
    public AngularProject analyze(Path angularProjectPath) throws IOException {
        Path script = ScriptExtractor.extractScript();

        List<String> cmd = List.of(
                nodeExecutable,
                script.toString(),
                angularProjectPath.toAbsolutePath().toString()
        );
        log.info("Strategy A (ts-morph): running {}", String.join(" ", cmd));

        Process proc;
        try {
            proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new IOException("Failed to launch Node.js subprocess: " + e.getMessage(), e);
        }

        // Drain stderr in background to prevent blocking
        Thread stderrDrainer = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                err.lines().forEach(line -> log.debug("[ts-morph] {}", line));
            } catch (IOException ignored) {}
        });
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();

        String json;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            json = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished;
        try {
            finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for ts-morph subprocess.");
        }

        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("ts-morph subprocess timed out after " + TIMEOUT_SECONDS + "s.");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("ts-morph subprocess exited with code " + proc.exitValue()
                    + ". Check the log for stderr output.");
        }

        TsMorphPayload.Root payload = MAPPER.readValue(json, TsMorphPayload.Root.class);
        return convert(payload);
    }

    // -----------------------------------------------------------------------
    // Payload → domain model conversion
    // -----------------------------------------------------------------------

    private AngularProject convert(TsMorphPayload.Root payload) {
        List<ComponentInfo> components = Optional.ofNullable(payload.components)
                .orElse(List.of()).stream()
                .map(this::toComponentInfo)
                .collect(Collectors.toList());

        List<ServiceInfo> services = Optional.ofNullable(payload.services)
                .orElse(List.of()).stream()
                .map(this::toServiceInfo)
                .collect(Collectors.toList());

        List<TsModelInfo> models = Optional.ofNullable(payload.models)
                .orElse(List.of()).stream()
                .map(this::toTsModelInfo)
                .collect(Collectors.toList());

        List<RouteNode> routes = Optional.ofNullable(payload.routes)
                .orElse(List.of()).stream()
                .map(this::toRouteNode)
                .collect(Collectors.toList());

        return new AngularProject(AngularProject.ParsingStrategy.NODE_TS_MORPH,
                com.devmanchego.contextextractor.frontend.FrontendFramework.ANGULAR,
                components, services, models, routes);
    }

    private RouteNode toRouteNode(TsMorphPayload.RouteEntry e) {
        List<RouteNode> children = Optional.ofNullable(e.children)
                .orElse(List.of()).stream()
                .map(this::toRouteNode)
                .collect(Collectors.toList());
        return new RouteNode(e.path, e.componentName, e.title, e.redirectTo, e.lazy, children);
    }

    private ComponentInfo toComponentInfo(TsMorphPayload.ComponentEntry e) {
        return new ComponentInfo(
                e.className,
                e.filePath,
                Optional.ofNullable(e.selector).orElse(""),
                Optional.ofNullable(e.injectedServices).orElse(List.of())
        );
    }

    private ServiceInfo toServiceInfo(TsMorphPayload.ServiceEntry e) {
        List<HttpCallInfo> calls = Optional.ofNullable(e.httpCalls)
                .orElse(List.of()).stream()
                .map(this::toHttpCallInfo)
                .collect(Collectors.toList());
        return new ServiceInfo(e.className, e.filePath, calls);
    }

    private HttpCallInfo toHttpCallInfo(TsMorphPayload.HttpCallEntry e) {
        HttpVerb verb;
        try {
            verb = HttpVerb.valueOf(e.httpVerb.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown HTTP verb '{}' in ts-morph payload — defaulting to GET.", e.httpVerb);
            verb = HttpVerb.GET;
        }
        return new HttpCallInfo(e.methodName, verb, e.urlTemplate, e.responseType, e.bodyType);
    }

    private TsModelInfo toTsModelInfo(TsMorphPayload.ModelEntry e) {
        TsModelInfo.Kind kind = "interface".equalsIgnoreCase(e.kind)
                ? TsModelInfo.Kind.INTERFACE : TsModelInfo.Kind.CLASS;
        List<TsFieldInfo> fields = Optional.ofNullable(e.fields)
                .orElse(List.of()).stream()
                .map(f -> new TsFieldInfo(f.name, f.type, f.optional))
                .collect(Collectors.toList());
        return new TsModelInfo(e.name, e.filePath, kind, fields);
    }
}
