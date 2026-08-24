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
 *
 * [SAMPLE_MEDIA_ID] deliberately looks like the real, generated-UUID `media_id` a genuine
 * MediaTracker export carries (AGENTS.md §3.1) rather than a placeholder like `"media-1"` --
 * [LibraryCsvImporter] itself doesn't enforce UUID syntax (see that class's KDoc on
 * [LibraryCsvImporter.parseRow]'s `media_id` handling for why), but these fixtures should still
 * model what well-formed data actually looks like rather than implying a non-UUID id is the norm.
 */
private const val SAMPLE_MEDIA_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

class LibraryCsvImporterTest {
    private fun validRow(
        mediaId: String = SAMPLE_MEDIA_ID,
        type: String = "BOOK",
        title: String = "Dune",
        authors: String = "Frank Herbert",
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
    ): List<String> =
        listOf(
            CSV_SCHEMA_VERSION.toString(),
            mediaId,
            type,
            title,
            authors,
            releaseYear,
            purchasePrice,
            createdAt,
            coverImageHash,
            isbn,
            format,
            totalPages,
            status,
            finishedAt,
            trackingMode,
            externalIdentifiers,
            // The v3 movie columns (runtime_minutes/watch_status/watched_at) and the v4
            // total_seasons column. Blank because every fixture here is a book, and kept present so
            // these rows stay the width a real current export actually has -- parseRow ignores
            // them, but a fixture that quietly stayed a format behind would be a poor model of the
            // file it stands in for.
            "",
            "",
            "",
            "",
        )

    @Test
    fun parseRow_happyPath_parsesEveryField() {
        val result = LibraryCsvImporter.parseRow(validRow())
        assertIs<LibraryRowParseResult.Parsed>(result)
        val row = result.row
        assertEquals(SAMPLE_MEDIA_ID, row.mediaId)
        assertEquals("Dune", row.title)
        assertEquals("Frank Herbert", row.authors)
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
    fun parseRow_nonUuidMediaId_isAcceptedByDesign() {
        // Deliberate, not an oversight -- see LibraryCsvImporter.buildRow's KDoc on its media_id
        // handling. AGENTS.md §3.1 governs ids this app *generates*; a non-blank, human-chosen id
        // in a file this app is *consuming* (a hand-crafted or hand-edited library_export.csv) is a
        // different situation with no corresponding safety benefit to rejecting it -- nothing
        // downstream requires or assumes UUID syntax. This test pins that decision so it isn't
        // silently reversed by a future "id looks wrong, let's validate it" change.
        val result = LibraryCsvImporter.parseRow(validRow(mediaId = "book-1"))
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals("book-1", result.row.mediaId)
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

    // --- non-finite purchase_price (Finding 1: NaN/Infinity must not silently pass validation) ---

    @Test
    fun parseRow_nanPurchasePrice_isRejected() {
        // String.toDoubleOrNull() happily parses "NaN" to Double.NaN, and NaN < 0.0 is false (IEEE
        // 754) -- without an explicit finite check, this would sail past
        // BookMetadataValidation.validatePurchasePrice's negativity check and get persisted.
        val result = LibraryCsvImporter.parseRow(validRow(purchasePrice = "NaN"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("purchase_price"))
    }

    @Test
    fun parseRow_infinityPurchasePrice_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(purchasePrice = "Infinity"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("purchase_price"))
    }

    @Test
    fun parseRow_negativeInfinityPurchasePrice_isRejected() {
        val result = LibraryCsvImporter.parseRow(validRow(purchasePrice = "-Infinity"))
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("purchase_price"))
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
        val result =
            LibraryCsvImporter.parseRow(
                validRow(
                    authors = "",
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
        assertEquals(null, row.authors)
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
        val result =
            LibraryCsvImporter.parseRow(
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

    // --- authors (schema v5 / CSV v2, ROADMAP Task 9 Phase A) -------------------------------------

    @Test
    fun parseRow_authorsPopulated_passedThroughVerbatim() {
        val result = LibraryCsvImporter.parseRow(validRow(authors = "Ann Sample Author; B. Other Author"))
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals("Ann Sample Author; B. Other Author", result.row.authors)
    }

    @Test
    fun parseRow_blankAuthors_parsesAsNull() {
        val result = LibraryCsvImporter.parseRow(validRow(authors = ""))
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals(null, result.row.authors)
    }

    // --- padLegacyV1Row (ROADMAP Task 9 Phase A: a v1 file must still import) ---------------------

    @Test
    fun padLegacyV1Row_insertsBlankAuthorsAtCorrectPosition() {
        val v1Row =
            listOf(
                "1",
                SAMPLE_MEDIA_ID,
                "BOOK",
                "Dune",
                "1965",
                "9.99",
                "2024-01-01T00:00:00Z",
                "",
                "9780441013593",
                "PAPERBACK",
                "412",
                "READING",
                "",
                "PAGES",
                "ISBN:9780441013593",
            )
        val padded = LibraryCsvImporter.padLegacyV1Row(v1Row)

        // Same 15 values, now the current width, with a blank inserted right after "Dune"
        // (index 3) and the v3 movie columns plus the v4 total_seasons column padded on the end --
        // a v1 file is three format changes behind, so this adapter has to carry all of them.
        assertEquals(LibraryCsvExporter.HEADER.size, padded.size)
        assertEquals("Dune", padded[3])
        assertEquals("", padded[4])
        assertEquals("1965", padded[5])
        assertEquals("ISBN:9780441013593", padded[15])

        // And the padded row parses exactly like a genuine blank-authors row would.
        val result = LibraryCsvImporter.parseRow(padded)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals(null, result.row.authors)
        assertEquals("Dune", result.row.title)
    }

    // --- padLegacyV2Row (ROADMAP Task 13 Phase B: a v2 file must still import) --------------------

    @Test
    fun padLegacyV2Row_appendsBlankMovieColumnsAndLeavesEveryExistingValueInPlace() {
        val v2Row =
            listOf(
                "2",
                SAMPLE_MEDIA_ID,
                "BOOK",
                "Dune",
                "Frank Herbert",
                "1965",
                "9.99",
                "2024-01-01T00:00:00Z",
                "",
                "9780441013593",
                "PAPERBACK",
                "412",
                "READING",
                "",
                "PAGES",
                "ISBN:9780441013593",
            )
        val padded = LibraryCsvImporter.padLegacyV2Row(v2Row)

        assertEquals(LibraryCsvExporter.HEADER.size, padded.size)
        // A pure append: every column a v2 file already had keeps its index, which is the whole
        // reason the v3 movie columns -- and, since then, the v4 total_seasons column -- went on
        // the end.
        assertEquals(v2Row, padded.take(v2Row.size))
        assertTrue(
            padded.drop(v2Row.size).all { it.isEmpty() },
            "the appended movie/total_seasons columns must be blank",
        )

        val result = LibraryCsvImporter.parseRow(padded)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals("Dune", result.row.title)
        assertEquals("Frank Herbert", result.row.authors)
    }

    // --- padLegacyV3Row (ROADMAP Task 13 Phase C: a v3 file must still import) --------------------

    @Test
    fun padLegacyV3Row_appendsBlankTotalSeasonsAndLeavesEveryExistingValueInPlace() {
        val v3Row =
            listOf(
                "3",
                SAMPLE_MEDIA_ID,
                "BOOK",
                "Dune",
                "Frank Herbert",
                "1965",
                "9.99",
                "2024-01-01T00:00:00Z",
                "",
                "9780441013593",
                "PAPERBACK",
                "412",
                "READING",
                "",
                "PAGES",
                "ISBN:9780441013593",
                "",
                "",
                "",
            )
        val padded = LibraryCsvImporter.padLegacyV3Row(v3Row)

        assertEquals(LibraryCsvExporter.HEADER.size, padded.size)
        // A pure append: every column a v3 file already had keeps its index, which is the whole
        // reason total_seasons went on the end.
        assertEquals(v3Row, padded.take(v3Row.size))
        assertTrue(padded.drop(v3Row.size).all { it.isEmpty() }, "the appended total_seasons column must be blank")

        val result = LibraryCsvImporter.parseRow(padded)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals("Dune", result.row.title)
        assertEquals("Frank Herbert", result.row.authors)
    }
}
