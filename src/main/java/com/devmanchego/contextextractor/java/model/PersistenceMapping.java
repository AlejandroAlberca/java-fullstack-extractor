package com.devmanchego.contextextractor.java.model;

import java.util.List;

/** The complete DTO ↔ Entity mapping for one entity/DTO pair. */
public final class PersistenceMapping {

    private final DtoInfo dto;
    private final EntityInfo entity;
    private final List<MappingRow> rows;
    private final String mapperClass;   // FQN of the @Mapper class, null if none found

    public PersistenceMapping(DtoInfo dto, EntityInfo entity,
                              List<MappingRow> rows, String mapperClass) {
        this.dto = dto;
        this.entity = entity;
        this.rows = List.copyOf(rows);
        this.mapperClass = mapperClass;
    }

    public DtoInfo getDto() { return dto; }
    public EntityInfo getEntity() { return entity; }
    public List<MappingRow> getRows() { return rows; }
    public String getMapperClass() { return mapperClass; }
    public boolean hasMapper() { return mapperClass != null; }
}
