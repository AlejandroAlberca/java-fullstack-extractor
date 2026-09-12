package com.devmanchego.contextextractor.matching;

import com.devmanchego.contextextractor.angular.model.HttpCallInfo;
import com.devmanchego.contextextractor.angular.model.ServiceInfo;
import com.devmanchego.contextextractor.angular.model.TsModelInfo;
import com.devmanchego.contextextractor.java.model.EndpointInfo;

import java.util.Optional;

/**
 * A matched Front-End ↔ Back-End API flow.
 * Carries the Angular service call, the matched Java endpoint, and the
 * correlated TypeScript model (derived from the endpoint's response/body type).
 */
public final class MatchedFlow {

    private final ServiceInfo angularService;
    private final HttpCallInfo angularCall;
    private final EndpointInfo javaEndpoint;
    private final TsModelInfo tsResponseModel;   // may be null
    private final TsModelInfo tsRequestModel;    // may be null
    private final boolean fuzzyMatch;

    public MatchedFlow(ServiceInfo angularService, HttpCallInfo angularCall,
                       EndpointInfo javaEndpoint,
                       TsModelInfo tsResponseModel, TsModelInfo tsRequestModel) {
        this(angularService, angularCall, javaEndpoint, tsResponseModel, tsRequestModel, false);
    }

    public MatchedFlow(ServiceInfo angularService, HttpCallInfo angularCall,
                       EndpointInfo javaEndpoint,
                       TsModelInfo tsResponseModel, TsModelInfo tsRequestModel,
                       boolean fuzzyMatch) {
        this.angularService = angularService;
        this.angularCall = angularCall;
        this.javaEndpoint = javaEndpoint;
        this.tsResponseModel = tsResponseModel;
        this.tsRequestModel = tsRequestModel;
        this.fuzzyMatch = fuzzyMatch;
    }

    public ServiceInfo getAngularService() { return angularService; }
    public HttpCallInfo getAngularCall() { return angularCall; }
    public EndpointInfo getJavaEndpoint() { return javaEndpoint; }
    public Optional<TsModelInfo> getTsResponseModel() { return Optional.ofNullable(tsResponseModel); }
    public Optional<TsModelInfo> getTsRequestModel() { return Optional.ofNullable(tsRequestModel); }
    public boolean isFuzzyMatch() { return fuzzyMatch; }
}
