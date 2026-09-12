package com.devmanchego.contextextractor.angular.model;

import java.util.List;

/** A TypeScript interface or class used as a request/response model. */
public final class TsModelInfo {

    public enum Kind { INTERFACE, CLASS }

    private final String name;
    private final String filePath;
    private final Kind kind;
    private final List<TsFieldInfo> fields;

    public TsModelInfo(String name, String filePath, Kind kind, List<TsFieldInfo> fields) {
        this.name = name;
        this.filePath = filePath;
        this.kind = kind;
        this.fields = List.copyOf(fields);
    }

    public String getName() { return name; }
    public String getFilePath() { return filePath; }
    public Kind getKind() { return kind; }
    public List<TsFieldInfo> getFields() { return fields; }
}
