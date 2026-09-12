package com.devmanchego.contextextractor.java.model;

import java.util.List;

/** A Java DTO (Data Transfer Object) class with its fields and validation rules. */
public final class DtoInfo {

    private final String fullyQualifiedName;
    private final String simpleName;
    private final String sourceFile;
    private final List<FieldInfo> fields;

    public DtoInfo(String fullyQualifiedName, String simpleName,
                   String sourceFile, List<FieldInfo> fields) {
        this.fullyQualifiedName = fullyQualifiedName;
        this.simpleName = simpleName;
        this.sourceFile = sourceFile;
        this.fields = List.copyOf(fields);
    }

    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public String getSimpleName() { return simpleName; }
    public String getSourceFile() { return sourceFile; }
    public List<FieldInfo> getFields() { return fields; }
}
