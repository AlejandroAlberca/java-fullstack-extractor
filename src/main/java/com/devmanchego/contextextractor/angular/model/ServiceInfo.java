package com.devmanchego.contextextractor.angular.model;

import java.util.List;

/** An Angular @Injectable service with its HTTP calls. */
public final class ServiceInfo {

    private final String className;
    private final String filePath;
    private final List<HttpCallInfo> httpCalls;

    public ServiceInfo(String className, String filePath, List<HttpCallInfo> httpCalls) {
        this.className = className;
        this.filePath = filePath;
        this.httpCalls = List.copyOf(httpCalls);
    }

    public String getClassName() { return className; }
    public String getFilePath() { return filePath; }
    public List<HttpCallInfo> getHttpCalls() { return httpCalls; }
}
