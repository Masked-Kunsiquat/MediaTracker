package com.hub.media.features.portability.domain

import com.hub.media.core.database.dao.ImportBookInsert
import com.hub.media.core.database.dao.ImportBookUpdate
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.BookWithDetails
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.portability.data.ImportWriteRepository
import com.hub.media.features.portability.csv.CsvTableResult
import com.hub.media.features.portability.csv.LibraryCsvExporter
import com.hub.media.features.portability.csv.LibraryCsvImporter
import com.hub.media.features.portability.csv.LibraryRowParseResult
import com.hub.media.features.portability.csv.ParsedLibraryRow
import com.hub.media.features.portability.csv.ParsedSessionRow
import com.hub.media.features.portability.csv.ReadingLogCsvExporter
import com.hub.media.features.portability.csv.ReadingLogCsvImporter
import com.hub.media.features.portability.csv.SessionRowParseResult
import com.hub.media.features.portability.csv.CsvTableReader
import kotlinx.coroutines.flow.first

/** See [ImportDataUseCase.execute]. */
public interface ImportUseCase {
    public suspend fun execute(
        libraryCsv: String?,
        readingLogsCsv: String?,
        duplicatePolicy: DuplicatePolicy,
    ): Resource<ImportSummary>
}

/**
 * End-to-end "import my data from CSV" workflow (ROADMAP Task 8 Phase B) -- the harder half of
 * data portability: [ExportDataUseCase] can only fail by producing a bad file, but this writes to
 * the user's real library, which has no cloud copy (AGENTS.md §1). Every design choice below
 * favors "refuse and explain" over "guess and proceed."
 *
 * ### Two failure classes, two different responses
 * - **Structural** problems (empty file, wrong header, wrong column count, an unterminated quote,
 *   a `csv_schema_version` newer than this app understands) mean the *file itself* cannot be
 *   trusted -- [CsvTableReader] catches these and this method refuses the **whole** import before
 *   writing anything, returning [Resource.Error].
 * - **Semantic** problems in one row (a blank title, an out-of-range release year, an unknown
 *   enum value, a malformed `external_identifiers` segment, a session referencing an unknown
 *   book) mean that *one row* is bad, not the file -- these are collected as
 *   [ImportRejection]s and skipped, while every other valid row still imports. Silently dropping
 *   a row without reporting it is not acceptable (this phase's brief); silently aborting an
 *   entire multi-hundred-row import over one bad line would be disproportionate.
 *
 * ### Duplicate matching precedence (for a `library_export.csv` row)
 * 1. **`media_id`** exact match against an existing book -- the common case for a re-import into
 *    the *same* library (e.g. periodic backup-and-restore testing), since `media_id` is the row's
 *    own database primary key round-tripping unchanged.
 * 2. **`isbn`** exact match (case-sensitive, exact string) -- the fallback for importing into a
 *    *different* / fresh library (a restore onto a new install, or a Goodreads-style import) where
 *    `media_id` was never shared. Two distinct physical copies of the same book legitimately share
 *    an ISBN; this deliberately treats them as "the same book" for merge purposes (an accepted
 *    tradeoff -- see ROADMAP Task 8's Goodreads bullet, which depends on exactly this).
 * 3. **`title` + `release_year`** (case-insensitive title, exact year match) -- last-resort
 *    fallback for a book with no ISBN on record. **Known weakness**: this schema has no author
 *    column, so two different books sharing a title and release year (a common title, a reboot/
 *    revival, etc.) would incorrectly match here. Accepted because it only ever applies when the
 *    stronger ISBN tier already failed to match, and the alternative (never matching at all) means
 *    every ISBN-less duplicate is treated as brand new every re-import.
 *
 * A row matching none of the three is always a fresh insert, regardless of [DuplicatePolicy].
 *
 * ### What each [DuplicatePolicy] does, field by field
 * - [DuplicatePolicy.SKIP]: the existing row is left completely untouched.
 * - [DuplicatePolicy.REPLACE]: every field this importer manages (title, releaseYear,
 *   purchasePrice, isbn, format, totalPages, status, finishedAt, trackingMode, external
 *   identifiers) is overwritten with the imported row's value. **`createdAt` and
 *   `coverImageHash` are deliberately never touched by REPLACE** on an existing book: `createdAt`
 *   is "when this device first learned about the book," which a metadata correction shouldn't
 *   silently rewrite, and CSV import carries no image bytes at all (see the cover-image note
 *   below) -- a fresh INSERT (brand new to this device) does use the imported `createdAt`, since
 *   that *is* the correct restore semantics for a book this device has never seen before.
 * - [DuplicatePolicy.MERGE]: only backfills fields the existing row left null/blank
 *   (releaseYear, purchasePrice, isbn, totalPages) and adds any external identifier for a
 *   provider the book didn't already have. Title, format, status, finishedAt, trackingMode,
 *   createdAt, and coverImageHash are never touched by merge -- they're either identity fields or
 *   user-owned facts about *this* device's copy that a re-import shouldn't silently overwrite.
 *   This is deliberately narrow today (no column exists yet for the Goodreads-import fields
 *   ROADMAP Task 8 mentions -- shelves, read counts, ratings), but the mechanism is exactly what
 *   that future phase needs: a later re-import backfilling newly-added nullable columns without
 *   ever clobbering a value already recorded here.
 *
 * ### Cover images are never restored by CSV import
 * `cover_image_hash` is parsed from the file (for completeness) but never written by this use
 * case, on insert or update: the CSV carries only the hash *string*, never the image bytes it
 * names, so writing a foreign device's hash here would silently break the book's cover display
 * (the file `<hash>.jpg` almost certainly doesn't exist in this device's
 * [com.hub.media.core.storage.LocalImageStorageManager] storage) without the DB itself looking
 * corrupt. Binary asset restore is the `.sqlite` backup/restore phase's job, not CSV's.
 *
 * ### Ordering and orphan sessions
 * `reading_logs_export.csv` rows reference a book via `media_id`. This use case always resolves
 * every library row (building the complete set of "known" media ids -- every pre-existing book,
 * plus every book this same import inserts or matches) *before* looking at any session row, so a
 * session can never be wrongly orphaned just because its book happened to be processed later in
 * the same import. A session whose `media_id` isn't in that known set (the book exists in neither
 * the current database nor the library file being imported alongside it) is **skipped, and
 * reported** as an [ImportRejection] -- not silently dropped, and not a reason to fail the whole
 * import. Failing the entire import over one dangling session would be disproportionate to a
 * single data-quality issue (e.g. the user only has a reading-logs export without its matching
 * library export, or that session's book row was itself rejected above); skip-with-report keeps
 * every other valid row importing while still surfacing the problem.
 *
 * Sessions themselves are matched for duplicates by `session_id` (their own primary key) using the
 * same [DuplicatePolicy] as books: MERGE backfills only `durationSeconds`/`deltaPages`/`notes`
 * when the existing row left them null/blank; timestamps and positions are the session's identity
 * and are never touched by merge.
 *
 * ### All-or-nothing
 * Every insert/update this use case decides on is queued, not written immediately, then applied in
 * exactly one call to [ImportWriteRepository.importAtomically] -- a single database transaction
 * (see [com.hub.media.core.database.dao.ImportWriteDao.importAtomically]). If that call fails for
 * any reason, nothing it touched is left applied; this use case reports the failure via
 * [Resource.Error] rather than a summary, since no summary would be accurate.
 *
 * @param bookRepository Source of the current library snapshot and destination-agnostic duplicate
 *   matching data.
 * @param readingSessionRepository Source of the current session snapshot.
 * @param importWriteRepository Applies the resolved plan in one transaction.
 */
public class ImportDataUseCase(
    private val bookRepository: BookRepository,
    private val readingSessionRepository: ReadingSessionRepository,
    private val importWriteRepository: ImportWriteRepository,
) : ImportUseCase {

    public override suspend fun execute(
        libraryCsv: String?,
        readingLogsCsv: String?,
        duplicatePolicy: DuplicatePolicy,
    ): Resource<ImportSummary> {
        if (libraryCsv == null && readingLogsCsv == null) {
            return Resource.Error("Nothing to import -- no file was selected.")
        }

        val libraryDataRows = if (libraryCsv != null) {
            when (val table = CsvTableReader.read(libraryCsv, LibraryCsvExporter.HEADER)) {
                is CsvTableResult.Failure -> return Resource.Error("library_export.csv: ${table.message}")
                is CsvTableResult.Success -> table.rows
            }
        } else {
            emptyList()
        }

        val sessionDataRows = if (readingLogsCsv != null) {
            when (val table = CsvTableReader.read(readingLogsCsv, ReadingLogCsvExporter.HEADER)) {
                is CsvTableResult.Failure -> return Resource.Error("reading_logs_export.csv: ${table.message}")
                is CsvTableResult.Success -> table.rows
            }
        } else {
            emptyList()
        }

        return try {
            val existingBooks = bookRepository.observeAllBooksWithDetails().first()
            val existingIdentifiersByMediaId = bookRepository.observeAllExternalIdentifiers().first().groupBy { it.mediaId }
            val existingSessionsById = readingSessionRepository.observeAllSessions().first().associateBy { it.id }

            val byMediaId = existingBooks.associateBy { it.mediaItem.id }.toMutableMap()
            val byIsbn = existingBooks
                .mapNotNull { book -> book.details?.isbn?.takeIf { it.isNotBlank() }?.let { it to book } }
                .toMap()
                .toMutableMap()
            val byTitleYear = existingBooks
                .associateBy { titleYearKey(it.mediaItem.title, it.mediaItem.releaseYear) }
                .toMutableMap()
            val knownMediaIds = existingBooks.mapTo(mutableSetOf()) { it.mediaItem.id }

            val rejections = mutableListOf<ImportRejection>()
            val bookInserts = mutableListOf<ImportBookInsert>()
            val bookUpdates = mutableListOf<ImportBookUpdate>()
            var booksImported = 0
            var booksSkipped = 0
            var booksMerged = 0
            var booksReplaced = 0

            libraryDataRows.forEachIndexed { index, rawRow ->
                val rowNumber = index + 2
                when (val parsed = LibraryCsvImporter.parseRow(rawRow)) {
                    is LibraryRowParseResult.Rejected ->
                        rejections += ImportRejection(ImportRowSource.BOOK, rowNumber, parsed.reason)

                    is LibraryRowParseResult.Parsed -> {
                        val row = parsed.row
                        val match = byMediaId[row.mediaId]
                            ?: row.isbn?.let(byIsbn::get)
                            ?: byTitleYear[titleYearKey(row.title, row.releaseYear)]

                        if (match == null) {
                            bookInserts += buildInsert(row)
                            booksImported++
                            knownMediaIds += row.mediaId
                        } else {
                            knownMediaIds += match.mediaItem.id
                            when (duplicatePolicy) {
                                DuplicatePolicy.SKIP -> booksSkipped++
                                DuplicatePolicy.REPLACE -> {
                                    bookUpdates += buildReplace(match, row)
                                    booksReplaced++
                                }
                                DuplicatePolicy.MERGE -> {
                                    val existingIdentifiers = existingIdentifiersByMediaId[match.mediaItem.id].orEmpty()
                                    bookUpdates += buildMerge(match, row, existingIdentifiers)
                                    booksMerged++
                                }
                            }
                        }
                    }
                }
            }

            val sessionInserts = mutableListOf<ReadingSessionEntity>()
            val sessionUpdates = mutableListOf<ReadingSessionEntity>()
            var sessionsImported = 0
            var sessionsSkipped = 0
            var sessionsMerged = 0
            var sessionsReplaced = 0

            sessionDataRows.forEachIndexed { index, rawRow ->
                val rowNumber = index + 2
                when (val parsed = ReadingLogCsvImporter.parseRow(rawRow)) {
                    is SessionRowParseResult.Rejected ->
                        rejections += ImportRejection(ImportRowSource.SESSION, rowNumber, parsed.reason)

                    is SessionRowParseResult.Parsed -> {
                        val row = parsed.row
                        if (row.mediaId !in knownMediaIds) {
                            rejections += ImportRejection(
                                ImportRowSource.SESSION,
                                rowNumber,
                                "media_id '${row.mediaId}' is not a known book (not already in your " +
                                    "library, and not present in the library file being imported) -- " +
                                    "session skipped",
                            )
                        } else {
                            val existing = existingSessionsById[row.sessionId]
                            if (existing == null) {
                                sessionInserts += toSessionEntity(row)
                                sessionsImported++
                            } else {
                                when (duplicatePolicy) {
                                    DuplicatePolicy.SKIP -> sessionsSkipped++
                                    DuplicatePolicy.REPLACE -> {
                                        sessionUpdates += toSessionEntity(row)
                                        sessionsReplaced++
                                    }
                                    DuplicatePolicy.MERGE -> {
                                        sessionUpdates += mergeSession(existing, row)
                                        sessionsMerged++
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val writeResult = importWriteRepository.importAtomically(
                bookInserts = bookInserts,
                bookUpdates = bookUpdates,
                sessionInserts = sessionInserts,
                sessionUpdates = sessionUpdates,
            )
            if (writeResult is Resource.Error) return writeResult

            Resource.Success(
                ImportSummary(
                    booksImported = booksImported,
                    booksSkipped = booksSkipped,
                    booksMerged = booksMerged,
                    booksReplaced = booksReplaced,
                    sessionsImported = sessionsImported,
                    sessionsSkipped = sessionsSkipped,
                    sessionsMerged = sessionsMerged,
                    sessionsReplaced = sessionsReplaced,
                    rejections = rejections,
                ),
            )
        } catch (e: Exception) {
            Resource.Error("Import failed: ${e.message ?: "Unknown error"}", e)
        }
    }

    private fun titleYearKey(title: String, releaseYear: Int?): String =
        "${title.trim().lowercase()}::${releaseYear ?: ""}"

    private fun buildInsert(row: ParsedLibraryRow): ImportBookInsert {
        val mediaItem = MediaItemEntity(
            id = row.mediaId,
            type = MediaType.BOOK,
            title = row.title,
            releaseYear = row.releaseYear,
            purchasePrice = row.purchasePrice,
            createdAt = row.createdAt,
            // Never restored from CSV -- see class KDoc's cover-image note.
            coverImageHash = null,
        )
        val details = BookDetailsEntity(
            mediaId = row.mediaId,
            isbn = row.isbn,
            format = row.format,
            totalPages = row.totalPages,
            status = row.status,
            finishedAt = row.finishedAt,
            trackingMode = row.trackingMode,
        )
        val identifiers = row.externalIdentifiers.map { (provider, id) ->
            ExternalIdentifierEntity(mediaId = row.mediaId, provider = provider, externalId = id)
        }
        return ImportBookInsert(mediaItem, details, identifiers)
    }

    private fun buildReplace(existing: BookWithDetails, row: ParsedLibraryRow): ImportBookUpdate {
        val mediaId = existing.mediaItem.id
        val mediaItem = existing.mediaItem.copy(
            title = row.title,
            releaseYear = row.releaseYear,
            purchasePrice = row.purchasePrice,
            // createdAt/coverImageHash intentionally untouched -- see class KDoc.
        )
        val details = BookDetailsEntity(
            mediaId = mediaId,
            isbn = row.isbn,
            format = row.format,
            totalPages = row.totalPages,
            status = row.status,
            finishedAt = row.finishedAt,
            trackingMode = row.trackingMode,
        )
        val identifiers = row.externalIdentifiers.map { (provider, id) ->
            ExternalIdentifierEntity(mediaId = mediaId, provider = provider, externalId = id)
        }
        return ImportBookUpdate(mediaItem, details, identifiers, replaceIdentifiers = true)
    }

    private fun buildMerge(
        existing: BookWithDetails,
        row: ParsedLibraryRow,
        existingIdentifiers: List<ExternalIdentifierEntity>,
    ): ImportBookUpdate {
        val mediaId = existing.mediaItem.id
        val mediaItem = existing.mediaItem.copy(
            releaseYear = existing.mediaItem.releaseYear ?: row.releaseYear,
            purchasePrice = existing.mediaItem.purchasePrice ?: row.purchasePrice,
            // title/createdAt/coverImageHash never backfilled -- identity/local-owned fields.
        )
        val existingDetails = existing.details
        val details = BookDetailsEntity(
            mediaId = mediaId,
            isbn = existingDetails?.isbn?.takeIf { it.isNotBlank() } ?: row.isbn,
            format = existingDetails?.format ?: row.format,
            totalPages = existingDetails?.totalPages ?: row.totalPages,
            status = existingDetails?.status ?: row.status,
            finishedAt = existingDetails?.finishedAt ?: row.finishedAt,
            trackingMode = existingDetails?.trackingMode ?: row.trackingMode,
        )
        val existingProviders = existingIdentifiers.map { it.provider }.toSet()
        val newIdentifiers = row.externalIdentifiers
            .filter { (provider, _) -> provider !in existingProviders }
            .map { (provider, id) -> ExternalIdentifierEntity(mediaId = mediaId, provider = provider, externalId = id) }
        return ImportBookUpdate(mediaItem, details, newIdentifiers, replaceIdentifiers = false)
    }

    private fun toSessionEntity(row: ParsedSessionRow): ReadingSessionEntity = ReadingSessionEntity(
        id = row.sessionId,
        mediaId = row.mediaId,
        timestampStart = row.timestampStart,
        timestampEnd = row.timestampEnd,
        durationSeconds = row.durationSeconds,
        startUnit = row.startUnit,
        endUnit = row.endUnit,
        deltaPages = row.deltaPages,
        notes = row.notes,
    )

    private fun mergeSession(existing: ReadingSessionEntity, row: ParsedSessionRow): ReadingSessionEntity =
        existing.copy(
            durationSeconds = existing.durationSeconds ?: row.durationSeconds,
            deltaPages = existing.deltaPages ?: row.deltaPages,
            notes = existing.notes?.takeIf { it.isNotBlank() } ?: row.notes,
            // timestamps/positions/mediaId are the session's identity -- merge never touches them.
        )
}
