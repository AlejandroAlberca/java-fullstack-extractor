package com.devmanchego.contextextractor.java.exception;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a custom application exception discovered in the backend source code.
 * Captures exception hierarchy, handler mapping, throw locations, and message patterns.
 */
public record ExceptionInfo(
        String exceptionClassName,      // e.g. "ResourceNotFoundException"
        String fullyQualifiedName,      // e.g. "com.example.hrapp.exception.ResourceNotFoundException"
        String domain,                  // inferred from package: "Department", "Employee", etc.
        String errorCode,               // synthetic: "DEPT-001", "EMP-001"
        int httpStatus,                 // from @ExceptionHandler (404, 409, 500, etc.), -1 if no handler
        String messageTemplate,         // reconstructed: "Department not found with id: {id}"
        String originClass,             // fully-qualified class that throws it
        String originMethod,            // method name that throws it
        int lineNumber,                 // line number of throw statement
        Set<String> parameterNames,     // inferred parameters that appear in template: {"id"}
        List<String> throwLocations,    // all locations where this exception is thrown (for multiplicity)
        String exceptionHandlerClass,   // which @RestControllerAdvice handles it (or null)
        boolean hasHandler,             // true if @ExceptionHandler found
        boolean isHumanFacingError      // true if not a system/framework exception
) {

    public ExceptionInfo {
        Objects.requireNonNull(exceptionClassName);
        Objects.requireNonNull(fullyQualifiedName);
        Objects.requireNonNull(messageTemplate);
    }

    /**
     * Returns true if this exception has no handler (will bubble up as 500).
     */
    public boolean isOrphan() {
        return !hasHandler;
    }

    /**
     * Returns true if handler was found but HTTP status is 5xx.
     */
    public boolean isServerError() {
        return httpStatus >= 500 && httpStatus < 600;
    }

    /**
     * Synthetic code format: DEPT-001 (domain + sequence).
     */
    public String getErrorCodeOrDefault() {
        return errorCode != null ? errorCode : "UNK-000";
    }
}
