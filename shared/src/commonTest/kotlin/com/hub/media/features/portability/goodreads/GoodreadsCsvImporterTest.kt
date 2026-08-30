package com.hub.media.features.portability.goodreads

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.portability.csv.LibraryRowParseResult
import com.hub.media.features.portability.csv.book
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests [GoodreadsCsvImporter] -- the Goodreads-column-to-[com.hub.media.features.portability.csv.ParsedLibraryRow]
 * mapping layer (ROADMAP Task 8 Phase D). Priority: the ISBN Excel-armor gotcha, the `Binding`/
 * `Exclusive Shelf` mapping tables and their fallbacks, the `Date Read`/`Date Added` mapping, and
 * the `Year Published` vs `Original Publication Year` decision -- every field this phase's brief
 * calls out by name. All book data below is invented.
 */
class GoodreadsCsvImporterTest {
    private fun fixedClock(instant: Instant): Clock =
        object : Clock {
            override fun now(): Instant = instant
        }

    /** Builds a (columnIndex, row) pair for an arbitrary subset/order of Goodreads columns. */
    private fun rowOf(vararg fields: Pair<String, String>): Pair<Map<String, Int>, List<String>> {
        val columnIndex = fields.mapIndexed { index, (name, _) -> name to index }.toMap()
        val values = fields.map { it.second }
        return columnIndex to values
    }

    private fun validRow(
        title: String = "The Wandering Cartographer",
        author: String = "",
        additionalAuthors: String = "",
        isbn13: String = "=\"9780593135204\"",
        isbn: String = "",
        numberOfPages: String = "312",
        binding: String = "Hardcover",
        yearPublished: String = "2020",
        originalPublicationYear: String = "1998",
        exclusiveShelf: String = "read",
        dateRead: String = "2023/05/12",
        dateAdded: String = "2022/11/03",
    ): Pair<Map<String, Int>, List<String>> =
        rowOf(
            GoodreadsColumns.TITLE to title,
            GoodreadsColumns.AUTHOR to author,
            GoodreadsColumns.ADDITIONAL_AUTHORS to additionalAuthors,
            GoodreadsColumns.ISBN13 to isbn13,
            GoodreadsColumns.ISBN to isbn,
            GoodreadsColumns.NUMBER_OF_PAGES to numberOfPages,
            GoodreadsColumns.BINDING to binding,
            GoodreadsColumns.YEAR_PUBLISHED to yearPublished,
            GoodreadsColumns.ORIGINAL_PUBLICATION_YEAR to originalPublicationYear,
            GoodreadsColumns.EXCLUSIVE_SHELF to exclusiveShelf,
            GoodreadsColumns.DATE_READ to dateRead,
            GoodreadsColumns.DATE_ADDED to dateAdded,
        )

    // ---- happy path / title-only ---------------------------------------------------------------

    @Test
    fun parseRow_happyPath_parsesEveryField() {
        val (columnIndex, row) = validRow()
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row)
        assertIs<LibraryRowParseResult.Parsed>(result)
        val parsed = result.row
        assertEquals("The Wandering Cartographer", parsed.title)
        assertEquals(1998, parsed.releaseYear) // Original Publication Year preferred
        assertEquals("9780593135204", parsed.book.isbn)
        assertEquals(BookFormat.HARDCOVER, parsed.book.format)
        assertEquals(312, parsed.book.totalPages)
        assertEquals(ReadingStatus.FINISHED, parsed.book.status)
        assertEquals(TrackingMode.PAGES, parsed.book.trackingMode)
        assertEquals(listOf(IdentifierProvider.ISBN to "9780593135204"), parsed.externalIdentifiers)
        assertNull(parsed.purchasePrice)
        assertNull(parsed.coverImageHash)
    }

    @Test
    fun parseRow_onlyTitleColumnPresent_stillImports() {
        val (columnIndex, row) = rowOf(GoodreadsColumns.TITLE to "Bare Minimum Book")
        val result =
            GoodreadsCsvImporter.parseRow(
                columnIndex,
                row,
                clock = fixedClock(Instant.fromEpochMilliseconds(1_000)),
            )
        assertIs<LibraryRowParseResult.Parsed>(result)
        val parsed = result.row
        assertEquals("Bare Minimum Book", parsed.title)
        assertNull(parsed.releaseYear)
        assertNull(parsed.book.isbn)
        assertEquals(BookFormat.PHYSICAL, parsed.book.format)
        assertNull(parsed.book.totalPages)
        assertEquals(ReadingStatus.TO_READ, parsed.book.status)
        assertNull(parsed.book.finishedAt)
        assertEquals(TrackingMode.PERCENT, parsed.book.trackingMode)
        assertEquals(emptyList(), parsed.externalIdentifiers)
        // Date Added was missing entirely -- falls back to the injected clock.
        assertEquals(Instant.fromEpochMilliseconds(1_000), parsed.createdAt)
    }

    @Test
    fun parseRow_blankTitle_isRejected() {
        val (columnIndex, row) = validRow(title = "")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row)
        assertIs<LibraryRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Title"))
    }

    // ---- Author + Additional Authors -> authors (schema v5, ROADMAP Task 9 Phase A) ------------

    @Test
    fun parseRow_primaryAuthorOnly_mapsToAuthors() {
        val (columnIndex, row) = validRow(author = "Ann Sample Author")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertEquals("Ann Sample Author", result.row.book.authors)
    }

    @Test
    fun parseRow_primaryAuthorPlusAdditionalAuthors_combinedInOrder_reJoinedWithAppSeparator() {
        // Goodreads' own "Additional Authors" separator (",") must not leak into the stored form --
        // it's re-joined with this app's "; " (BookDetailsEntity.AUTHOR_SEPARATOR) instead.
        val (columnIndex, row) =
            validRow(
                author = "Ann Sample Author",
                additionalAuthors = "B. Other Author, C. Third Author",
            )
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertEquals("Ann Sample Author; B. Other Author; C. Third Author", result.row.book.authors)
    }

    @Test
    fun parseRow_additionalAuthorsWithExtraWhitespace_eachNameTrimmed() {
        val (columnIndex, row) =
            validRow(
                author = "Ann Sample Author",
                additionalAuthors = "  B. Other Author ,   C. Third Author  ",
            )
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertEquals("Ann Sample Author; B. Other Author; C. Third Author", result.row.book.authors)
    }

    @Test
    fun parseRow_noAuthorColumnsPresentOrBlank_authorsIsNull() {
        val (columnIndex, row) = validRow(author = "", additionalAuthors = "")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertNull(result.row.book.authors)
    }

    @Test
    fun parseRow_onlyAdditionalAuthorsPresent_primaryBlank_stillCombines() {
        // An unusual but not impossible input (blank primary author, only co-authors recorded) --
        // must not produce a leading empty/blank entry.
        val (columnIndex, row) = validRow(author = "", additionalAuthors = "B. Other Author")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertEquals("B. Other Author", result.row.book.authors)
    }

    // ---- ISBN Excel-armor stripping ------------------------------------------------------------

    @Test
    fun parseRow_isbnArmorStripped() {
        val (columnIndex, row) = validRow(isbn13 = "=\"9780593135204\"", isbn = "")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals("9780593135204", result.row.book.isbn)
    }

    @Test
    fun parseRow_emptyIsbnArmor_treatedAsBlank() {
        val (columnIndex, row) = validRow(isbn13 = "=\"\"", isbn = "=\"\"")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertNull(result.row.book.isbn)
        assertEquals(emptyList(), result.row.externalIdentifiers)
    }

    @Test
    fun parseRow_isbn13PreferredOverIsbn10() {
        val (columnIndex, row) = validRow(isbn13 = "=\"9780593135204\"", isbn = "=\"0593135202\"")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals("9780593135204", result.row.book.isbn)
    }

    @Test
    fun parseRow_isbn13BlankFallsBackToIsbn10() {
        val (columnIndex, row) = validRow(isbn13 = "=\"\"", isbn = "=\"0593135202\"")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals("0593135202", result.row.book.isbn)
    }

    @Test
    fun parseRow_unarmoredIsbn_passesThroughUnchanged() {
        // A hand-edited or non-Goodreads-generated cell with no armor at all.
        val (columnIndex, row) = validRow(isbn13 = "9780593135204", isbn = "")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertEquals("9780593135204", result.row.book.isbn)
    }

    // ---- Binding -> BookFormat -------------------------------------------------------------------

    @Test
    fun parseRow_bindingHardcover_mapsToHardcover() {
        val (columnIndex, row) = validRow(binding = "Hardcover")
        assertEquals(
            BookFormat.HARDCOVER,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.format,
        )
    }

    @Test
    fun parseRow_bindingPaperback_mapsToPaperback() {
        val (columnIndex, row) = validRow(binding = "Paperback")
        assertEquals(
            BookFormat.PAPERBACK,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.format,
        )
    }

    @Test
    fun parseRow_bindingMassMarketPaperback_mapsToPaperback() {
        val (columnIndex, row) = validRow(binding = "Mass Market Paperback")
        assertEquals(
            BookFormat.PAPERBACK,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.format,
        )
    }

    @Test
    fun parseRow_bindingKindleEdition_mapsToEbook() {
        val (columnIndex, row) = validRow(binding = "Kindle Edition")
        assertEquals(
            BookFormat.EBOOK,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.format,
        )
    }

    @Test
    fun parseRow_bindingAudiobook_mapsToAudiobook() {
        val (columnIndex, row) = validRow(binding = "Audiobook")
        assertEquals(
            BookFormat.AUDIOBOOK,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.format,
        )
    }

    @Test
    fun parseRow_bindingUnrecognized_fallsBackToPhysical() {
        val (columnIndex, row) = validRow(binding = "Cuneiform Tablet")
        assertEquals(
            BookFormat.PHYSICAL,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.format,
        )
    }

    @Test
    fun parseRow_bindingBlank_fallsBackToPhysical() {
        val (columnIndex, row) = validRow(binding = "")
        assertEquals(
            BookFormat.PHYSICAL,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.format,
        )
    }

    // ---- Exclusive Shelf -> ReadingStatus ---------------------------------------------------------

    @Test
    fun parseRow_shelfRead_mapsToFinished() {
        val (columnIndex, row) = validRow(exclusiveShelf = "read")
        assertEquals(
            ReadingStatus.FINISHED,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.status,
        )
    }

    @Test
    fun parseRow_shelfCurrentlyReading_mapsToReading() {
        val (columnIndex, row) = validRow(exclusiveShelf = "currently-reading")
        assertEquals(
            ReadingStatus.READING,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.status,
        )
    }

    @Test
    fun parseRow_shelfToRead_mapsToToRead() {
        val (columnIndex, row) = validRow(exclusiveShelf = "to-read")
        assertEquals(
            ReadingStatus.TO_READ,
            (GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed).row.book.status,
        )
    }

    @Test
    fun parseRow_shelfUnrecognizedOrBlank_fallsBackToToRead_neverDnf() {
        val (blankColumnIndex, blankRow) = validRow(exclusiveShelf = "")
        assertEquals(
            ReadingStatus.TO_READ,
            (GoodreadsCsvImporter.parseRow(blankColumnIndex, blankRow) as LibraryRowParseResult.Parsed).row.book.status,
        )

        val (customColumnIndex, customRow) = validRow(exclusiveShelf = "some-custom-shelf")
        val result = GoodreadsCsvImporter.parseRow(customColumnIndex, customRow) as LibraryRowParseResult.Parsed
        assertEquals(ReadingStatus.TO_READ, result.row.book.status)
        // Nothing Goodreads' Exclusive Shelf can carry ever becomes DNF (see mapExclusiveShelf's KDoc).
        assertTrue(result.row.book.status != ReadingStatus.DNF)
    }

    // ---- Date Read -> finishedAt ------------------------------------------------------------------

    @Test
    fun parseRow_dateRead_mapsToFinishedAt_whenShelfIsRead() {
        val (columnIndex, row) = validRow(exclusiveShelf = "read", dateRead = "2023/05/12")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertEquals(2023, result.row.book.finishedAt?.let { instantYear(it) })
    }

    @Test
    fun parseRow_dateReadBlank_yieldsNullFinishedAt() {
        val (columnIndex, row) = validRow(exclusiveShelf = "read", dateRead = "")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertNull(result.row.book.finishedAt)
    }

    @Test
    fun parseRow_dateReadInvalidCalendarDate_yieldsNullFinishedAt_rowStillImports() {
        // Not a real calendar date -- tolerated as "can't confidently parse this", not a row
        // rejection (this phase's tolerance brief; see parseGoodreadsDate's KDoc).
        val (columnIndex, row) = validRow(exclusiveShelf = "read", dateRead = "2023/02/30")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row)
        assertIs<LibraryRowParseResult.Parsed>(result)
        assertNull(result.row.book.finishedAt)
    }

    @Test
    fun parseRow_dateReadPresentButShelfNotRead_finishedAtStaysNull() {
        // A stray Date Read on a currently-reading row (e.g. a re-shelve) must not fabricate a
        // finish date that contradicts the status -- see buildRow's KDoc.
        val (columnIndex, row) = validRow(exclusiveShelf = "currently-reading", dateRead = "2023/05/12")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertNull(result.row.book.finishedAt)
    }

    // ---- Date Added -> createdAt ------------------------------------------------------------------

    @Test
    fun parseRow_dateAddedBlank_fallsBackToClock() {
        val fixed = Instant.fromEpochMilliseconds(42_000)
        val (columnIndex, row) = validRow(dateAdded = "")
        val result =
            GoodreadsCsvImporter.parseRow(
                columnIndex,
                row,
                clock = fixedClock(fixed),
            ) as LibraryRowParseResult.Parsed
        assertEquals(fixed, result.row.createdAt)
    }

    // ---- Year Published vs Original Publication Year ------------------------------------------

    @Test
    fun parseRow_originalPublicationYearPreferredOverYearPublished() {
        val (columnIndex, row) = validRow(yearPublished = "2026", originalPublicationYear = "1926")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertEquals(1926, result.row.releaseYear, "the work's original year, not a later reprint's edition year")
    }

    @Test
    fun parseRow_originalPublicationYearBlank_fallsBackToYearPublished() {
        val (columnIndex, row) = validRow(yearPublished = "2020", originalPublicationYear = "")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertEquals(2020, result.row.releaseYear)
    }

    @Test
    fun parseRow_bothYearColumnsBlank_releaseYearIsNull() {
        val (columnIndex, row) = validRow(yearPublished = "", originalPublicationYear = "")
        val result = GoodreadsCsvImporter.parseRow(columnIndex, row) as LibraryRowParseResult.Parsed
        assertNull(result.row.releaseYear)
    }

    private fun instantYear(instant: Instant): Int = instant.toLocalDateTime(TimeZone.currentSystemDefault()).year
}
