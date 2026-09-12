package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.render.CrossReferenceGraph.Category;
import com.devmanchego.contextextractor.render.CrossReferenceGraph.Node;
import com.devmanchego.contextextractor.render.CrossReferenceGraph.RelatedLink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelatedLinksRendererTest {

    private final RelatedLinksRenderer renderer = new RelatedLinksRenderer();

    @Test
    void emptyLinks_producesNoSection() {
        assertEquals("", renderer.render(List.of(), "indexed_specs/detailed_tables/employees.md"));
    }

    @Test
    void siblingTarget_usesBareFilename() {
        Node dept = new Node(Category.TABLE, "departments", "indexed_specs/detailed_tables/departments.md", null);
        String out = renderer.render(
                List.of(new RelatedLink(dept, "FK N:1 via `department_id`", false, true)),
                "indexed_specs/detailed_tables/employees.md");
        assertTrue(out.contains("[`departments`](departments.md)"), out);
        assertTrue(out.contains("**Links out:**"));
    }

    @Test
    void crossFolderTarget_usesRelativeParentPath() {
        Node mapping = new Node(Category.PERSISTENCE_MAPPING, "EmployeeResponse ↔ Employee",
                "indexed_specs/detailed_persistence_mappings/employeeresponse-employee.md", null);
        String out = renderer.render(
                List.of(new RelatedLink(mapping, "mapped by", false, false)),
                "indexed_specs/detailed_tables/employees.md");
        assertTrue(out.contains("(../detailed_persistence_mappings/employeeresponse-employee.md)"), out);
        assertTrue(out.contains("**Referenced by:**"));
    }

    @Test
    void tableFallbackAnchor_appendedToPath() {
        Node table = new Node(Category.TABLE, "employees", "full_specs/api-spec-data-model.md", "table-employees");
        String out = renderer.render(
                List.of(new RelatedLink(table, "maps to table", false, true)),
                "indexed_specs/detailed_persistence_mappings/x.md");
        assertTrue(out.contains("../../full_specs/api-spec-data-model.md#table-employees"), out);
    }

    @Test
    void nonNavigableTarget_rendersAsTextWithNotIndexed() {
        Node contract = new Node(Category.DATA_CONTRACT, "EmployeeResponse ↔ Employee", null, null);
        String out = renderer.render(
                List.of(new RelatedLink(contract, "data contract", false, true)),
                "indexed_specs/detailed_persistence_mappings/x.md");
        assertTrue(out.contains("`EmployeeResponse ↔ Employee` *(not indexed)*"), out);
        assertFalse(out.contains("]("), "should not contain a link");
    }

    @Test
    void inferredEdge_marked() {
        Node table = new Node(Category.TABLE, "employees", "indexed_specs/detailed_tables/employees.md", null);
        String out = renderer.render(
                List.of(new RelatedLink(table, "touches table", true, true)),
                "indexed_specs/flows/flow-001.md");
        assertTrue(out.contains("*(inferred)*"), out);
    }

    @Test
    void innerType_stripsArrayAndUnwrapsCollections() {
        assertEquals("EmployeeResponse", CrossReferenceGraph.innerType("EmployeeResponse"));
        assertEquals("EmployeeResponse", CrossReferenceGraph.innerType("EmployeeResponse[]"));
        assertEquals("EmployeeResponse", CrossReferenceGraph.innerType("List<EmployeeResponse>"));
        assertEquals("Foo", CrossReferenceGraph.innerType("Set<Foo>"));
        assertEquals("Bar", CrossReferenceGraph.innerType("Page<Bar>"));
        assertEquals("", CrossReferenceGraph.innerType(null));
    }

    @Test
    void longGroup_truncatedWithMoreCount() {
        List<RelatedLink> links = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Node flow = new Node(Category.FLOW, "flow " + i, "indexed_specs/flows/flow-" + i + ".md", null);
            links.add(new RelatedLink(flow, "used by flow", false, false));
        }
        String out = renderer.render(links, "indexed_specs/detailed_data_contracts/x.md");
        assertTrue(out.contains("(+4 more)"), out); // 12 - MAX_PER_GROUP(8)
    }
}
