package com.devmanchego.contextextractor.java.model;

import java.util.List;

/**
 * Traceability mapping for a domain: UI ↔ DTO ↔ Entity ↔ Database.
 * Groups {@link TraceabilityRow} records by domain (e.g., "Orders", "Employees").
 */
public final class TraceabilityMapping {

    private final String domain;              // Domain/bounded context name
    private final String dtoSimpleName;       // DTO class name (e.g., "OrderCreateRequest")
    private final String entitySimpleName;    // Entity class name (e.g., "Order")
    private final List<TraceabilityRow> rows; // All traced fields for this DTO↔Entity pair

    public TraceabilityMapping(String domain, String dtoSimpleName, String entitySimpleName,
                              List<TraceabilityRow> rows) {
        this.domain = domain;
        this.dtoSimpleName = dtoSimpleName;
        this.entitySimpleName = entitySimpleName;
        this.rows = List.copyOf(rows);
    }

    public String getDomain() { return domain; }
    public String getDtoSimpleName() { return dtoSimpleName; }
    public String getEntitySimpleName() { return entitySimpleName; }
    public List<TraceabilityRow> getRows() { return rows; }

    /**
     * Title for this mapping suitable for markdown section heading.
     */
    public String getTitle() {
        return String.format("%s (%s → %s)", domain, dtoSimpleName, entitySimpleName);
    }
}
