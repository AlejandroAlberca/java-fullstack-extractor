package com.devmanchego.contextextractor.java.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Complete database connection information extracted from Spring Boot
 * configuration files or command-line parameters.
 *
 * Supports PostgreSQL, MySQL, and Oracle databases.
 */
public final class DatabaseConnectionInfo {

    private final DatabaseType type;
    private final String server;
    private final Integer port;
    private final String databaseName;
    private final String username;
    private final String password;
    private final String driverClassName;
    private final String jdbcUrl;

    private DatabaseConnectionInfo(Builder builder) {
        this.type = builder.type;
        this.server = builder.server;
        this.port = builder.port;
        this.databaseName = builder.databaseName;
        this.username = builder.username;
        this.password = builder.password;
        this.driverClassName = builder.driverClassName;
        this.jdbcUrl = builder.jdbcUrl;
    }

    public DatabaseType getType() {
        return type;
    }

    public String getServer() {
        return server;
    }

    public Optional<Integer> getPort() {
        return Optional.ofNullable(port);
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public Optional<String> getUsername() {
        return Optional.ofNullable(username);
    }

    public Optional<String> getPassword() {
        return Optional.ofNullable(password);
    }

    public Optional<String> getDriverClassName() {
        return Optional.ofNullable(driverClassName);
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * Returns the effective port based on database type default if not specified.
     */
    public int getEffectivePort() {
        if (port != null) {
            return port;
        }
        return switch (type) {
            case POSTGRESQL -> 5432;
            case MYSQL -> 3306;
            case ORACLE -> 1521;
        };
    }

    @Override
    public String toString() {
        return "DatabaseConnectionInfo{" +
                "type=" + type.getDisplayName() +
                ", server='" + server + '\'' +
                ", port=" + getEffectivePort() +
                ", database='" + databaseName + '\'' +
                ", username='" + (username != null ? "***" : "null") + '\'' +
                ", driver='" + driverClassName + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatabaseConnectionInfo that = (DatabaseConnectionInfo) o;
        return type == that.type &&
                Objects.equals(server, that.server) &&
                Objects.equals(port, that.port) &&
                Objects.equals(databaseName, that.databaseName) &&
                Objects.equals(username, that.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, server, port, databaseName, username);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private DatabaseType type;
        private String server;
        private Integer port;
        private String databaseName;
        private String username;
        private String password;
        private String driverClassName;
        private String jdbcUrl;

        public Builder type(DatabaseType type) {
            this.type = type;
            return this;
        }

        public Builder server(String server) {
            this.server = server;
            return this;
        }

        public Builder port(Integer port) {
            this.port = port;
            return this;
        }

        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder driverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
            return this;
        }

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public DatabaseConnectionInfo build() {
            if (type == null) {
                throw new IllegalStateException("Database type must be set");
            }
            if (server == null) {
                throw new IllegalStateException("Server must be set");
            }
            if (databaseName == null) {
                throw new IllegalStateException("Database name must be set");
            }
            if (jdbcUrl == null) {
                throw new IllegalStateException("JDBC URL must be set");
            }

            return new DatabaseConnectionInfo(this);
        }
    }
}
