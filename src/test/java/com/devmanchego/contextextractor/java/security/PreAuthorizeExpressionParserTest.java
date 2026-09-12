package com.devmanchego.contextextractor.java.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PreAuthorizeExpressionParserTest {

    @Test
    void hasRole_singleQuote_fullyParsed() {
        var result = PreAuthorizeExpressionParser.parse("hasRole('ADMIN')");
        assertEquals(Set.of("ADMIN"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void hasAnyRole_multipleArguments_allCaptured() {
        var result = PreAuthorizeExpressionParser.parse("hasAnyRole('ADMIN', 'USER')");
        assertEquals(Set.of("ADMIN", "USER"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void hasAuthority_fullyParsed() {
        var result = PreAuthorizeExpressionParser.parse("hasAuthority('WRITE_PRODUCTS')");
        assertEquals(Set.of("WRITE_PRODUCTS"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void hasPermission_simpleTargetAndPermission_extractsPermission() {
        var result = PreAuthorizeExpressionParser.parse("hasPermission('', 'ACHAT')");
        assertEquals(Set.of("ACHAT"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void hasPermission_compoundPermission_decomposedIntoConstituents() {
        var result = PreAuthorizeExpressionParser.parse("hasPermission('', 'ACHAT|ADMINISTRATION')");
        assertEquals(Set.of("ACHAT", "ADMINISTRATION"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void hasPermission_withExtraWhitespaceBeforeParen_stillMatches() {
        // Observed in the wild: "hasPermission ('', 'ACHAT')" with a space before the paren.
        var result = PreAuthorizeExpressionParser.parse("hasPermission ('', 'ACHAT')");
        assertEquals(Set.of("ACHAT"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void hasPermission_targetArgumentContainsNestedCall_permissionStillExtracted() {
        // A naive "match up to the first close-paren" regex would truncate the argument list
        // at getTarget()'s own closing paren and miss the permission literal entirely.
        var result = PreAuthorizeExpressionParser.parse("hasPermission(getTarget(), 'ACHAT')");
        assertEquals(Set.of("ACHAT"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void hasPermission_withNonLiteralArgument_notFullyParsed() {
        var result = PreAuthorizeExpressionParser.parse(
                "hasPermission(authentication.getAuthorities(), someVariable)");
        assertTrue(result.roles().isEmpty());
        assertFalse(result.fullyParsed());
    }

    @Test
    void combinedRoleAndPermission_withOrConnective_fullyParsed() {
        var result = PreAuthorizeExpressionParser.parse("hasPermission('', 'ACHAT') or hasRole('ADMIN')");
        assertEquals(Set.of("ACHAT", "ADMIN"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void combinedWithAndOperatorAndParens_fullyParsed() {
        var result = PreAuthorizeExpressionParser.parse("(hasRole('ADMIN') && hasAuthority('WRITE'))");
        assertEquals(Set.of("ADMIN", "WRITE"), result.roles());
        assertTrue(result.fullyParsed());
    }

    @Test
    void unrecognisedExpression_notFullyParsed_noRoles() {
        var result = PreAuthorizeExpressionParser.parse("@customBean.check(#id, authentication)");
        assertTrue(result.roles().isEmpty());
        assertFalse(result.fullyParsed());
    }

    @Test
    void expressionMixingRecognisedAndUnrecognisedPredicates_notFullyParsed() {
        var result = PreAuthorizeExpressionParser.parse("hasRole('ADMIN') and @customBean.check(#id)");
        // The role is still captured — it must not be discarded just because part of the
        // expression is unrecognised — but the expression as a whole is not fully explained.
        assertEquals(Set.of("ADMIN"), result.roles());
        assertFalse(result.fullyParsed());
    }

    @Test
    void nullOrBlankExpression_notFullyParsed() {
        assertFalse(PreAuthorizeExpressionParser.parse(null).fullyParsed());
        assertFalse(PreAuthorizeExpressionParser.parse("  ").fullyParsed());
    }
}
