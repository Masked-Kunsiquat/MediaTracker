package com.hub.media.features.portability.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests [parseOptionalDouble]/[parseRequiredDouble] directly (ROADMAP Task 8 Phase B, PR review
 * finding). Priority: `"NaN"`/`"Infinity"`/`"-Infinity"` must be rejected, not silently parsed to
 * a non-finite [Double] -- [String.toDoubleOrNull] happily accepts all three (mirroring
 * [Double.parseDouble]'s JLS-defined literals), and `NaN < 0.0` is `false` per IEEE 754, so a
 * downstream `< 0.0` check (e.g. [com.hub.media.features.books.domain.BookMetadataValidation
 * .validatePurchasePrice]) can never catch a `NaN` that slips past this layer. See
 * [com.hub.media.features.books.domain.ReadingSessionValidation.validatePositions] for the
 * precedent this fix follows.
 */
class CsvRowParsingTest {

    // --- parseOptionalDouble ---

    @Test
    fun parseOptionalDouble_blank_returnsNull() {
        assertNull(parseOptionalDouble("", "field"))
        assertNull(parseOptionalDouble("   ", "field"))
    }

    @Test
    fun parseOptionalDouble_validNumber_parses() {
        assertEquals(9.99, parseOptionalDouble("9.99", "field"))
        assertEquals(0.0, parseOptionalDouble("0", "field"))
        assertEquals(-5.0, parseOptionalDouble("-5.0", "field"))
    }

    @Test
    fun parseOptionalDouble_garbage_rejects() {
        val exception = assertFailsWith<RowRejectedException> { parseOptionalDouble("not-a-number", "field") }
        assertEquals("field is not a valid number: 'not-a-number'", exception.message)
    }

    @Test
    fun parseOptionalDouble_nan_rejects() {
        val exception = assertFailsWith<RowRejectedException> { parseOptionalDouble("NaN", "field") }
        assertEquals("field is not a valid number: 'NaN'", exception.message)
    }

    @Test
    fun parseOptionalDouble_positiveInfinity_rejects() {
        assertFailsWith<RowRejectedException> { parseOptionalDouble("Infinity", "field") }
    }

    @Test
    fun parseOptionalDouble_negativeInfinity_rejects() {
        assertFailsWith<RowRejectedException> { parseOptionalDouble("-Infinity", "field") }
    }

    // --- parseRequiredDouble ---

    @Test
    fun parseRequiredDouble_validNumber_parses() {
        assertEquals(50.0, parseRequiredDouble("50.0", "field"))
    }

    @Test
    fun parseRequiredDouble_blank_rejects() {
        assertFailsWith<RowRejectedException> { parseRequiredDouble("", "field") }
    }

    @Test
    fun parseRequiredDouble_nan_rejects() {
        val exception = assertFailsWith<RowRejectedException> { parseRequiredDouble("NaN", "field") }
        assertEquals("field is not a valid number: 'NaN'", exception.message)
    }

    @Test
    fun parseRequiredDouble_positiveInfinity_rejects() {
        assertFailsWith<RowRejectedException> { parseRequiredDouble("Infinity", "field") }
    }

    @Test
    fun parseRequiredDouble_negativeInfinity_rejects() {
        assertFailsWith<RowRejectedException> { parseRequiredDouble("-Infinity", "field") }
    }
}
