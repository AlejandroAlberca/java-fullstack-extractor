package com.devmanchego.contextextractor.render;

import com.devmanchego.contextextractor.java.exception.ExceptionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ErrorCatalogRendererTest {

    @Test
    void testRenderNoExceptions() {
        String output = new ErrorCatalogRenderer().render(List.of());

        assertNotNull(output);
        assertTrue(output.contains("No custom exceptions detected"));
        assertTrue(output.contains("# ERROR CATALOG"));
    }

    @Test
    void testRenderWithExceptions() {
        ExceptionInfo exc1 = new ExceptionInfo(
                "ResourceNotFoundException",
                "com.example.exception.ResourceNotFoundException",
                "EMPLOYEE",
                "EMP-001",
                404,
                "Employee not found with id: {id}",
                "com.example.EmployeeService",
                "findById",
                50,
                Set.of("id"),
                List.of("findById:50"),
                "com.example.GlobalHandler",
                true,
                true
        );

        ExceptionInfo exc2 = new ExceptionInfo(
                "ConflictException",
                "com.example.exception.ConflictException",
                "DEPARTMENT",
                "DEPT-001",
                409,
                "Department already exists",
                "com.example.DepartmentService",
                "create",
                42,
                Set.of(),
                List.of("create:42"),
                "com.example.GlobalHandler",
                true,
                true
        );

        String output = new ErrorCatalogRenderer().render(List.of(exc1, exc2));

        assertTrue(output.contains("## Summary"));
        assertTrue(output.contains("## Exception Index"));
        assertTrue(output.contains("## Exception Details"));
        assertTrue(output.contains("ResourceNotFoundException"));
        assertTrue(output.contains("ConflictException"));
        assertTrue(output.contains("EMP-001"));
        assertTrue(output.contains("DEPT-001"));
    }

    @Test
    void testRenderOrphanWarnings() {
        ExceptionInfo orphan = new ExceptionInfo(
                "UnhandledException",
                "com.example.exception.UnhandledException",
                "APP",
                "APP-001",
                -1,
                "Something went wrong",
                "com.example.Service",
                "process",
                99,
                Set.of(),
                List.of("process:99"),
                null,
                false,
                true
        );

        String output = new ErrorCatalogRenderer().render(List.of(orphan));

        assertTrue(output.contains("⚠️ Orphan Exceptions"));
        assertTrue(output.contains("ORPHAN"));
        assertTrue(output.contains("No handler"));
    }

    @Test
    void testRenderWithMultipleThrowLocations() {
        ExceptionInfo exc = new ExceptionInfo(
                "ConflictException",
                "com.example.exception.ConflictException",
                "EMPLOYEE",
                "EMP-002",
                409,
                "Conflict detected",
                "com.example.EmployeeService",
                "update",
                70,
                Set.of(),
                List.of("create:42", "update:70", "delete:88"),
                "com.example.GlobalHandler",
                true,
                true
        );

        String output = new ErrorCatalogRenderer().render(List.of(exc));

        assertTrue(output.contains("Thrown from:"));
        assertTrue(output.contains("create:42"));
        assertTrue(output.contains("update:70"));
    }

    @Test
    void testRenderSummaryStats() {
        List<ExceptionInfo> exceptions = List.of(
                new ExceptionInfo("Exc1", "com.example.Exc1", "APP", "APP-001", 404,
                        "msg1", "cls1", "m1", 1, Set.of(), List.of(), "handler", true, true),
                new ExceptionInfo("Exc2", "com.example.Exc2", "APP", "APP-002", -1,
                        "msg2", "cls2", "m2", 2, Set.of(), List.of(), null, false, true),
                new ExceptionInfo("Exc3", "com.example.Exc3", "APP", "APP-003", 500,
                        "msg3", "cls3", "m3", 3, Set.of(), List.of(), "handler", true, true)
        );

        String output = new ErrorCatalogRenderer().render(exceptions);

        assertTrue(output.contains("**Total custom exceptions:**") && output.contains("3"), "Should contain total exception count");
        assertTrue(output.contains("**With handlers:**") && output.contains("2"), "Should contain handler count");
        assertTrue(output.contains("**Orphan (no handler):**") && output.contains("1"), "Should contain orphan count");
        assertTrue(output.contains("**Server errors (5xx):**") && output.contains("1"), "Should contain server error count");
    }
}
