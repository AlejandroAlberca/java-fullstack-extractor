package com.devmanchego.contextextractor.java.schema;

import com.devmanchego.contextextractor.java.model.*;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedColumn;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedForeignKey;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedIndex;
import com.devmanchego.contextextractor.java.model.RelationalSchema.ParsedTable;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaBuilderTest {

    @Test
    void testMergesStructuralAndJpaWithCorrectProvenance() {
        // Structural (SQL migration): orders table with a CHECK constraint and a FK to users.
        ParsedColumn.Builder totalAmount = new ParsedColumn.Builder();
        totalAmount.name = "total_amount";
        totalAmount.sqlType = "NUMERIC(12,2)";
        totalAmount.nullable = false;
        totalAmount.checkRaw = "total_amount > 0";

        ParsedColumn.Builder id = new ParsedColumn.Builder();
        id.name = "id";
        id.sqlType = "BIGINT";
        id.nullable = false;
        id.primaryKey = true;

        ParsedTable ordersTable = new ParsedTable("orders",
                List.of(id.build(), totalAmount.build()),
                List.of("id"),
                List.of(new ParsedForeignKey("orders", "user_id", "users", "id", "fk_orders_user")),
                List.of(), List.of());

        RelationalSchema sqlSchema = new RelationalSchema(
                Map.of("orders", ordersTable),
                List.of(new ParsedIndex("orders_status_idx", "orders", List.of("status"), false,
                        "status != 'DELIVERED'")),
                List.of("V1__init.sql"));

        // JPA: Order entity adds business meaning + validation-derived constraint on total_amount.
        FieldInfo totalAmountField = FieldInfo.builder()
                .name("totalAmount").type("BigDecimal").columnName("total_amount")
                .description("Sum of line items + tax + shipping.")
                .addValidationRule("@Positive")
                .build();
        EntityInfo orderEntity = new EntityInfo("com.example.Order", "Order", "orders",
                "Order.java", List.of(totalAmountField), List.of(), List.of(),
                "Customer purchase orders.");

        SchemaBuilder builder = new SchemaBuilder();
        DatabaseSchema schema = builder.build(List.of(orderEntity), List.of(), RelationalSchema.empty(),
                sqlSchema, List.of());

        SchemaTable orders = schema.getTablesByName().get("orders");
        assertNotNull(orders);
        assertEquals("Customer purchase orders.", orders.getDescription());
        assertEquals(DataProvenance.SQL_MIGRATION, orders.getSource());

        SchemaColumn totalAmountCol = orders.getColumns().stream()
                .filter(c -> c.getName().equals("total_amount")).findFirst().orElseThrow();
        assertEquals(DataProvenance.SQL_MIGRATION, totalAmountCol.getStructuralSource());
        assertEquals("total_amount > 0", totalAmountCol.getCheckConstraintRaw());
        assertEquals("Sum of line items + tax + shipping.", totalAmountCol.getBusinessMeaning());
        assertTrue(totalAmountCol.getCheckConstraintProse().contains("Must be positive (> 0)."));

        // Contiguous ordinal positions regardless of source ordering.
        assertEquals(1, orders.getColumns().get(0).getOrdinalPosition());
        assertEquals(2, orders.getColumns().get(1).getOrdinalPosition());

        // Relationship recovered from SQL.
        assertEquals(1, schema.getRelationships().size());
        SchemaRelationship rel = schema.getRelationships().get(0);
        assertEquals("orders", rel.getFromTable());
        assertEquals("users", rel.getToTable());
        assertEquals(SchemaRelationship.CardinalitySource.INFERRED_SQL, rel.getCardinalitySource());

        assertTrue(schema.getWarnings().isEmpty());
    }

    @Test
    void testFallsBackToJpaOnlyWithWarningWhenNoStructuralSource() {
        FieldInfo idField = FieldInfo.builder().name("id").type("Long").primaryKey(true).build();
        FieldInfo nameField = FieldInfo.builder().name("name").type("String").nullable(false).build();
        EntityInfo entity = new EntityInfo("com.example.Department", "Department", "departments",
                "Department.java", List.of(idField, nameField), List.of(), List.of());

        SchemaBuilder builder = new SchemaBuilder();
        DatabaseSchema schema = builder.build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        assertFalse(schema.getWarnings().isEmpty());
        assertTrue(schema.getWarnings().get(0).contains("No live database connection"));

        SchemaTable departments = schema.getTablesByName().get("departments");
        assertNotNull(departments);
        assertEquals(DataProvenance.JPA, departments.getSource());
        assertEquals(2, departments.getColumns().size());
    }

    @Test
    void testResolvesEnumConstantsForEnumeratedField() throws Exception {
        CompilationUnit statusEnumCu = StaticJavaParser.parse(
                "package com.example; public enum OrderStatus { PENDING, SHIPPED, DELIVERED, CANCELLED }");

        FieldInfo statusField = FieldInfo.builder()
                .name("status").type("OrderStatus").columnName("status")
                .addValidationRule("@Enumerated")
                .build();
        EntityInfo entity = new EntityInfo("com.example.Order", "Order", "orders",
                "Order.java", List.of(statusField), List.of(), List.of());

        SchemaBuilder builder = new SchemaBuilder();
        DatabaseSchema schema = builder.build(List.of(entity), List.of(statusEnumCu),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        SchemaColumn statusCol = schema.getTablesByName().get("orders").getColumns().get(0);
        assertTrue(statusCol.isEnum());
        assertEquals(List.of("PENDING", "SHIPPED", "DELIVERED", "CANCELLED"), statusCol.getEnumValues());
    }

    // -----------------------------------------------------------------------
    // P3: JPA-only table with an inherited/composite key must still get a PK,
    // and no persistent field is silently dropped from the column list.
    // -----------------------------------------------------------------------

    @Test
    void jpaOnlyTable_inheritedPrimaryKey_appearsAsColumnWithKeyOrigin() {
        FieldInfo idField = FieldInfo.builder().name("id").type("Long")
                .columnName("ACHETEUR_ID").primaryKey(true).keyOrigin("AbstractEntity").build();
        FieldInfo codeField = FieldInfo.builder().name("code").type("String")
                .columnName("ACHETEUR_CODE").nullable(false).build();
        EntityInfo entity = new EntityInfo("com.example.AcheteurEntity", "AcheteurEntity", "ACHETEUR",
                "AcheteurEntity.java", List.of(idField, codeField), List.of(), List.of());

        SchemaBuilder builder = new SchemaBuilder();
        DatabaseSchema schema = builder.build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        SchemaTable table = schema.getTablesByName().get("ACHETEUR");
        assertNotNull(table);
        // Every declared field produced a column — none silently dropped.
        assertEquals(2, table.getColumns().size());
        // Ordinals are contiguous starting at 1.
        assertEquals(1, table.getColumns().get(0).getOrdinalPosition());
        assertEquals(2, table.getColumns().get(1).getOrdinalPosition());

        SchemaColumn idCol = table.getColumns().stream()
                .filter(c -> "ACHETEUR_ID".equals(c.getName())).findFirst().orElseThrow();
        assertTrue(idCol.isPrimaryKey());
        assertFalse(idCol.isDerived(), "An inherited primary key is a real column, not a derived one");
        assertEquals("AbstractEntity", idCol.getKeyOrigin());
    }

    @Test
    void jpaOnlyTable_fieldWithNoExplicitColumnAnnotation_isNotDerived() {
        // No @Column at all — JPA's default naming strategy still maps this to a real column.
        FieldInfo nameField = FieldInfo.builder().name("name").type("String").build();
        EntityInfo entity = new EntityInfo("com.example.Widget", "Widget", "widget",
                "Widget.java", List.of(nameField), List.of(), List.of());

        SchemaBuilder builder = new SchemaBuilder();
        DatabaseSchema schema = builder.build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        SchemaColumn col = schema.getTablesByName().get("widget").getColumns().get(0);
        assertFalse(col.isDerived(), "A field lacking @Column must still be a real column, not derived");
        assertEquals("name", col.getName(), "Falls back to the field's own (snake_case) name");
    }

    @Test
    void jpaOnlyTable_transientField_shownAsDerived_notDropped() {
        FieldInfo idField = FieldInfo.builder().name("id").type("Long").primaryKey(true).build();
        FieldInfo tempField = FieldInfo.builder().name("tempNote").type("String").transientField(true).build();
        EntityInfo entity = new EntityInfo("com.example.Widget", "Widget", "widget",
                "Widget.java", List.of(idField, tempField), List.of(), List.of());

        SchemaBuilder builder = new SchemaBuilder();
        DatabaseSchema schema = builder.build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        SchemaTable table = schema.getTablesByName().get("widget");
        SchemaColumn tempCol = table.getColumns().stream()
                .filter(c -> "temp_note".equals(c.getName())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "@Transient field must still appear (as derived), not vanish: " + table.getColumns()));
        assertTrue(tempCol.isDerived());
    }

    @Test
    void jpaOnlyTable_formulaField_shownAsDerived() {
        FieldInfo idField = FieldInfo.builder().name("id").type("Long").primaryKey(true).build();
        FieldInfo delaiField = FieldInfo.builder().name("delai").type("Integer").formula(true).build();
        EntityInfo entity = new EntityInfo("com.example.MaturiteJalon", "MaturiteJalon", "maturite_jalon",
                "MaturiteJalon.java", List.of(idField, delaiField), List.of(), List.of());

        SchemaBuilder builder = new SchemaBuilder();
        DatabaseSchema schema = builder.build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        SchemaColumn delaiCol = schema.getTablesByName().get("maturite_jalon").getColumns().stream()
                .filter(c -> "delai".equals(c.getName())).findFirst().orElseThrow();
        assertTrue(delaiCol.isDerived());
    }

    @Test
    void jpaOnlyTable_formulaWithExpression_derivationLogicReportsTheExpressionItself() {
        FieldInfo idField = FieldInfo.builder().name("id").type("Long").primaryKey(true).build();
        FieldInfo delaiField = FieldInfo.builder().name("delai").type("Integer")
                .formula(true).formulaExpression("date_part('day', now() - jalon_date)").build();
        EntityInfo entity = new EntityInfo("com.example.MaturiteJalon", "MaturiteJalon", "maturite_jalon",
                "MaturiteJalon.java", List.of(idField, delaiField), List.of(), List.of());

        DatabaseSchema schema = new SchemaBuilder().build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        SchemaColumn delaiCol = schema.getTablesByName().get("maturite_jalon").getColumns().stream()
                .filter(c -> "delai".equals(c.getName())).findFirst().orElseThrow();
        assertTrue(delaiCol.getDerivationLogic().contains("date_part('day', now() - jalon_date)"),
                "Derivation logic must report the actual expression, not a generic label: "
                        + delaiCol.getDerivationLogic());
    }

    @Test
    void jpaOnlyTable_transientField_derivationLogicDistinctFromFormula() {
        FieldInfo idField = FieldInfo.builder().name("id").type("Long").primaryKey(true).build();
        FieldInfo tempField = FieldInfo.builder().name("tempNote").type("String").transientField(true).build();
        EntityInfo entity = new EntityInfo("com.example.Widget", "Widget", "widget",
                "Widget.java", List.of(idField, tempField), List.of(), List.of());

        DatabaseSchema schema = new SchemaBuilder().build(List.of(entity), List.of(),
                RelationalSchema.empty(), RelationalSchema.empty(), List.of());

        SchemaColumn tempCol = schema.getTablesByName().get("widget").getColumns().stream()
                .filter(c -> "temp_note".equals(c.getName())).findFirst().orElseThrow();
        assertFalse(tempCol.getDerivationLogic().contains("@Formula"),
                "A @Transient field's derivation logic should not claim it's a @Formula");
    }

    @Test
    void testMergesJpaRelationWithSqlForeignKeyAsMerged() {
        ParsedColumn.Builder userId = new ParsedColumn.Builder();
        userId.name = "user_id"; userId.sqlType = "BIGINT"; userId.nullable = false;
        ParsedTable ordersTable = new ParsedTable("orders", List.of(userId.build()), List.of(),
                List.of(new ParsedForeignKey("orders", "user_id", "users", "id", "fk_user")),
                List.of(), List.of());
        RelationalSchema sqlSchema = new RelationalSchema(Map.of("orders", ordersTable), List.of(), List.of());

        RelationInfo userRelation = new RelationInfo("user", "User",
                RelationInfo.Kind.MANY_TO_ONE, "user_id", null);
        EntityInfo orderEntity = new EntityInfo("com.example.Order", "Order", "orders",
                "Order.java", List.of(), List.of(userRelation), List.of());

        SchemaBuilder builder = new SchemaBuilder();
        DatabaseSchema schema = builder.build(List.of(orderEntity), List.of(),
                RelationalSchema.empty(), sqlSchema, List.of());

        assertEquals(1, schema.getRelationships().size());
        SchemaRelationship rel = schema.getRelationships().get(0);
        assertEquals(DataProvenance.MERGED, rel.getProvenance());
        assertEquals(SchemaRelationship.CardinalitySource.DECLARED_JPA, rel.getCardinalitySource());
        assertEquals(SchemaRelationship.Cardinality.MANY_TO_ONE, rel.getCardinality());
    }
}
