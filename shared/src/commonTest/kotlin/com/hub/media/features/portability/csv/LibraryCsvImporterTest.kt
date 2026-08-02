package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests [LibraryCsvImporter], the per-row semantic parser (ROADMAP Task 8 Phase B). Priority:
 * business-rule rejection reuse ([com.hub.media.features.books.domain.BookMetadataValidation]),
 * and deliverable #4 -- the `PROVIDER:id|...` packed-identifier hazard, including an id that
 * itself contains a `:` (must round-trip) and a malformed segment with no `:` at all (must reject
 * the row rather than silently mis-splitting it).
 */
class LibraryCsvImporterTest {

    private fun validRow(
        mediaId: String = "media-1",
        type: String = "BOOK",
        title: String = "Dune",
        releaseYear: String = "1965",
        purchasePrice: String = "9.99",
        createdAt: String = "2024-01-01T00:00:00Z",
        coverImageHash: String = "",
        isbn: String = "9780441013593",
        format: String = "PAPERBACK",
        totalPages: String = "412",
        status: String = "READING",
        finishedAt: String = "",
        trackingMode: String = "PAGES",
        externalIdentifiers: String = "ISBN:9780441013593",
    ): List<String> = listOf(
        CSV_SCHEMA_VERSION.toString(), mediaId, type, title, releaseYear, purchasePrice, createdAt,
        coverImageHash, isbn, format, totalPages, status, finishedAt, trackingMode, externalIdentifiers,
    )

    @Test
    fun parseRow_happyPath_parsesEveryField() {
        val result = LibraryCsvImporter.parseRow(validRow())
        assertIs<LibraryRowParseResult.Parsed>(result)
        val row = result.row
        assertEquals("media-1", row.mediaId)
        assertEquals("Dune", row.title)
        assertEquals(1965, row.releaseYear)
        assertEquals(9.99, row.purchasePrice)
        assertEquals("9780441013593", row.isbn)
        assertEquals(BookFormat.PAPERBACK, row.format)
        assertEquals(412, row.totalPages)
        assertEquals(ReadingStatus.READING, row.status)
        assertEquals(TrackingMode.PAGES, row.trackingMode)
        assertEquals(listOf(IdentifierProvider.ISBN to "9780441013593"), row.externalIdentifiers)
    }

    @Test
    fun parseRow_blankMediaId_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(mediaId = ""))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("media_id"))
    }

    @Test
    fun parseRow_blankTitle_isRejected_reusingBookMetadataValidation() {
        val result = LibraryCsvImporter.parseRow(validRow(title = ""))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Title"))
    }

    @Test
    fun parseRow_releaseYearOutOfBounds_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(releaseYear = "999"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Release year"))
    }

    @Test
    fun parseRow_negativePurchasePrice_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(purchasePrice = "-5.00"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Purchase price"))
    }

    @Test
    fun parseRow_zeroTotalPages_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(totalPages = "0"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Total pages"))
    }

    @Test
    fun parseRow_unknownBookFormat_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(format = "LASERDISC"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("format"))
    }

    @Test
    fun parseRow_unknownMediaType_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(type = "MOVIE"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("MOVIE"))
    }

    @Test
    fun parseRow_malformedCreatedAt_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(createdAt = "not-a-date"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("created_at"))
    }

    @Test
    fun parseRow_blankOptionalFields_parseAsNullWithDefaults() {
        val result = LibraryCsvImporter.parseRow(
            validRow(
                releaseYear = "",
                purchasePrice = "",
                isbn = "",
                format = "",
                totalPages = "",
                status = "",
                finishedAt = "",
                trackingMode = "",
                externalIdentifiers = "",
            ),
        )
        assertIs<LibraryRowParseResult.Parsed>(result)
        val row = result.row
        assertEquals(null, row.releaseYear)
        assertEquals(null, row.purchasePrice)
        assertEquals(null, row.isbn)
        assertEquals(BookFormat.PHYSICAL, row.format)
        assertEquals(null, row.totalPages)
        assertEquals(ReadingStatus.TO_READ, row.status)
        assertEquals(null, row.finishedAt)
        assertEquals(TrackingMode.PERCENT, row.trackingMode) // no totalPages -> PERCENT default
        assertEquals(emptyList(), row.externalIdentifiers)
    }

    // --- external_identifiers packing hazard (deliverable #4) ---

    @Test
    fun parseRow_identifierContainingColon_roundTripsViaFirstColonSplit() {
        val result = LibraryCsvImporter.parseRow(
            validRow(externalIdentifiers = "TMDB:abc:def|ISBN:9780441013593"),
        )
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals(
            listOf(IdentifierProvider.TMDB to "abc:def", IdentifierProvider.ISBN to "9780441013593"),
            result.row.externalIdentifiers,
        )
    }

    @Test
    fun parseRow_identifierSegmentMissingColon_isRejectedNotSilentlyMisparsed() {
        // Simulates the fallout of an id containing a literal '|' (the encoding's known
        // limitation, see unpackIdentifiers' KDoc): the segment has no ':' at all.
        val result = LibraryCsvImporter.parseRow(validRow(externalIdentifiers = "ISBN:123|noColonHere"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains(":"))
    }

    @Test
    fun parseRow_identifierUnknownProvider_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(externalIdentifiers = "NOT_A_PROVIDER:123"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Unknown identifier provider"))
    }

    @Test
    fun parseRow_identifierWithEmptyId_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(externalIdentifiers = "ISBN:"))
        assertIs<LibraryRowParseResult.Rejected>(result)
    }
}
