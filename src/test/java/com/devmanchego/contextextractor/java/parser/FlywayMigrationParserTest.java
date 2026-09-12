package com.devmanchego.contextextractor.java.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationParserTest {

    @TempDir
    Path tempProjectDir;

    private Path migrationDir;

    @BeforeEach
    void setUp() throws Exception {
        migrationDir = tempProjectDir.resolve("src/main/resources/db/migration");
        Files.createDirectories(migrationDir);
    }

    @Test
    void testEmptyWhenNoMigrationsExist() {
        FlywayMigrationParser parser = new FlywayMigrationParser(tempProjectDir);
        com.devmanchego.contextextractor.java.model.RelationalSchema schema = parser.parse();
        assertTrue(schema.isEmpty());
    }

    @Test
    void testParsesOrdersAndOrderItemsFromMultipleMigrations() throws Exception {
        writeMigration("V1__init.sql", """
                CREATE TABLE users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(200) UNIQUE NOT NULL
                );

                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id),
                    total_amount NUMERIC(12,2) NOT NULL CHECK (total_amount > 0),
                    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                );

                CREATE INDEX orders_user_id_idx ON orders(user_id);
                CREATE INDEX orders_status_idx ON orders(status) WHERE status != 'DELIVERED';
                """);

        writeMigration("V2__order_items.sql", """
                CREATE TABLE order_items (
                    id BIGINT PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    product_id BIGINT NOT NULL,
                    quantity INTEGER NOT NULL CHECK (quantity > 0),
                    discount_percent NUMERIC(5,2) NOT NULL DEFAULT 0.00,
                    UNIQUE (order_id, product_id),
                    FOREIGN KEY (order_id) REFERENCES orders(id)
                );
                """);

        FlywayMigrationParser parser = new FlywayMigrationParser(tempProjectDir);
        com.devmanchego.contextextractor.java.model.RelationalSchema schema = parser.parse();

        assertEquals(3, schema.tables().size());
        assertEquals(List.of("V1__init.sql", "V2__order_items.sql"), schema.versionHistory());

        var orders = schema.tables().get("orders");
        assertNotNull(orders);
        assertEquals(List.of("id"), orders.primaryKeyColumns());
        assertEquals(1, orders.foreignKeys().size());
        assertEquals("users", orders.foreignKeys().get(0).toTable());
        assertEquals("user_id", orders.foreignKeys().get(0).fromColumn());

        var totalAmount = findColumn(orders, "total_amount");
        assertEquals("total_amount > 0", totalAmount.checkRaw());
        assertFalse(totalAmount.nullable());

        var status = findColumn(orders, "status");
        assertEquals("'PENDING'", status.defaultValue());

        var orderItems = schema.tables().get("order_items");
        assertEquals(1, orderItems.uniqueConstraints().size());
        assertEquals(List.of("order_id", "product_id"), orderItems.uniqueConstraints().get(0));
        assertEquals(1, orderItems.foreignKeys().size());
        assertEquals("orders", orderItems.foreignKeys().get(0).toTable());

        assertEquals(2, schema.indexes().size());
        var partialIndex = schema.indexes().stream()
                .filter(i -> i.name().equals("orders_status_idx"))
                .findFirst().orElseThrow();
        assertEquals("status != 'DELIVERED'", partialIndex.partialWhere());
    }

    @Test
    void testVersionOrderingWithDoubleDigits() throws Exception {
        writeMigration("V1__a.sql", "CREATE TABLE t1 (id BIGINT PRIMARY KEY);");
        writeMigration("V10__b.sql", "CREATE TABLE t10 (id BIGINT PRIMARY KEY);");
        writeMigration("V2__c.sql", "CREATE TABLE t2 (id BIGINT PRIMARY KEY);");

        FlywayMigrationParser parser = new FlywayMigrationParser(tempProjectDir);
        com.devmanchego.contextextractor.java.model.RelationalSchema schema = parser.parse();

        assertEquals(List.of("V1__a.sql", "V2__c.sql", "V10__b.sql"), schema.versionHistory());
    }

    private com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedColumn findColumn(
            com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedTable table, String name) {
        return table.columns().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Column not found: " + name));
    }

    private void writeMigration(String filename, String sql) throws Exception {
        Files.write(migrationDir.resolve(filename), sql.getBytes(StandardCharsets.UTF_8));
    }
}
