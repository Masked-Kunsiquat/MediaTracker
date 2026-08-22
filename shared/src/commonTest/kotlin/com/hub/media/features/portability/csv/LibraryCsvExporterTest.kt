package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests [LibraryCsvExporter] (ROADMAP Task 8 Phase A). Consolidated and generalized per Issue #67.
 */
class LibraryCsvExporterTest {
    // Fixed, well-known epoch millis (avoids depending on any Instant string-parsing API existing
    // on this Kotlin version) -- ISO-8601-ness of the exported form is verified structurally via
    // ISO_INSTANT_REGEX below, and exact-value equality is checked against createdAt.toString()
    // itself, which is exactly the "one documented, unambiguous textual form" this phase's task
    // brief requires (kotlin.time.Instant.toString() -> ISO-8601 UTC, never locale-dependent).
    private val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val finishedAt = Instant.fromEpochMilliseconds(1_700_086_400_000)

    @Test
    fun export_emptyLibrary_producesOnlyHeaderRow() {
        val csv = LibraryCsvExporter.export(emptyList(), emptyMap())
        assertEquals(CsvUtil.buildLine(LibraryCsvExporter.HEADER), csv)
    }

    @Test
    fun export_headerRow_includesSchemaVersionColumnFirst() {
        val csv = LibraryCsvExporter.export(emptyList(), emptyMap())
        val header = csv.substringBefore(CsvUtil.LINE_ENDING)
        assertEquals(CSV_SCHEMA_VERSION_COLUMN, header.split(",").first())
    }

    @Test
    fun export_everyDataRow_startsWithTheSchemaVersionValue() {
        val media = mediaWithDetails(mediaId = "m1")
        val csv = LibraryCsvExporter.export(listOf(media), emptyMap())
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]
        assertEquals(CSV_SCHEMA_VERSION.toString(), dataLine.split(",").first())
    }

    @Test
    fun export_fullyPopulatedBook_includesEveryFieldInOrder() {
        val mediaItem =
            MediaItemEntity(
                id = "media-1",
                type = MediaType.BOOK,
                title = "A Sample Title",
                releaseYear = 2019,
                purchasePrice = 14.99,
                createdAt = createdAt,
                coverImageHash = "deadbeef.jpg",
            )
        val details =
            BookDetailsEntity(
                mediaId = "media-1",
                isbn = "9780000000001",
                format = BookFormat.HARDCOVER,
                totalPages = 342,
                status = ReadingStatus.FINISHED,
                finishedAt = finishedAt,
                trackingMode = TrackingMode.PAGES,
                authors = "Ann Sample Author",
            )
        val identifiers =
            listOf(
                ExternalIdentifierEntity("media-1", IdentifierProvider.ISBN, "9780000000001"),
                ExternalIdentifierEntity("media-1", IdentifierProvider.OPEN_LIBRARY, "OL999999M"),
            )

        val csv =
            LibraryCsvExporter.export(
                mediaItems = listOf(MediaWithDetails.Book(mediaItem, details)),
                identifiersByMediaId = mapOf("media-1" to identifiers),
            )
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]
        val fields = dataLine.split(",")

        assertEquals(CSV_SCHEMA_VERSION.toString(), fields[0])
        assertEquals("media-1", fields[1])
        assertEquals("BOOK", fields[2])
        assertEquals("A Sample Title", fields[3])
        assertEquals("Ann Sample Author", fields[4])
        assertEquals("2019", fields[5])
        assertEquals("14.99", fields[6])
        assertEquals(createdAt.toString(), fields[7])
        assertTrue(ISO_INSTANT_REGEX.matches(fields[7]), "created_at should be ISO-8601 UTC: ${fields[7]}")
        assertEquals("deadbeef.jpg", fields[8])
        assertEquals("9780000000001", fields[9])
        assertEquals("HARDCOVER", fields[10])
        assertEquals("342", fields[11])
        assertEquals("FINISHED", fields[12])
        assertEquals(finishedAt.toString(), fields[13])
        assertTrue(ISO_INSTANT_REGEX.matches(fields[13]), "finished_at should be ISO-8601 UTC: ${fields[13]}")
        assertEquals("PAGES", fields[14])
        assertEquals("ISBN:9780000000001|OPEN_LIBRARY:OL999999M", fields[15])
    }

    @Test
    fun export_minimalBook_nullableFieldsExportAsEmpty() {
        val mediaItem =
            MediaItemEntity(
                id = "media-2",
                type = MediaType.BOOK,
                title = "Minimal Book",
                releaseYear = null,
                purchasePrice = null,
                createdAt = createdAt,
                coverImageHash = null,
            )
        val details =
            BookDetailsEntity(
                mediaId = "media-2",
                isbn = null,
                format = BookFormat.EBOOK,
                totalPages = null,
                status = ReadingStatus.TO_READ,
                finishedAt = null,
                trackingMode = TrackingMode.PERCENT,
            )

        val csv = LibraryCsvExporter.export(listOf(MediaWithDetails.Book(mediaItem, details)), emptyMap())
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]
        val fields = dataLine.split(",")

        assertEquals("", fields[4]) // authors
        assertEquals("", fields[5]) // release_year
        assertEquals("", fields[6]) // purchase_price
        assertEquals("", fields[8]) // cover_image_hash
        assertEquals("", fields[9]) // isbn
        assertEquals("", fields[11]) // total_pages
        assertEquals("", fields[13]) // finished_at
        assertEquals("", fields[15]) // external_identifiers
        assertEquals("", fields[16]) // runtime_minutes -- a book has none
        assertEquals("", fields[17]) // watch_status
        assertEquals("", fields[18]) // watched_at
    }

    // ---- movie columns (CSV v3, ROADMAP Task 13 Phase B) -------------------------------------

    @Test
    fun export_fullyPopulatedMovie_writesTheMovieColumnsAndLeavesTheBookOnesEmpty() {
        val mediaItem =
            MediaItemEntity(
                id = "media-3",
                type = MediaType.MOVIE,
                title = "Arrival",
                releaseYear = 2016,
                purchasePrice = 9.99,
                createdAt = createdAt,
                coverImageHash = "poster.jpg",
            )
        val details =
            MovieDetailsEntity(
                mediaId = "media-3",
                runtimeMinutes = 116,
                status = WatchStatus.WATCHED,
                watchedAt = finishedAt,
            )

        val csv = LibraryCsvExporter.export(listOf(MediaWithDetails.Movie(mediaItem, details)), emptyMap())
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals("MOVIE", fields[2])
        assertEquals("Arrival", fields[3])
        assertEquals("2016", fields[5])
        assertEquals("9.99", fields[6])
        // The book-shaped columns stay empty rather than borrowing a movie's values: `status` is a
        // ReadingStatus column, and a WATCHED film written into it would read back as an
        // unrecognized reading status.
        assertEquals("", fields[4], "authors")
        assertEquals("", fields[9], "isbn")
        assertEquals("", fields[10], "format")
        assertEquals("", fields[11], "total_pages")
        assertEquals("", fields[12], "status")
        assertEquals("", fields[13], "finished_at")
        assertEquals("", fields[14], "tracking_mode")

        assertEquals("116", fields[16])
        assertEquals("WATCHED", fields[17])
        assertEquals(finishedAt.toString(), fields[18])
        assertTrue(ISO_INSTANT_REGEX.matches(fields[18]), "watched_at should be ISO-8601 UTC: ${fields[18]}")
    }

    @Test
    fun export_movieWithUnknownRuntimeAndNoWatchDate_exportsThoseAsEmpty() {
        val mediaItem =
            MediaItemEntity(
                id = "media-4",
                type = MediaType.MOVIE,
                title = "Unseen",
                releaseYear = null,
                purchasePrice = null,
                createdAt = createdAt,
                coverImageHash = null,
            )
        val details = MovieDetailsEntity(mediaId = "media-4", status = WatchStatus.WATCHLIST)

        val csv = LibraryCsvExporter.export(listOf(MediaWithDetails.Movie(mediaItem, details)), emptyMap())
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals("", fields[16], "an unknown runtime is empty, never 0")
        assertEquals("WATCHLIST", fields[17])
        assertEquals("", fields[18])
    }

    @Test
    fun export_missingMovieDetailsRow_leavesMovieColumnsEmptyWithoutCrashing() {
        val mediaItem =
            MediaItemEntity(
                id = "media-5",
                type = MediaType.MOVIE,
                title = "Orphaned",
                releaseYear = null,
                purchasePrice = null,
                createdAt = createdAt,
                coverImageHash = null,
            )

        val csv = LibraryCsvExporter.export(listOf(MediaWithDetails.Movie(mediaItem, details = null)), emptyMap())
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals("Orphaned", fields[3])
        assertEquals("", fields[16])
        assertEquals("", fields[17])
        assertEquals("", fields[18])
    }

    @Test
    fun export_missingBookDetailsRow_leavesBookDetailsColumnsEmptyWithoutCrashing() {
        // Data-integrity edge case documented on BookRepository.observeBookDetail: a MediaItemEntity
        // can (in theory) have no BookDetailsEntity row.
        val mediaItem =
            MediaItemEntity(
                id = "media-3",
                type = MediaType.BOOK,
                title = "Orphaned Media Item",
                releaseYear = null,
                purchasePrice = null,
                createdAt = createdAt,
                coverImageHash = null,
            )
        val csv = LibraryCsvExporter.export(listOf(MediaWithDetails.Book(mediaItem, details = null)), emptyMap())
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]
        val fields = dataLine.split(",")

        assertEquals("", fields[4]) // authors
        assertEquals("", fields[9]) // isbn
        assertEquals("", fields[10]) // format
        assertEquals("", fields[11]) // total_pages
        assertEquals("", fields[12]) // status
        assertEquals("", fields[13]) // finished_at
        assertEquals("", fields[14]) // tracking_mode
    }

    @Test
    fun export_titleContainingCommaAndQuotes_isEscapedAndStillParsesAsOneField() {
        val mediaItem =
            MediaItemEntity(
                id = "media-4",
                type = MediaType.BOOK,
                title = "The \"Best\" Book, Ever",
                releaseYear = null,
                purchasePrice = null,
                createdAt = createdAt,
                coverImageHash = null,
            )
        val details =
            BookDetailsEntity(mediaId = "media-4", isbn = null, format = BookFormat.PHYSICAL, totalPages = null)

        val csv = LibraryCsvExporter.export(listOf(MediaWithDetails.Book(mediaItem, details)), emptyMap())
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]

        assertTrue(dataLine.contains("\"The \"\"Best\"\" Book, Ever\""))
    }

    @Test
    fun export_bookWithNoExternalIdentifiers_producesEmptyIdentifiersField() {
        val media = mediaWithDetails(mediaId = "media-5")
        val csv = LibraryCsvExporter.export(listOf(media), identifiersByMediaId = emptyMap())
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]
        assertEquals("", dataLine.split(",").last())
    }

    @Test
    fun export_enumFields_exportByName() {
        val media =
            mediaWithDetails(
                mediaId = "media-6",
                format = BookFormat.PAPERBACK,
                status = ReadingStatus.DNF,
                trackingMode = TrackingMode.PERCENT,
            )
        val csv = LibraryCsvExporter.export(listOf(media), emptyMap())
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]
        val fields = dataLine.split(",")
        assertEquals(MediaType.BOOK.name, fields[2])
        assertEquals(BookFormat.PAPERBACK.name, fields[10])
        assertEquals(ReadingStatus.DNF.name, fields[12])
        assertEquals(TrackingMode.PERCENT.name, fields[14])
    }

    private fun mediaWithDetails(
        mediaId: String,
        format: BookFormat = BookFormat.PHYSICAL,
        status: ReadingStatus = ReadingStatus.TO_READ,
        trackingMode: TrackingMode = TrackingMode.PAGES,
    ): MediaWithDetails =
        MediaWithDetails.Book(
            item =
                MediaItemEntity(
                    id = mediaId,
                    type = MediaType.BOOK,
                    title = "Sample Title $mediaId",
                    releaseYear = 2020,
                    purchasePrice = 9.99,
                    createdAt = createdAt,
                    coverImageHash = null,
                ),
            details =
                BookDetailsEntity(
                    mediaId = mediaId,
                    isbn = "9780000000000",
                    format = format,
                    totalPages = 200,
                    status = status,
                    trackingMode = trackingMode,
                ),
        )

    private companion object {
        /** Structural check for ISO-8601 UTC ("extended" form, kotlin.time.Instant.toString()'s shape). */
        val ISO_INSTANT_REGEX = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z""")
    }
}
