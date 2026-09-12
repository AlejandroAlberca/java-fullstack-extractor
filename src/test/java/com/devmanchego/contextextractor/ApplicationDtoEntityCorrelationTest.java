package com.devmanchego.contextextractor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4: {@link Application#dtoAndEntityNamesCorrelate} must correlate names regardless of which
 * side of the pairing carries the naming-convention suffix. Before this fix only the DTO side
 * was normalised, so a codebase that suffixes its persistence classes instead (AcheteurEntity
 * paired with an unsuffixed Acheteur bean) never correlated at all, however complete the data.
 */
class ApplicationDtoEntityCorrelationTest {

    @Test
    void suffixedEntity_unsuffixedDto_correlates() {
        // The exact real-world shape this fix targets: AcheteurEntity ↔ Acheteur.
        assertTrue(Application.dtoAndEntityNamesCorrelate("Acheteur", "AcheteurEntity"));
    }

    @Test
    void suffixedDto_unsuffixedEntity_stillCorrelates() {
        // The direction that already worked before this fix — must keep working.
        assertTrue(Application.dtoAndEntityNamesCorrelate("AcheteurDto", "Acheteur"));
    }

    @Test
    void bothSuffixed_correlates() {
        assertTrue(Application.dtoAndEntityNamesCorrelate("AcheteurDto", "AcheteurEntity"));
    }

    @Test
    void neitherSuffixed_exactNameMatch_correlates() {
        assertTrue(Application.dtoAndEntityNamesCorrelate("Acheteur", "Acheteur"));
    }

    @Test
    void unrelatedNames_doNotCorrelate() {
        assertFalse(Application.dtoAndEntityNamesCorrelate("InvoiceDto", "Acheteur"));
    }

    @Test
    void jpaEntitySuffix_stripsTheWholeSuffix_notJustEntity() {
        // "JpaEntity" must not be caught by a naive "Entity"-only strip, which would leave
        // "AcheteurJpa" and still fail to correlate with "Acheteur".
        assertTrue(Application.dtoAndEntityNamesCorrelate("Acheteur", "AcheteurJpaEntity"));
    }

    @Test
    void entityBeanSuffix_correlates() {
        assertTrue(Application.dtoAndEntityNamesCorrelate("Acheteur", "AcheteurEntityBean"));
    }

    @Test
    void dtoPrefixHeuristic_stillPreserved() {
        // Pre-existing fallback: a DTO name that extends an unsuffixed entity name.
        assertTrue(Application.dtoAndEntityNamesCorrelate("UserProfileDto", "User"));
    }
}
