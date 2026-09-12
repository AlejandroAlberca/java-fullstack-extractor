package com.devmanchego.contextextractor.java.parser;

import com.devmanchego.contextextractor.java.model.DatabaseConnectionInfo;
import com.devmanchego.contextextractor.java.model.DatabaseType;
import com.devmanchego.contextextractor.java.model.RelationalSchema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveSchemaIntrospectorTest {

    @Test
    void testSuccessfulIntrospectionAgainstRealDatabase() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:introspector_test;DB_CLOSE_DELAY=-1";

        try (Connection setupConn = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement stmt = setupConn.createStatement()) {
            stmt.execute("CREATE TABLE departments (" +
                    "id BIGINT PRIMARY KEY, " +
                    "name VARCHAR(100) UNIQUE NOT NULL)");
            stmt.execute("CREATE TABLE employees (" +
                    "id BIGINT PRIMARY KEY, " +
                    "first_name VARCHAR(100) NOT NULL, " +
                    "salary NUMERIC(12,2) NOT NULL, " +
                    "department_id BIGINT, " +
                    "CONSTRAINT fk_dept FOREIGN KEY (department_id) REFERENCES departments(id))");
            stmt.execute("CREATE INDEX idx_emp_dept ON employees(department_id)");

            DatabaseConnectionInfo connInfo = DatabaseConnectionInfo.builder()
                    .type(DatabaseType.POSTGRESQL) // type only affects schema-pattern/partial-index logic
                    .server("mem")
                    .databaseName("introspector_test")
                    .username("sa")
                    .password("")
                    .jdbcUrl(jdbcUrl)
                    .build();

            LiveSchemaIntrospector introspector = new LiveSchemaIntrospector();
            LiveSchemaIntrospector.Result result = introspector.introspect(connInfo);

            assertTrue(result.succeeded(), "Introspection should succeed: " + result.warning());
            RelationalSchema schema = result.schema();

            assertTrue(schema.tables().containsKey("EMPLOYEES"));
            assertTrue(schema.tables().containsKey("DEPARTMENTS"));

            var employees = schema.tables().get("EMPLOYEES");
            assertEquals(List.of("ID"), employees.primaryKeyColumns());
            assertEquals(1, employees.foreignKeys().size());
            assertEquals("DEPARTMENTS", employees.foreignKeys().get(0).toTable());

            var salary = employees.columns().stream()
                    .filter(c -> c.name().equals("SALARY")).findFirst().orElseThrow();
            assertFalse(salary.nullable());

            var departments = schema.tables().get("DEPARTMENTS");
            var deptName = departments.columns().stream()
                    .filter(c -> c.name().equals("NAME")).findFirst().orElseThrow();
            assertTrue(deptName.unique());
        }
    }

    @Test
    void testGracefulFallbackWhenDatabaseUnreachable() {
        DatabaseConnectionInfo connInfo = DatabaseConnectionInfo.builder()
                .type(DatabaseType.POSTGRESQL)
                .server("nonexistent-host-xyz")
                .port(5432)
                .databaseName("nope")
                .username("nobody")
                .password("wrong")
                .jdbcUrl("jdbc:postgresql://nonexistent-host-xyz:5432/nope")
                .build();

        LiveSchemaIntrospector introspector = new LiveSchemaIntrospector();

        assertDoesNotThrow(() -> {
            LiveSchemaIntrospector.Result result = introspector.introspect(connInfo);
            assertFalse(result.succeeded());
            assertNotNull(result.warning());
            assertTrue(result.schema().isEmpty());
        });
    }
}
