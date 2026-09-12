package com.devmanchego.contextextractor.java.model;

/**
 * Supported database types for schema extraction.
 */
public enum DatabaseType {
    POSTGRESQL("postgresql", "org.postgresql.Driver", "PostgreSQL"),
    MYSQL("mysql", "com.mysql.cj.jdbc.Driver", "MySQL"),
    ORACLE("oracle", "oracle.jdbc.driver.OracleDriver", "Oracle");

    private final String urlKeyword;
    private final String driverClassName;
    private final String displayName;

    DatabaseType(String urlKeyword, String driverClassName, String displayName) {
        this.urlKeyword = urlKeyword;
        this.driverClassName = driverClassName;
        this.displayName = displayName;
    }

    public String getUrlKeyword() {
        return urlKeyword;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Detects database type from JDBC URL.
     *
     * @param jdbcUrl the JDBC connection URL
     * @return DatabaseType if detected, null if unknown
     */
    public static DatabaseType fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        String lowerUrl = jdbcUrl.toLowerCase();

        for (DatabaseType type : DatabaseType.values()) {
            if (lowerUrl.contains(type.urlKeyword)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Detects database type from driver class name.
     *
     * @param driverClass the JDBC driver class name
     * @return DatabaseType if detected, null if unknown
     */
    public static DatabaseType fromDriverClass(String driverClass) {
        if (driverClass == null) return null;
        String lowerDriver = driverClass.toLowerCase();

        for (DatabaseType type : DatabaseType.values()) {
            if (lowerDriver.contains(type.urlKeyword)) {
                return type;
            }
        }
        return null;
    }
}
