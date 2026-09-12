package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.model.*;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedColumn;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedForeignKey;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedIndex;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedTable;
import com.devmanchego.contextextractor.java.schema.SchemaBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSchemaRendererTest {

    @Test
    void testRendersOrdersDataModelWithProvenanceTags() {
        ParsedColumn.Builder id = new ParsedColumn.Builder();
        id.name = "id"; id.sqlType = "BIGINT"; id.nullable = false; id.primaryKey = true;
        ParsedColumn.Builder totalAmount = new ParsedColumn.Builder();
        totalAmount.name = "total_amount"; totalAmount.sqlType = "NUMERIC(12,2)";
        totalAmount.nullable = false; totalAmount.checkRaw = "total_amount > 0";
        ParsedColumn.Builder status = new ParsedColumn.Builder();
        status.name = "status"; status.sqlType = "VARCHAR(20)"; status.nullable = false;
        status.defaultValue = "'PENDING'";

        ParsedTable ordersTable = new ParsedTable("orders",
                List.of(id.build(), totalAmount.build(), status.build()),
                List.of("id"),
                List.of(new ParsedForeignKey("orders", "user_id", "users", "id", "fk_orders_user")),
                List.of(), List.of());

        RelationalSchema sqlSchema = new RelationalSchema(
                Map.of("orders", ordersTable),
                List.of(new ParsedIndex("orders_status_idx", "orders", List.of("status"), false,
                        "status != 'DELIVERED'")),
                List.of("V1__init.sql"));

        FieldInfo totalAmountField = FieldInfo.builder()
                .name("totalAmount").type("BigDecimal").columnName("total_amount")
                .description("Sum of line items + tax + shipping.")
                .addValidationRule("@Positive")
                .build();
        FieldInfo statusField = FieldInfo.builder()
                .name("status").type("OrderStatus").columnName("status")
                .addValidationRule("@Enumerated")
                .enumValues(List.of("PENDING", "SHIPPED", "DELIVERED", "CANCELLED"))
                .build();
        EntityInfo orderEntity = new EntityInfo("com.example.Order", "Order", "orders",
                "Order.java", List.of(totalAmountField, statusField), List.of(), List.of(),
                "Customer purchase orders.");

        DatabaseSchema schema = new SchemaBuilder().build(List.of(orderEntity), List.of(),
                RelationalSchema.empty(), sqlSchema, List.of());

        String markdown = new DatabaseSchemaRenderer().render(schema);

        assertTrue(markdown.contains("# Data Model"));
        assertTrue(markdown.contains("## Global Relation Graph (Cardinality)"));
        assertTrue(markdown.contains("`orders` (N:1) → `users`"));
        assertTrue(markdown.contains("### Table: `orders`"));
        assertTrue(markdown.contains("Customer purchase orders."));
        assertTrue(markdown.contains("Sum of line items + tax + shipping."));
        assertTrue(markdown.contains("total_amount > 0"));
        assertTrue(markdown.contains("Lifecycle: `PENDING`, `SHIPPED`, `DELIVERED`, `CANCELLED`"));
        assertTrue(markdown.contains("Must be positive (> 0)."));
        assertTrue(markdown.contains("orders_status_idx"));
        assertTrue(markdown.contains("status != 'DELIVERED'"));
        assertTrue(markdown.contains("`sql`"));
        assertTrue(markdown.contains("`jpa`"));

        // Contiguous numbering, no gaps.
        assertTrue(markdown.contains("| 1 | `id`"));
        assertTrue(markdown.contains("| 2 | `total_amount`"));
        assertTrue(markdown.contains("| 3 | `status`"));

        // No operational/level-C content should ever appear.
        assertFalse(markdown.contains("Growth Rate"));
        assertFalse(markdown.contains("Performance Note"));
    }

    @Test
    void testRendersInheritedPrimaryKeyWithOrigin() {
        FieldInfo idField = FieldInfo.builder().name("id").type("Long")
                .columnName("ACHETEUR_ID").primaryKey(true).keyOrigin("AbstractEntity").build();
        FieldInfo codeField = FieldInfo.builder().name("code").type("String")
                .columnName("ACHETEUR_CODE").build();
        EntityInfo entity = new EntityInfo("com.example.AcheteurEntity", "AcheteurEntity", "ACHETEUR",
                "AcheteurEntity.java", List.of(idField, codeField), List.of(), List.of());

        DatabaseSchema schema = new SchemaBuilder().build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        String markdown = new DatabaseSchemaRenderer().render(schema);

        assertTrue(markdown.contains("ACHETEUR_ID"));
        assertTrue(markdown.contains("inherited from `AbstractEntity`"));
        // The inherited key column must appear in the physical column list, not as derived.
        assertTrue(markdown.contains("| 1 | `ACHETEUR_ID`"));
    }

    @Test
    void testDerivedFieldBetweenPhysicalColumns_leavesNoGapInDisplayedOrdinals() {
        FieldInfo idField = FieldInfo.builder().name("id").type("Long").primaryKey(true).build();
        FieldInfo formulaField = FieldInfo.builder().name("delai").type("Integer").formula(true).build();
        FieldInfo dateField = FieldInfo.builder().name("jalonDate").type("LocalDateTime").build();
        EntityInfo entity = new EntityInfo("com.example.MaturiteJalon", "MaturiteJalon", "maturite_jalon",
                "MaturiteJalon.java", List.of(idField, formulaField, dateField), List.of(), List.of());

        DatabaseSchema schema = new SchemaBuilder().build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        String markdown = new DatabaseSchemaRenderer().render(schema);

        // The physical-columns table shows only id and jalon_date — 1 and 2, no gap for the
        // formula field that sits between them and renders in its own Derived Fields table.
        assertTrue(markdown.contains("| 1 | `id`"));
        assertTrue(markdown.contains("| 2 | `jalon_date`"));
        assertFalse(markdown.contains("| 3 |"), "No third row should exist in the physical columns table");
        assertTrue(markdown.contains("`delai`"), "The formula field must still appear, in Derived Fields");
    }

    @Test
    void testRendersEmptySchemaWithHonestWarning() {
        DatabaseSchema schema = new DatabaseSchema(Map.of(), List.of(), java.util.Set.of(),
                List.of("No JPA entities found."));
        String markdown = new DatabaseSchemaRenderer().render(schema);
        assertTrue(markdown.contains("No data model could be extracted"));
        assertTrue(markdown.contains("No JPA entities found."));
    }
}
