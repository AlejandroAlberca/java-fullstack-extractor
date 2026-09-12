package com.devmanchego.contextextractor.angular.model;

/** A single field declared in a TypeScript interface or class. */
public final class TsFieldInfo {

    private final String name;
    private final String type;
    private final boolean optional;

    public TsFieldInfo(String name, String type, boolean optional) {
        this.name = name;
        this.type = type;
        this.optional = optional;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public boolean isOptional() { return optional; }
}
