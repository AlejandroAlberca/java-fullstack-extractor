package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.EndpointInfo;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;

/** Common interface for all backend endpoint extractor implementations. */
public interface EndpointExtractor {

    /**
     * Extracts all HTTP endpoints declared in the given compilation unit.
     * Returns an empty list (never null) when the compilation unit contains
     * no endpoints recognized by this extractor.
     *
     * @param cu  the parsed Java source file
     * @param sourceFilePath  absolute path of the source file (for reporting)
     */
    List<EndpointInfo> extract(CompilationUnit cu, String sourceFilePath);
}
