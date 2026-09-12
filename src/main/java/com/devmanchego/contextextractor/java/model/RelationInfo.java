package com.devmanchego.contextextractor.java.model;

/** A JPA relationship on an entity (@OneToMany, @ManyToOne, @OneToOne, @ManyToMany). */
public final class RelationInfo {

    public enum Kind { ONE_TO_MANY, MANY_TO_ONE, ONE_TO_ONE, MANY_TO_MANY }

    private final String fieldName;
    private final String targetEntityType;
    private final Kind kind;
    private final String joinColumn;      // from @JoinColumn(name=...), null if absent
    private final String mappedBy;        // for bidirectional relations

    public RelationInfo(String fieldName, String targetEntityType, Kind kind,
                        String joinColumn, String mappedBy) {
        this.fieldName = fieldName;
        this.targetEntityType = targetEntityType;
        this.kind = kind;
        this.joinColumn = joinColumn;
        this.mappedBy = mappedBy;
    }

    public String getFieldName() { return fieldName; }
    public String getTargetEntityType() { return targetEntityType; }
    public Kind getKind() { return kind; }
    public String getJoinColumn() { return joinColumn; }
    public String getMappedBy() { return mappedBy; }
}
