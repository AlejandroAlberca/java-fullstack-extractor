package com.devmanchego.contextextractor.java.persistence;

import com.devmanchego.contextextractor.java.model.*;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceMappingExtractorTest {

    private PersistenceMappingExtractor extractor;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        extractor = new PersistenceMappingExtractor();
        parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT));
    }

    // -----------------------------------------------------------------------
    // Entity extraction
    // -----------------------------------------------------------------------

    @Test
    void entityAnnotation_extractsTableName() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Table(name = "customers")
            public class Customer {
                @Id @GeneratedValue Long id;
                @Column(name = "full_name", nullable = false, length = 255)
                String name;
            }
            """;

        Optional<EntityInfo> result = extractor.extractEntity(parse(source), "Customer.java");

        assertTrue(result.isPresent());
        assertEquals("customers", result.get().getTableName());
    }

    @Test
    void pkAndGeneratedValue_markedCorrectly() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Item {
                @Id @GeneratedValue Long id;
                String name;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Item.java").orElseThrow();
        FieldInfo idField = entity.getFields().stream()
                .filter(f -> "id".equals(f.getName())).findFirst().orElseThrow();

        assertTrue(idField.isPrimaryKey());
        assertTrue(idField.isGeneratedValue());
    }

    @Test
    void transientField_markedNotPersisted() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Order {
                @Id Long id;
                @Transient String tempNote;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Order.java").orElseThrow();
        FieldInfo f = entity.getFields().stream()
                .filter(fi -> "tempNote".equals(fi.getName())).findFirst().orElseThrow();
        assertTrue(f.isTransient());
    }

    @Test
    void staticField_neverExtractedAsAnEntityField() {
        // P4: serialVersionUID (or any other static field) is a JVM implementation detail,
        // never persistent state — it must not appear in the field list at all.
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Order implements java.io.Serializable {
                private static final long serialVersionUID = 1L;
                @Id Long id;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Order.java").orElseThrow();

        assertTrue(entity.getFields().stream().noneMatch(f -> "serialVersionUID".equals(f.getName())),
                "static fields must never appear in the entity's field list: " + entity.getFields());
        assertEquals(1, entity.getFields().size());
    }

    @Test
    void staticField_neverExtractedAsADtoField() {
        String source = """
            package com.example.dto;
            public class OrderDto {
                private static final long serialVersionUID = 1L;
                public static final int MAX_ITEMS = 100;
                private String status;
            }
            """;

        var dtos = extractor.extractDtos(parse(source), "OrderDto.java");
        assertEquals(1, dtos.size());
        DtoInfo dto = dtos.get(0);

        assertTrue(dto.getFields().stream().noneMatch(f -> "serialVersionUID".equals(f.getName())));
        assertTrue(dto.getFields().stream().noneMatch(f -> "MAX_ITEMS".equals(f.getName())));
        assertEquals(1, dto.getFields().size());
        assertEquals("status", dto.getFields().get(0).getName());
    }

    @Test
    void relationAnnotations_reportedInRelationsBlock_notInFieldTable() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            import java.util.List;
            @Entity
            public class Department {
                @Id Long id;
                @OneToMany(mappedBy = "department")
                List<Employee> employees;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Dept.java").orElseThrow();

        // employees must not appear in fields
        assertTrue(entity.getFields().stream().noneMatch(f -> "employees".equals(f.getName())),
                "Relation fields must not appear in the field table");

        // employees must appear in relations
        assertEquals(1, entity.getRelations().size());
        assertEquals("employees", entity.getRelations().get(0).getFieldName());
        assertEquals(RelationInfo.Kind.ONE_TO_MANY, entity.getRelations().get(0).getKind());
    }

    // -----------------------------------------------------------------------
    // MapStruct mapping
    // -----------------------------------------------------------------------

    @Test
    void explicitMapperMapping_overridesNameHeuristic() {
        String dtoSource = """
            package com.example;
            public class CustomerDto {
                String status;
            }
            """;
        String entitySource = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Customer {
                @Id Long id;
                String state;
            }
            """;
        String mapperSource = """
            package com.example;
            import org.mapstruct.*;
            @Mapper
            public interface CustomerMapper {
                @Mapping(source = "status", target = "state")
                Customer toEntity(CustomerDto dto);
            }
            """;

        DtoInfo dto    = extractor.extractDto(parse(dtoSource), "Dto.java").orElseThrow();
        EntityInfo ent = extractor.extractEntity(parse(entitySource), "Ent.java").orElseThrow();
        PersistenceMapping mapping = extractor.correlate(dto, ent, List.of(parse(mapperSource)));

        assertTrue(mapping.hasMapper());
        MappingRow row = mapping.getRows().stream()
                .filter(r -> "status".equals(r.getDtoField())).findFirst().orElseThrow();
        assertEquals("state", row.getEntityField());
        assertEquals(MappingRow.Confidence.EXPLICIT, row.getConfidence());
    }

    @Test
    void noMapper_nameMatch_markedImplicit() {
        String dtoSource = """
            package com.example;
            public class ProductDto {
                String name;
                Double price;
            }
            """;
        String entitySource = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Product {
                @Id Long id;
                String name;
                Double price;
            }
            """;

        DtoInfo dto    = extractor.extractDto(parse(dtoSource), "Dto.java").orElseThrow();
        EntityInfo ent = extractor.extractEntity(parse(entitySource), "Ent.java").orElseThrow();
        PersistenceMapping mapping = extractor.correlate(dto, ent, List.of());

        assertFalse(mapping.hasMapper());
        mapping.getRows().stream()
                .filter(r -> !r.isEntityOnly())
                .forEach(r -> assertEquals(MappingRow.Confidence.IMPLICIT, r.getConfidence(),
                        "Name-matched field must be marked IMPLICIT: " + r.getDtoField()));
    }

    @Test
    void noMapper_noNameMatch_markedNoMatch() {
        String dtoSource = """
            package com.example;
            public class FooDto {
                String alpha;
            }
            """;
        String entitySource = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Foo {
                @Id Long id;
                String beta;
            }
            """;

        DtoInfo dto    = extractor.extractDto(parse(dtoSource), "Dto.java").orElseThrow();
        EntityInfo ent = extractor.extractEntity(parse(entitySource), "Ent.java").orElseThrow();
        PersistenceMapping mapping = extractor.correlate(dto, ent, List.of());

        MappingRow row = mapping.getRows().stream()
                .filter(r -> "alpha".equals(r.getDtoField())).findFirst().orElseThrow();
        assertEquals(MappingRow.Confidence.NO_MATCH, row.getConfidence());
        assertEquals("[no match]", row.getEntityField());
    }

    @Test
    void dtoOnlyAndEntityOnlyFields_bothVisible() {
        String dtoSource = """
            package com.example;
            public class OrderDto {
                String dtoOnlyField;
                String shared;
            }
            """;
        String entitySource = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Order {
                @Id Long id;
                String shared;
                String entityOnlyField;
            }
            """;

        DtoInfo dto    = extractor.extractDto(parse(dtoSource), "Dto.java").orElseThrow();
        EntityInfo ent = extractor.extractEntity(parse(entitySource), "Ent.java").orElseThrow();
        PersistenceMapping mapping = extractor.correlate(dto, ent, List.of());

        assertTrue(mapping.getRows().stream().anyMatch(MappingRow::isDtoOnly),
                "DTO-only field must be present");
        assertTrue(mapping.getRows().stream().anyMatch(MappingRow::isEntityOnly),
                "Entity-only field must be present");
    }

    // -----------------------------------------------------------------------
    // Java record DTO extraction
    // -----------------------------------------------------------------------

    @Test
    void topLevelRecord_extractedAsDto() {
        String source = """
            package com.example.model;
            public record HealthScoreDto(double score, String classification, int signalCount) {}
            """;

        List<DtoInfo> dtos = extractor.extractDtos(parse(source), "HealthScoreDto.java");

        assertEquals(1, dtos.size());
        DtoInfo dto = dtos.get(0);
        assertEquals("HealthScoreDto", dto.getSimpleName());
        assertEquals(3, dto.getFields().size());
        assertEquals("score",         dto.getFields().get(0).getName());
        assertEquals("double",        dto.getFields().get(0).getType());
        assertEquals("classification",dto.getFields().get(1).getName());
        assertEquals("signalCount",   dto.getFields().get(2).getName());
    }

    @Test
    void innerRecord_extractedAsDto() {
        String source = """
            package com.example.api;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class IngestionController {
                @PostMapping("/upload")
                public UploadResponse upload() { return null; }
                public record UploadResponse(String sessionId, int fileCount, String status) {}
            }
            """;

        List<DtoInfo> dtos = extractor.extractDtos(parse(source), "IngestionController.java");

        assertTrue(dtos.stream().anyMatch(d -> "UploadResponse".equals(d.getSimpleName())),
                "Inner record UploadResponse must be extracted");
        DtoInfo dto = dtos.stream().filter(d -> "UploadResponse".equals(d.getSimpleName()))
                .findFirst().orElseThrow();
        assertEquals(3, dto.getFields().size());
    }

    @Test
    void recordInModelPackage_extractedRegardlessOfSuffix() {
        // "AnalysisContext" doesn't end in Dto/Request/Response but is in model package
        String source = """
            package com.example.model;
            public record AnalysisContext(String sessionId, long startedAt) {}
            """;

        List<DtoInfo> dtos = extractor.extractDtos(parse(source), "AnalysisContext.java");

        assertEquals(1, dtos.size(), "Record in model package must be extracted regardless of name suffix");
    }

    @Test
    void classInModelPackage_extractedWithoutDtoSuffix() {
        String source = """
            package com.example.model;
            public class Diagnosis {
                private String code;
                private String description;
            }
            """;

        List<DtoInfo> dtos = extractor.extractDtos(parse(source), "Diagnosis.java");

        assertEquals(1, dtos.size(), "Class in model package must be extracted regardless of name suffix");
    }

    @Test
    void multipleRecordsPerFile_allExtracted() {
        String source = """
            package com.example.model;
            public record DiffResultDto(int added, int removed) {
                public record CallTreeDiffNode(String method, int delta) {}
                public record HotspotDiffEntry(String cls, double pct) {}
            }
            """;

        List<DtoInfo> dtos = extractor.extractDtos(parse(source), "DiffResultDto.java");

        assertEquals(3, dtos.size(), "Outer record + 2 inner records must all be extracted");
    }

    // -----------------------------------------------------------------------
    // Repository @Query extraction
    // -----------------------------------------------------------------------

    @Test
    void queryAnnotation_literalJpqlExtracted() {
        String source = """
            package com.example;
            import org.springframework.data.jpa.repository.*;
            @Repository
            public interface ProductRepository extends JpaRepository<Product, Long> {
                @Query("SELECT p FROM Product p WHERE p.active = true")
                List<Product> findActive();
            }
            """;

        List<RepositoryMethodInfo> methods = extractor.extractRepositoryMethods(parse(source));

        assertEquals(1, methods.size());
        assertTrue(methods.get(0).hasQuery());
        assertEquals("SELECT p FROM Product p WHERE p.active = true",
                methods.get(0).getQueryLiteral());
    }

    @Test
    void methodWithoutQuery_hasNoQueryLiteral() {
        String source = """
            package com.example;
            import org.springframework.data.jpa.repository.*;
            @Repository
            public interface OrderRepo extends JpaRepository<Order, Long> {
                List<Order> findByStatus(String status);
            }
            """;

        List<RepositoryMethodInfo> methods = extractor.extractRepositoryMethods(parse(source));

        assertEquals(1, methods.size());
        assertFalse(methods.get(0).hasQuery());
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private CompilationUnit parse(String source) {
        ParseResult<CompilationUnit> pr = parser.parse(source);
        assertTrue(pr.isSuccessful(), "Parse failed: " + pr.getProblems());
        return pr.getResult().orElseThrow();
    }
}
