package com.devmanchego.contextextractor.java.model;

/**
 * Origin of a single extracted schema fact, so consumers (including an LLM)
 * can tell verified structure apart from best-effort inference.
 *
 * Precedence for structural facts (type, nullable, default, index): LIVE_DB > SQL_MIGRATION > JPA.
 * Semantic facts (business meaning, enum labels, validation-derived constraints) are always JPA.
 */
public enum DataProvenance {
    /** Read directly from the database catalog via JDBC (DatabaseMetaData / catalog views). */
    LIVE_DB,
    /** Parsed from a Flyway SQL migration file (static, no DB connection required). */
    SQL_MIGRATION,
    /** Parsed from JPA/Hibernate entity annotations and Bean Validation constraints. */
    JPA,
    /** Same fact confirmed by two or more independent sources. */
    MERGED
}
