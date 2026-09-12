package com.devmanchego.contextextractor.java.persistence;

import com.devmanchego.contextextractor.java.model.EntityInfo;
import com.devmanchego.contextextractor.java.model.FieldInfo;
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

/**
 * P3: @MappedSuperclass inheritance (with @AttributeOverride) and @EmbeddedId composite keys.
 *
 * <p>Before this fix, an entity whose identifier came from a mapped superclass had no primary
 * key at all in the generated schema, and an @EmbeddedId was reported as a derived attribute
 * that "does not exist in the database" — the exact inversion this phase corrects.
 */
class JpaInheritanceAndCompositeKeyTest {

    private PersistenceMappingExtractor extractor;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        extractor = new PersistenceMappingExtractor();
        parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT));
    }

    // -----------------------------------------------------------------------
    // @MappedSuperclass — same file
    // -----------------------------------------------------------------------

    @Test
    void mappedSuperclassId_inheritedAsPrimaryKey() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @MappedSuperclass
            class AbstractEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.SEQUENCE)
                protected Long id;
            }
            @Entity
            @Table(name = "ACHETEUR")
            @AttributeOverride(name = "id", column = @Column(name = "ACHETEUR_ID"))
            public class AcheteurEntity extends AbstractEntity {
                @Column(name = "ACHETEUR_CODE", nullable = false)
                protected String code;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "AcheteurEntity.java").orElseThrow();

        FieldInfo idField = entity.getFields().stream()
                .filter(f -> "id".equals(f.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("Inherited @Id field must be present: " + entity.getFields()));

        assertTrue(idField.isPrimaryKey(), "Inherited @Id must still be recognised as the primary key");
        assertEquals("ACHETEUR_ID", idField.getColumnName(), "@AttributeOverride must rename the inherited column");
        assertEquals("AbstractEntity", idField.getKeyOrigin(),
                "Provenance must record which mapped superclass declared this key");
    }

    @Test
    void mappedSuperclassId_comesBeforeEntitysOwnFields() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @MappedSuperclass
            class AbstractEntity {
                @Id Long id;
            }
            @Entity
            public class Widget extends AbstractEntity {
                String name;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Widget.java").orElseThrow();

        assertEquals("id", entity.getFields().get(0).getName(),
                "Inherited identifier should be the first field, matching how the table actually reads");
        assertEquals("name", entity.getFields().get(1).getName());
    }

    @Test
    void plainJavaSuperclass_withoutMappedSuperclassAnnotation_contributesNoFields() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            class PlainBase {
                Long baseField;
            }
            @Entity
            public class Widget extends PlainBase {
                @Id Long id;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Widget.java").orElseThrow();

        assertTrue(entity.getFields().stream().noneMatch(f -> "baseField".equals(f.getName())),
                "A plain (non-@MappedSuperclass) superclass is not part of JPA state");
    }

    @Test
    void staticFieldOnMappedSuperclass_neverInherited() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @MappedSuperclass
            class AbstractEntity implements java.io.Serializable {
                private static final long serialVersionUID = 1L;
                @Id Long id;
            }
            @Entity
            public class Widget extends AbstractEntity {
                String name;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Widget.java").orElseThrow();

        assertTrue(entity.getFields().stream().noneMatch(f -> "serialVersionUID".equals(f.getName())));
    }

    // -----------------------------------------------------------------------
    // @MappedSuperclass — cross-file (the realistic case)
    // -----------------------------------------------------------------------

    @Test
    void mappedSuperclassInDifferentFile_stillResolved() {
        String baseSource = """
            package com.example;
            import jakarta.persistence.*;
            @MappedSuperclass
            public class AbstractEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.SEQUENCE)
                protected Long id;
            }
            """;
        String entitySource = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Table(name = "MARCHE")
            @AttributeOverride(name = "id", column = @Column(name = "MARCHE_ID"))
            public class MarcheEntity extends AbstractEntity {
                @Column(name = "MARCHE_CODE")
                private String code;
            }
            """;

        CompilationUnit baseCu = parse(baseSource);
        CompilationUnit entityCu = parse(entitySource);
        List<CompilationUnit> allCUs = List.of(baseCu, entityCu);

        EntityInfo entity = extractor.extractEntity(entityCu, "MarcheEntity.java", allCUs).orElseThrow();

        FieldInfo idField = entity.getFields().stream()
                .filter(f -> "id".equals(f.getName())).findFirst().orElseThrow();
        assertTrue(idField.isPrimaryKey());
        assertEquals("MARCHE_ID", idField.getColumnName());
        assertEquals("AbstractEntity", idField.getKeyOrigin());
    }

    @Test
    void withoutAllCUs_crossFileSuperclass_isNotResolved_butDoesNotFail() {
        // Documents the honest degradation: the 2-arg overload only sees one file, so a
        // superclass declared elsewhere simply isn't found — no crash, no fabricated data.
        String entitySource = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class MarcheEntity extends AbstractEntity {
                @Column(name = "MARCHE_CODE")
                private String code;
            }
            """;

        Optional<EntityInfo> result = extractor.extractEntity(parse(entitySource), "MarcheEntity.java");

        assertTrue(result.isPresent());
        assertTrue(result.get().getFields().stream().noneMatch(FieldInfo::isPrimaryKey));
    }

    // -----------------------------------------------------------------------
    // @EmbeddedId composite keys
    // -----------------------------------------------------------------------

    @Test
    void embeddedId_flattenedAsPrimaryKeyColumns_notDerived() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Embeddable
            class ArticleMarchePK implements java.io.Serializable {
                @Column(name = "ARTICLE_ID")
                private Long articleId;
                @Column(name = "MARCHE_ID")
                private Long marcheId;
            }
            @Entity
            @Table(name = "ARTICLEMARCHE")
            public class ArticleMarcheEntity {
                @EmbeddedId
                private ArticleMarchePK articleMarchePK;
                @Column(name = "QUANTITY")
                private Integer quantity;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "ArticleMarcheEntity.java").orElseThrow();

        List<FieldInfo> pkFields = entity.getFields().stream().filter(FieldInfo::isPrimaryKey).toList();
        assertEquals(2, pkFields.size(), "Both embeddable component fields must become PK columns: "
                + entity.getFields());
        assertTrue(pkFields.stream().anyMatch(f -> "ARTICLE_ID".equals(f.getColumnName())));
        assertTrue(pkFields.stream().anyMatch(f -> "MARCHE_ID".equals(f.getColumnName())));
        pkFields.forEach(f -> assertFalse(f.isNullable(), "A primary key component is never nullable"));

        // Must not appear as a single un-flattened field of the embeddable's own type.
        assertTrue(entity.getFields().stream().noneMatch(f -> "articleMarchePK".equals(f.getName())));
    }

    @Test
    void embeddedId_componentNames_useDottedPathOfEmbeddedFieldName() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Embeddable
            class OrderLinePK {
                private Long orderId;
                private Integer lineNumber;
            }
            @Entity
            public class OrderLine {
                @EmbeddedId
                private OrderLinePK pk;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "OrderLine.java").orElseThrow();

        assertTrue(entity.getFields().stream().anyMatch(f -> "pk.orderId".equals(f.getName())));
        assertTrue(entity.getFields().stream().anyMatch(f -> "pk.lineNumber".equals(f.getName())));
    }

    @Test
    void embeddedId_staticFieldOnEmbeddable_neverBecomesAKeyColumn() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Embeddable
            class Pk implements java.io.Serializable {
                private static final long serialVersionUID = 1L;
                private Long a;
            }
            @Entity
            public class Widget {
                @EmbeddedId
                private Pk pk;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Widget.java").orElseThrow();

        assertTrue(entity.getFields().stream().noneMatch(f -> f.getName().contains("serialVersionUID")));
        assertEquals(1, entity.getFields().size());
    }

    @Test
    void embeddedId_unresolvableEmbeddableType_stillProducesAPlaceholderPrimaryKey() {
        // The embeddable class isn't in this compilation unit at all (e.g. from a jar).
        // The table must still end up with *a* primary key, not none.
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Widget {
                @EmbeddedId
                private ExternalPk pk;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Widget.java").orElseThrow();

        assertEquals(1, entity.getFields().size());
        assertTrue(entity.getFields().get(0).isPrimaryKey());
    }

    // -----------------------------------------------------------------------
    // @Formula — must not be conflated with "no @Column means derived"
    // -----------------------------------------------------------------------

    @Test
    void formulaField_markedAsFormula() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            import org.hibernate.annotations.Formula;
            @Entity
            public class MaturiteJalon {
                @Id Long id;
                @Formula("date_part('day', now() - jalon_date)")
                private Integer delai;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "MaturiteJalon.java").orElseThrow();

        FieldInfo delai = entity.getFields().stream()
                .filter(f -> "delai".equals(f.getName())).findFirst().orElseThrow();
        assertTrue(delai.isFormula());
    }

    @Test
    void formulaField_capturesTheExpressionText() {
        // P4: the expression itself must be reported, not a generic "it's derived" label.
        String source = """
            package com.example;
            import jakarta.persistence.*;
            import org.hibernate.annotations.Formula;
            @Entity
            public class MaturiteJalon {
                @Id Long id;
                @Formula("date_part('day', now() - jalon_date)")
                private Integer delai;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "MaturiteJalon.java").orElseThrow();

        FieldInfo delai = entity.getFields().stream()
                .filter(f -> "delai".equals(f.getName())).findFirst().orElseThrow();
        assertEquals("date_part('day', now() - jalon_date)", delai.getFormulaExpression());
    }

    @Test
    void formulaField_valueAttributeForm_alsoCaptured() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            import org.hibernate.annotations.Formula;
            @Entity
            public class Widget {
                @Id Long id;
                @Formula(value = "price * quantity")
                private java.math.BigDecimal total;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Widget.java").orElseThrow();

        FieldInfo total = entity.getFields().stream()
                .filter(f -> "total".equals(f.getName())).findFirst().orElseThrow();
        assertEquals("price * quantity", total.getFormulaExpression());
    }

    @Test
    void plainFieldWithoutColumnAnnotation_isNotFormulaOrTransient() {
        String source = """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Widget {
                @Id Long id;
                private String name;
            }
            """;

        EntityInfo entity = extractor.extractEntity(parse(source), "Widget.java").orElseThrow();

        FieldInfo name = entity.getFields().stream()
                .filter(f -> "name".equals(f.getName())).findFirst().orElseThrow();
        assertFalse(name.isFormula());
        assertFalse(name.isTransient());
    }

    private CompilationUnit parse(String source) {
        ParseResult<CompilationUnit> pr = parser.parse(source);
        assertTrue(pr.isSuccessful(), "Parse failed: " + pr.getProblems());
        return pr.getResult().orElseThrow();
    }
}
