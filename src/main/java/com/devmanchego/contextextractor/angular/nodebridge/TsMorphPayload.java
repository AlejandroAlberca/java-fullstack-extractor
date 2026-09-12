package com.devmanchego.contextextractor.angular.nodebridge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Jackson POJOs that mirror the JSON contract produced by ts-morph-extractor.js.
 * All classes use @JsonIgnoreProperties(ignoreUnknown=true) for forward-compatibility.
 */
public final class TsMorphPayload {

    private TsMorphPayload() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Root {
        public String strategy;
        public String version;
        public List<ComponentEntry> components;
        public List<ServiceEntry> services;
        public List<ModelEntry> models;
        public List<RouteEntry> routes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RouteEntry {
        public String path;
        public String componentName;
        public String title;          // from data.title / data.breadcrumb
        public String redirectTo;
        public boolean lazy;
        public List<RouteEntry> children;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ComponentEntry {
        public String className;
        public String filePath;
        public String selector;
        public List<String> injectedServices;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServiceEntry {
        public String className;
        public String filePath;
        public List<HttpCallEntry> httpCalls;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HttpCallEntry {
        public String methodName;
        public String httpVerb;       // "GET", "POST", etc.
        public String urlTemplate;    // already normalized to {param} form
        public String responseType;   // already unwrapped from Observable<T>
        public String bodyType;       // null for GET/DELETE
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelEntry {
        public String name;
        public String filePath;
        public String kind;           // "interface" or "class"
        public List<FieldEntry> fields;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FieldEntry {
        public String name;
        public String type;
        public boolean optional;
    }
}
