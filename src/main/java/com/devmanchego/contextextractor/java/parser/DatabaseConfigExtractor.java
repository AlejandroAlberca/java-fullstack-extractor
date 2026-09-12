package com.devmanchego.contextextractor.java.parser;

import com.devmanchego.contextextractor.java.model.DatabaseConnectionInfo;
import com.devmanchego.contextextractor.java.model.DatabaseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts database configuration from Spring Boot application.yml/properties
 * with support for CLI parameter overrides.
 *
 * Supports PostgreSQL, MySQL, and Oracle databases.
 *
 * Configuration priority (highest to lowest):
 * 1. CLI parameters (--db-url, --db-type, --db-user, --db-password)
 * 2. application-{profile}.yml/properties
 * 3. application.yml/properties
 */
public class DatabaseConfigExtractor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfigExtractor.class);

    private static final String DATASOURCE_URL_KEY = "spring.datasource.url";
    private static final String DATASOURCE_USERNAME_KEY = "spring.datasource.username";
    private static final String DATASOURCE_PASSWORD_KEY = "spring.datasource.password";
    private static final String DATASOURCE_DRIVER_CLASS_KEY = "spring.datasource.driver-class-name";
    private static final String JPA_DIALECT_KEY = "spring.jpa.properties.hibernate.dialect";

    private static final Pattern POSTGRESQL_URL = Pattern.compile(
        "jdbc:postgresql://([^:/]+)(?::([0-9]+))?(?:/([^?]*))?");
    private static final Pattern MYSQL_URL = Pattern.compile(
        "jdbc:mysql://([^:/]+)(?::([0-9]+))?(?:/([^?]*))?");
    private static final Pattern ORACLE_URL = Pattern.compile(
        "jdbc:oracle:thin:@(?:([^:/]+)(?::([0-9]+))?[:/]([^?]*))?");

    private final Path javaProjectPath;
    private final String activeProfile;

    private final String cliDbUrl;
    private final String cliDbType;
    private final String cliDbUser;
    private final String cliDbPassword;

    public DatabaseConfigExtractor(Path javaProjectPath) {
        this(javaProjectPath, null, null, null, null, null);
    }

    public DatabaseConfigExtractor(Path javaProjectPath,
                                   String activeProfile,
                                   String cliDbUrl,
                                   String cliDbType,
                                   String cliDbUser,
                                   String cliDbPassword) {
        this.javaProjectPath = javaProjectPath;
        this.activeProfile = activeProfile;
        this.cliDbUrl = cliDbUrl;
        this.cliDbType = cliDbType;
        this.cliDbUser = cliDbUser;
        this.cliDbPassword = cliDbPassword;
    }

    /**
     * Extracts database configuration, applying CLI overrides if provided.
     *
     * @return DatabaseConnectionInfo with complete configuration
     * @throws RuntimeException if no valid configuration is found
     */
    public DatabaseConnectionInfo extract() {
        Map<String, String> config = loadConfigurationFromFiles();
        applyCliOverrides(config);

        String jdbcUrl = config.get(DATASOURCE_URL_KEY);
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new RuntimeException("No database JDBC URL found. " +
                    "Specify via application.yml/properties or --db-url parameter");
        }

        DatabaseType dbType = detectDatabaseType(jdbcUrl, config.get(DATASOURCE_DRIVER_CLASS_KEY));
        if (dbType == null) {
            throw new RuntimeException("Could not determine database type from URL: " + jdbcUrl);
        }

        JdbcUrlComponents components = parseJdbcUrl(jdbcUrl, dbType);
        if (components == null) {
            throw new RuntimeException("Invalid JDBC URL format: " + jdbcUrl);
        }

        String username = config.get(DATASOURCE_USERNAME_KEY);
        String password = config.get(DATASOURCE_PASSWORD_KEY);
        String driverClass = config.getOrDefault(DATASOURCE_DRIVER_CLASS_KEY,
                dbType.getDriverClassName());

        log.info("Extracted database configuration: {} {}:{}/@{}",
                dbType.getDisplayName(), components.server, components.effectivePort(),
                components.databaseName);

        return DatabaseConnectionInfo.builder()
                .type(dbType)
                .server(components.server)
                .port(components.port)
                .databaseName(components.databaseName)
                .username(username)
                .password(password)
                .driverClassName(driverClass)
                .jdbcUrl(jdbcUrl)
                .build();
    }

    /**
     * Loads configuration from Spring Boot configuration files.
     * Reads application.yml/properties and application-{profile}.yml/properties.
     */
    private Map<String, String> loadConfigurationFromFiles() {
        Map<String, String> config = new HashMap<>();

        Path resourcesDir = javaProjectPath.resolve("src/main/resources");
        if (!Files.exists(resourcesDir)) {
            log.warn("resources directory not found at {}", resourcesDir);
            return config;
        }

        // Load application.yml
        loadPropertiesFromYaml(resourcesDir.resolve("application.yml"), config);

        // Load application.properties
        loadPropertiesFromProperties(resourcesDir.resolve("application.properties"), config);

        // Load profile-specific config if available
        if (activeProfile != null && !activeProfile.isEmpty()) {
            loadPropertiesFromYaml(
                    resourcesDir.resolve("application-" + activeProfile + ".yml"), config);
            loadPropertiesFromProperties(
                    resourcesDir.resolve("application-" + activeProfile + ".properties"), config);
        }

        return config;
    }

    /**
     * Loads properties from an application.yml file.
     * Supports nested property syntax (e.g., spring.datasource.url).
     */
    private void loadPropertiesFromYaml(Path yamlFile, Map<String, String> config) {
        if (!Files.exists(yamlFile)) {
            return;
        }

        try {
            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            parseYamlProperties(content, config);
            log.debug("Loaded properties from {}", yamlFile);
        } catch (IOException e) {
            log.warn("Could not read YAML file {}: {}", yamlFile, e.getMessage());
        }
    }

    /**
     * Parses YAML using SnakeYAML and extracts Spring datasource properties.
     */
    @SuppressWarnings("unchecked")
    private void parseYamlProperties(String yaml, Map<String, String> config) {
        try {
            Yaml yamlParser = new Yaml();
            Map<String, Object> data = yamlParser.load(yaml);
            if (data != null) {
                flattenMap("", data, config);
            }
        } catch (Exception e) {
            log.warn("Error parsing YAML: {}", e.getMessage());
        }
    }

    /**
     * Recursively flattens nested YAML map into dot-notation properties.
     */
    @SuppressWarnings("unchecked")
    private void flattenMap(String prefix, Map<String, Object> map, Map<String, String> config) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;

            if (value instanceof Map) {
                flattenMap(fullKey, (Map<String, Object>) value, config);
            } else if (value != null) {
                config.put(fullKey, value.toString());
            }
        }
    }

    /**
     * Loads properties from an application.properties file (key=value format).
     */
    private void loadPropertiesFromProperties(Path propertiesFile, Map<String, String> config) {
        if (!Files.exists(propertiesFile)) {
            return;
        }

        try {
            Files.readAllLines(propertiesFile, StandardCharsets.UTF_8)
                    .stream()
                    .map(line -> line.replaceAll("#.*$", "").trim())
                    .filter(line -> !line.isEmpty() && line.contains("="))
                    .forEach(line -> {
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            config.put(parts[0].trim(), parts[1].trim());
                        }
                    });
            log.debug("Loaded properties from {}", propertiesFile);
        } catch (IOException e) {
            log.warn("Could not read properties file {}: {}", propertiesFile, e.getMessage());
        }
    }

    /**
     * Gets the indentation level of a YAML line (each 2 spaces = 1 level).
     */
    private int getIndentation(String line) {
        int indent = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') indent++;
            else break;
        }
        return indent / 2;
    }


    /**
     * Applies CLI parameter overrides to the configuration.
     */
    private void applyCliOverrides(Map<String, String> config) {
        if (cliDbUrl != null && !cliDbUrl.isEmpty()) {
            config.put(DATASOURCE_URL_KEY, cliDbUrl);
        }
        if (cliDbType != null && !cliDbType.isEmpty()) {
            config.put(DATASOURCE_DRIVER_CLASS_KEY, cliDbType);
        }
        if (cliDbUser != null && !cliDbUser.isEmpty()) {
            config.put(DATASOURCE_USERNAME_KEY, cliDbUser);
        }
        if (cliDbPassword != null && !cliDbPassword.isEmpty()) {
            config.put(DATASOURCE_PASSWORD_KEY, cliDbPassword);
        }
    }

    /**
     * Detects the database type from JDBC URL and driver class.
     */
    private DatabaseType detectDatabaseType(String jdbcUrl, String driverClass) {
        DatabaseType fromUrl = DatabaseType.fromJdbcUrl(jdbcUrl);
        if (fromUrl != null) {
            return fromUrl;
        }

        if (driverClass != null) {
            return DatabaseType.fromDriverClass(driverClass);
        }

        return null;
    }

    /**
     * Parses JDBC URL and extracts connection components.
     */
    private JdbcUrlComponents parseJdbcUrl(String jdbcUrl, DatabaseType dbType) {
        return switch (dbType) {
            case POSTGRESQL -> parsePostgresqlUrl(jdbcUrl);
            case MYSQL -> parseMysqlUrl(jdbcUrl);
            case ORACLE -> parseOracleUrl(jdbcUrl);
        };
    }

    private JdbcUrlComponents parsePostgresqlUrl(String jdbcUrl) {
        Matcher matcher = POSTGRESQL_URL.matcher(jdbcUrl);
        if (matcher.find()) {
            String server = matcher.group(1);
            Integer port = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : null;
            String database = matcher.group(3) != null ? matcher.group(3) : "postgres";
            return new JdbcUrlComponents(server, port, database);
        }
        return null;
    }

    private JdbcUrlComponents parseMysqlUrl(String jdbcUrl) {
        Matcher matcher = MYSQL_URL.matcher(jdbcUrl);
        if (matcher.find()) {
            String server = matcher.group(1);
            Integer port = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : null;
            String database = matcher.group(3) != null ? matcher.group(3) : "mysql";
            return new JdbcUrlComponents(server, port, database);
        }
        return null;
    }

    private JdbcUrlComponents parseOracleUrl(String jdbcUrl) {
        Matcher matcher = ORACLE_URL.matcher(jdbcUrl);
        if (matcher.find()) {
            String server = matcher.group(1);
            Integer port = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : null;
            String database = matcher.group(3) != null ? matcher.group(3) : "ORCL";
            return new JdbcUrlComponents(server, port, database);
        }
        return null;
    }

    /**
     * Internal class to hold parsed JDBC URL components.
     */
    private static class JdbcUrlComponents {
        final String server;
        final Integer port;
        final String databaseName;

        JdbcUrlComponents(String server, Integer port, String databaseName) {
            this.server = server;
            this.port = port;
            this.databaseName = databaseName;
        }

        int effectivePort() {
            return port != null ? port : 5432; // Fallback to PostgreSQL default
        }
    }
}
