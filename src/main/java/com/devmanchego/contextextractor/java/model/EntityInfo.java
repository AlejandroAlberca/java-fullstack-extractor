package com.devmanchego.contextextractor.java.model;

import java.util.List;

/** A JPA entity class with its fields, relations, and table metadata. */
public final class EntityInfo {

    private final String fullyQualifiedName;
    private final String simpleName;
    private final String tableName;        // from @Table(name=...) or derived from class name
    private final String sourceFile;
    private final List<FieldInfo> fields;  // excludes relation fields
    private final List<RelationInfo> relations;
    private final List<RepositoryMethodInfo> repositoryMethods; // from linked @Repository
    private final String description;      // class-level Javadoc, null if none

    public EntityInfo(String fullyQualifiedName, String simpleName, String tableName,
                      String sourceFile, List<FieldInfo> fields,
                      List<RelationInfo> relations,
                      List<RepositoryMethodInfo> repositoryMethods) {
        this(fullyQualifiedName, simpleName, tableName, sourceFile, fields,
                relations, repositoryMethods, null);
    }

    public EntityInfo(String fullyQualifiedName, String simpleName, String tableName,
                      String sourceFile, List<FieldInfo> fields,
                      List<RelationInfo> relations,
                      List<RepositoryMethodInfo> repositoryMethods,
                      String description) {
        this.fullyQualifiedName = fullyQualifiedName;
        this.simpleName = simpleName;
        this.tableName = tableName;
        this.sourceFile = sourceFile;
        this.fields = List.copyOf(fields);
        this.relations = List.copyOf(relations);
        this.repositoryMethods = List.copyOf(repositoryMethods);
        this.description = description;
    }

    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public String getSimpleName() { return simpleName; }
    public String getTableName() { return tableName; }
    public String getSourceFile() { return sourceFile; }
    public List<FieldInfo> getFields() { return fields; }
    public List<RelationInfo> getRelations() { return relations; }
    public List<RepositoryMethodInfo> getRepositoryMethods() { return repositoryMethods; }
    public String getDescription() { return description; }
}
