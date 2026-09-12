package com.devmanchego.contextextractor.angular.model;

import com.devmanchego.contextextractor.java.model.HttpVerb;

/**
 * An HTTP call made by an Angular service method.
 * urlTemplate uses canonical {param} placeholders (already normalized from TS template literals).
 */
public final class HttpCallInfo {

    private final String methodName;
    private final HttpVerb httpVerb;
    private final String urlTemplate;
    private final String responseType;   // unwrapped (Observable<T> → T, Promise<T> → T, T[] stays T[])
    private final String bodyType;       // null for GET/DELETE

    public HttpCallInfo(String methodName, HttpVerb httpVerb, String urlTemplate,
                        String responseType, String bodyType) {
        this.methodName = methodName;
        this.httpVerb = httpVerb;
        this.urlTemplate = urlTemplate;
        this.responseType = responseType;
        this.bodyType = bodyType;
    }

    public String getMethodName() { return methodName; }
    public HttpVerb getHttpVerb() { return httpVerb; }
    public String getUrlTemplate() { return urlTemplate; }
    public String getResponseType() { return responseType; }
    public String getBodyType() { return bodyType; }
}
