package com.devmanchego.contextextractor.java.model;

/** A method on a @Repository interface, optionally carrying a literal @Query string. */
public final class RepositoryMethodInfo {

    private final String methodName;
    private final String returnType;
    private final String queryLiteral;   // null if no @Query annotation

    public RepositoryMethodInfo(String methodName, String returnType, String queryLiteral) {
        this.methodName = methodName;
        this.returnType = returnType;
        this.queryLiteral = queryLiteral;
    }

    public String getMethodName() { return methodName; }
    public String getReturnType() { return returnType; }
    public String getQueryLiteral() { return queryLiteral; }
    public boolean hasQuery() { return queryLiteral != null && !queryLiteral.isBlank(); }
}
