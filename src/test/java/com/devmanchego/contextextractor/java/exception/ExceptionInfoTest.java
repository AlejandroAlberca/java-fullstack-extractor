package com.devmanchego.contextextractor.java.exception;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionInfoTest {

    @Test
    void testOrphanDetection() {
        ExceptionInfo orphan = new ExceptionInfo(
                "CustomException",
                "com.example.CustomException",
                "APP",
                "APP-001",
                -1,  // no HTTP status
                "Custom error occurred",
                "com.example.Service",
                "process",
                45,
                Set.of(),
                java.util.List.of("process:45"),
                null,  // no handler
                false,
                true
        );

        assertTrue(orphan.isOrphan());
        assertFalse(orphan.hasHandler());
    }

    @Test
    void testServerErrorDetection() {
        ExceptionInfo serverError = new ExceptionInfo(
                "InternalException",
                "com.example.InternalException",
                "APP",
                "APP-002",
                500,
                "Internal server error",
                "com.example.Service",
                "compute",
                67,
                Set.of(),
                java.util.List.of("compute:67"),
                "com.example.GlobalHandler",
                true,
                false
        );

        assertTrue(serverError.isServerError());
        assertTrue(serverError.hasHandler());
    }

    @Test
    void testErrorCodeGeneration() {
        ExceptionInfo exc = new ExceptionInfo(
                "ResourceNotFoundException",
                "com.example.hrapp.exception.ResourceNotFoundException",
                "EMPLOYEE",
                "EMP-001",
                404,
                "Employee not found with id: {id}",
                "com.example.EmployeeService",
                "findById",
                50,
                Set.of("id"),
                java.util.List.of("findById:50"),
                "com.example.GlobalHandler",
                true,
                true
        );

        assertEquals("EMP-001", exc.getErrorCodeOrDefault());
        assertFalse(exc.isOrphan());
        assertFalse(exc.isServerError());
    }

    @Test
    void testClientErrorDetection() {
        ExceptionInfo clientError = new ExceptionInfo(
                "ConflictException",
                "com.example.hrapp.exception.ConflictException",
                "DEPARTMENT",
                "DEPT-002",
                409,
                "Department already exists with name: {name}",
                "com.example.DepartmentService",
                "create",
                42,
                Set.of("name"),
                java.util.List.of("create:42"),
                "com.example.GlobalHandler",
                true,
                true
        );

        assertFalse(clientError.isServerError());
        assertEquals(409, clientError.httpStatus());
    }
}
