package com.devmanchego.contextextractor.java.parser;

import com.devmanchego.contextextractor.java.model.DatabaseConnectionInfo;
import com.devmanchego.contextextractor.java.model.DatabaseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigExtractorTest {

    @TempDir
    Path tempProjectDir;

    private Path resourcesDir;

    @BeforeEach
    void setUp() throws Exception {
        resourcesDir = tempProjectDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);
    }

    @Test
    void testPostgresqlConfigFromYaml() throws Exception {
        String yaml = """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/hrapp
                    username: postgres
                    password: secret123
                    driver-class-name: org.postgresql.Driver
                  jpa:
                    hibernate:
                      dialect: org.hibernate.dialect.PostgreSQLDialect
                """;
        Files.write(resourcesDir.resolve("application.yml"), yaml.getBytes(StandardCharsets.UTF_8));

        DatabaseConfigExtractor extractor = new DatabaseConfigExtractor(tempProjectDir);
        DatabaseConnectionInfo config = extractor.extract();

        assertEquals(DatabaseType.POSTGRESQL, config.getType());
        assertEquals("localhost", config.getServer());
        assertEquals(5432, config.getEffectivePort());
        assertEquals("hrapp", config.getDatabaseName());
        assertEquals("postgres", config.getUsername().orElse(null));
        assertEquals("secret123", config.getPassword().orElse(null));
    }

    @Test
    void testMysqlConfigFromProperties() throws Exception {
        String properties = """
                spring.datasource.url=jdbc:mysql://db.example.com:3306/myapp
                spring.datasource.username=root
                spring.datasource.password=password123
                spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
                """;
        Files.write(resourcesDir.resolve("application.properties"),
                properties.getBytes(StandardCharsets.UTF_8));

        DatabaseConfigExtractor extractor = new DatabaseConfigExtractor(tempProjectDir);
        DatabaseConnectionInfo config = extractor.extract();

        assertEquals(DatabaseType.MYSQL, config.getType());
        assertEquals("db.example.com", config.getServer());
        assertEquals(3306, config.getEffectivePort());
        assertEquals("myapp", config.getDatabaseName());
        assertEquals("root", config.getUsername().orElse(null));
    }

    @Test
    void testOracleConfigDetection() throws Exception {
        String yaml = """
                spring:
                  datasource:
                    url: jdbc:oracle:thin:@oracle.server.com:1521:ORCL
                    username: scott
                    password: tiger
                """;
        Files.write(resourcesDir.resolve("application.yml"), yaml.getBytes(StandardCharsets.UTF_8));

        DatabaseConfigExtractor extractor = new DatabaseConfigExtractor(tempProjectDir);
        DatabaseConnectionInfo config = extractor.extract();

        assertEquals(DatabaseType.ORACLE, config.getType());
        assertEquals("oracle.server.com", config.getServer());
        assertEquals(1521, config.getEffectivePort());
        assertEquals("ORCL", config.getDatabaseName());
    }

    @Test
    void testCliParameterOverride() throws Exception {
        String yaml = """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/old_db
                    username: old_user
                """;
        Files.write(resourcesDir.resolve("application.yml"), yaml.getBytes(StandardCharsets.UTF_8));

        // Override via CLI parameters
        DatabaseConfigExtractor extractor = new DatabaseConfigExtractor(
                tempProjectDir,
                null,
                "jdbc:mysql://prod.db.com:3306/new_app",
                null,
                "prod_user",
                "prod_pass"
        );
        DatabaseConnectionInfo config = extractor.extract();

        assertEquals(DatabaseType.MYSQL, config.getType());
        assertEquals("prod.db.com", config.getServer());
        assertEquals("new_app", config.getDatabaseName());
        assertEquals("prod_user", config.getUsername().orElse(null));
        assertEquals("prod_pass", config.getPassword().orElse(null));
    }

    @Test
    void testProfileSpecificConfig() throws Exception {
        // Base config
        String baseYaml = """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/dev_db
                    username: dev_user
                """;
        Files.write(resourcesDir.resolve("application.yml"), baseYaml.getBytes(StandardCharsets.UTF_8));

        // Production profile override
        String prodYaml = """
                spring:
                  datasource:
                    url: jdbc:postgresql://prod.db.internal:5432/prod_db
                    username: prod_user
                    password: prod_secret
                """;
        Files.write(resourcesDir.resolve("application-prod.yml"), prodYaml.getBytes(StandardCharsets.UTF_8));

        // Load with prod profile
        DatabaseConfigExtractor extractor = new DatabaseConfigExtractor(tempProjectDir, "prod", null, null, null, null);
        DatabaseConnectionInfo config = extractor.extract();

        assertEquals("prod.db.internal", config.getServer());
        assertEquals("prod_db", config.getDatabaseName());
        assertEquals("prod_user", config.getUsername().orElse(null));
    }

    @Test
    void testMissingConfigurationThrowsException() throws Exception {
        // No configuration files created
        DatabaseConfigExtractor extractor = new DatabaseConfigExtractor(tempProjectDir);
        assertThrows(RuntimeException.class, extractor::extract);
    }

    @Test
    void testJdbcUrlValidation() {
        // Test various JDBC URL formats
        testJdbcUrlParsing("jdbc:postgresql://localhost:5432/hrapp",
                DatabaseType.POSTGRESQL, "localhost", 5432, "hrapp");

        testJdbcUrlParsing("jdbc:postgresql://localhost/hrapp",
                DatabaseType.POSTGRESQL, "localhost", null, "hrapp");

        testJdbcUrlParsing("jdbc:mysql://db.example.com:3306/myapp",
                DatabaseType.MYSQL, "db.example.com", 3306, "myapp");

        testJdbcUrlParsing("jdbc:oracle:thin:@oracle.server:1521:ORCL",
                DatabaseType.ORACLE, "oracle.server", 1521, "ORCL");
    }

    private void testJdbcUrlParsing(String jdbcUrl, DatabaseType expectedType,
                                    String expectedServer, Integer expectedPort, String expectedDb) {
        try {
            String yaml = String.format("""
                    spring:
                      datasource:
                        url: %s
                        username: testuser
                    """, jdbcUrl);
            Files.write(resourcesDir.resolve("application.yml"), yaml.getBytes(StandardCharsets.UTF_8));

            DatabaseConfigExtractor extractor = new DatabaseConfigExtractor(tempProjectDir);
            DatabaseConnectionInfo config = extractor.extract();

            assertEquals(expectedType, config.getType());
            assertEquals(expectedServer, config.getServer());
            assertEquals(expectedDb, config.getDatabaseName());
            if (expectedPort != null) {
                assertEquals(expectedPort, config.getPort().orElse(null));
            }
        } catch (Exception e) {
            fail("Failed to parse JDBC URL: " + jdbcUrl, e);
        }
    }

    @Test
    void testDatabaseTypeDetection() {
        assertEquals(DatabaseType.POSTGRESQL, DatabaseType.fromJdbcUrl("jdbc:postgresql://localhost/db"));
        assertEquals(DatabaseType.MYSQL, DatabaseType.fromJdbcUrl("jdbc:mysql://localhost/db"));
        assertEquals(DatabaseType.ORACLE, DatabaseType.fromJdbcUrl("jdbc:oracle:thin:@localhost:1521:db"));

        assertEquals(DatabaseType.POSTGRESQL, DatabaseType.fromDriverClass("org.postgresql.Driver"));
        assertEquals(DatabaseType.MYSQL, DatabaseType.fromDriverClass("com.mysql.cj.jdbc.Driver"));
        assertEquals(DatabaseType.ORACLE, DatabaseType.fromDriverClass("oracle.jdbc.driver.OracleDriver"));
    }

    @Test
    void testConnectionInfoToString() throws Exception {
        String yaml = """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/hrapp
                    username: postgres
                """;
        Files.write(resourcesDir.resolve("application.yml"), yaml.getBytes(StandardCharsets.UTF_8));

        DatabaseConfigExtractor extractor = new DatabaseConfigExtractor(tempProjectDir);
        DatabaseConnectionInfo config = extractor.extract();

        String str = config.toString();
        assertTrue(str.contains("PostgreSQL"));
        assertTrue(str.contains("localhost"));
        assertTrue(str.contains("hrapp"));
    }
}
