package com.devmanchego.contextextractor.java.model;

import java.util.List;

/** Generic information about any Java class found during scanning. */
public final class ClassInfo {

    private final String fullyQualifiedName;
    private final String simpleName;
    private final String sourceFile;
    private final List<String> annotations;
    private final List<FieldInfo> fields;

    public ClassInfo(String fullyQualifiedName, String simpleName, String sourceFile,
                     List<String> annotations, List<FieldInfo> fields) {
        this.fullyQualifiedName = fullyQualifiedName;
        this.simpleName = simpleName;
        this.sourceFile = sourceFile;
        this.annotations = List.copyOf(annotations);
        this.fields = List.copyOf(fields);
    }

    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public String getSimpleName() { return simpleName; }
    public String getSourceFile() { return sourceFile; }
    public List<String> getAnnotations() { return annotations; }
    public List<FieldInfo> getFields() { return fields; }
}
