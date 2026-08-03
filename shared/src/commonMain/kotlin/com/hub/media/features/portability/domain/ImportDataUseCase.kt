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
import com.hub.media.features.portability.goodreads.GoodreadsCsvImporter
import com.hub.media.features.portability.goodreads.GoodreadsCsvTableReader
import com.hub.media.features.portability.goodreads.GoodreadsCsvTableResult
import kotlinx.coroutines.flow.first

/** See [ImportDataUseCase.execute] and [ImportDataUseCase.executeGoodreads]. */
public interface ImportUseCase {
    public suspend fun execute(
        libraryCsv: String?,
        readingLogsCsv: String?,
        duplicatePolicy: DuplicatePolicy,
    ): Resource<ImportSummary>

    public suspend fun executeGoodreads(
        goodreadsCsv: String,
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
 * ### In-file duplicates (two rows in the same file sharing a key)
 * The three lookup tiers above are seeded from the pre-existing library, then **kept current as
 * each row resolves** so a later row is matched against every earlier row *from this same file*
 * too, not only against what was already in the database before the import started. This matters
 * because book inserts use `OnConflictStrategy.ABORT`: two rows sharing a `media_id` that were
 * both (wrongly) treated as fresh inserts would collide on the `media_items.id` primary key and
 * abort the *entire* atomic import, and two rows sharing an `isbn` would silently create two
 * separate books despite the ISBN tier's whole purpose being to recognize them as the same one.
 *
 * An in-file duplicate is a genuinely different situation from a collision with an existing
 * library book -- neither row has been written yet, so there is no "device-owned" copy to
 * protect -- but this deliberately reuses the *exact same* per-[DuplicatePolicy] field rules
 * below rather than inventing a fourth behavior, applied to "the row's resolved state so far in
 * this file" instead of "the row already in the database":
 * - [DuplicatePolicy.SKIP]: the earliest row in the file to claim a key wins; every later row
 *   sharing that key is skipped and left untouched, exactly as SKIP treats a pre-existing book.
 * - [DuplicatePolicy.REPLACE]: each later duplicate overwrites the managed fields again, so the
 *   **last** row in the file wins for those fields (createdAt/coverImageHash still only ever come
 *   from the original insert -- REPLACE never touches them, in-file or not).
 * - [DuplicatePolicy.MERGE]: each later duplicate only backfills fields still blank, so the
 *   **first** row in the file to set a field wins for that field -- the opposite of REPLACE.
 *
 * These resolutions are counted with the exact same imported/skipped/merged/replaced buckets
 * [ImportSummary] already has -- an in-file duplicate is not a new bucket, it is reported
 * identically to a duplicate against an existing book -- so the four counts always sum to the
 * number of valid (non-rejected) rows in the file, and the summary never implies more books were
 * freshly inserted than the file could actually have contributed.
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
 * every library row *before* looking at any session row, building the complete set of "known"
 * media ids: every pre-existing book, every book this same import freshly inserts, **and** --
 * for a library row that matched an existing (or in-file) book via the isbn or title+year tier
 * rather than an exact `media_id` match -- both the matched book's real id *and* the file's own
 * `media_id` for that row. Without that second id, a `reading_logs_export.csv` row using the
 * file's `media_id` (the only id it can possibly reference) would be wrongly rejected as an
 * orphan even though its book demonstrably was imported, just under a different id. A session can
 * never be wrongly orphaned just because its book happened to be processed later in the same
 * import, or matched under a different id than the file used. A session whose `media_id` isn't in
 * that known set (the book exists in neither the current database nor the library file being
 * imported alongside it) is **skipped, and reported** as an [ImportRejection] -- not silently
 * dropped, and not a reason to fail the whole import. Failing the entire import over one dangling
 * session would be disproportionate to a single data-quality issue (e.g. the user only has a
 * reading-logs export without its matching library export, or that session's book row was itself
 * rejected above); skip-with-report keeps every other valid row importing while still surfacing
 * the problem.
 *
 * Because a session can be "known" under an id that isn't the book's real id (the isbn/title+year
 * match case above), a fresh-insert or REPLACE session write always rewrites `mediaId` to the
 * book's actual resolved id before it is queued -- inserting or replacing with the file's raw
 * `media_id` in that case would either violate the `reading_sessions.mediaId` foreign key (no book
 * row exists under that id) or silently attach the session to an unrelated book that happens to
 * share that id. A MERGE onto an *existing* session never touches `mediaId` (see below) -- that
 * session already points at a real book from a previous import.
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

        val libraryParseResults = if (libraryCsv != null) {
            when (val table = CsvTableReader.read(libraryCsv, LibraryCsvExporter.HEADER)) {
                is CsvTableResult.Failure -> return Resource.Error("library_export.csv: ${table.message}")
                is CsvTableResult.Success -> table.rows.map { row -> LibraryCsvImporter.parseRow(row) }
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

            val bookResolution = resolveBookRows(existingBooks, existingIdentifiersByMediaId, libraryParseResults, duplicatePolicy)
            val rejections = bookResolution.rejections.toMutableList()
            val knownMediaIds = bookResolution.knownMediaIds.toMutableSet()

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
                            // The book this session's own media_id resolved to -- may differ from
                            // row.mediaId when the library row matched an existing/in-file book via
                            // the isbn or title+year tier (see class KDoc, "Ordering and orphan
                            // sessions"). Falls back to row.mediaId itself for a book that was
                            // already in the database and untouched by this import's library file.
                            val resolvedMediaId = bookResolution.resolvedMediaId[row.mediaId] ?: row.mediaId
                            val existing = existingSessionsById[row.sessionId]
                            if (existing == null) {
                                sessionInserts += toSessionEntity(row, resolvedMediaId)
                                sessionsImported++
                            } else {
                                when (duplicatePolicy) {
                                    DuplicatePolicy.SKIP -> sessionsSkipped++
                                    DuplicatePolicy.REPLACE -> {
                                        sessionUpdates += toSessionEntity(row, resolvedMediaId)
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
                bookInserts = bookResolution.inserts,
                bookUpdates = bookResolution.updates,
                sessionInserts = sessionInserts,
                sessionUpdates = sessionUpdates,
            )
            if (writeResult is Resource.Error) return writeResult

            Resource.Success(
                ImportSummary(
                    booksImported = bookResolution.imported,
                    booksSkipped = bookResolution.skipped,
                    booksMerged = bookResolution.merged,
                    booksReplaced = bookResolution.replaced,
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

    /**
     * Runs the Goodreads-import pipeline (ROADMAP Task 8 Phase D) -- deliberately reusing every
     * piece of [execute]'s machinery that isn't specific to *this app's own* CSV shape:
     * [resolveBookRows] (duplicate matching + insert/update construction, identical to [execute]'s
     * book half), [ImportWriteRepository.importAtomically] (the same single all-or-nothing
     * transaction), and [ImportRejection]/[ImportSummary] (the same per-row reporting shape). The
     * only Goodreads-specific pieces are the parsing layer
     * ([com.hub.media.features.portability.goodreads.GoodreadsCsvTableReader]/
     * [GoodreadsCsvImporter], which map a completely different file shape into the same
     * [ParsedLibraryRow]/[LibraryRowParseResult] types [execute] already produces from its own
     * format) and this method's glue -- exactly the "small generalization to accept a different row
     * source" this phase called for: [resolveBookRows] was extracted from [execute]'s inline logic
     * to operate on `List<LibraryRowParseResult>` rather than raw CSV text, so both callers can feed
     * it already-parsed rows regardless of which file format produced them.
     *
     * There is no reading-logs equivalent -- a Goodreads export carries no session-level history,
     * only per-book state (`Exclusive Shelf`, `Date Read`) -- so `sessionInserts`/`sessionUpdates`
     * are always empty here and every `sessions*` count on the returned [ImportSummary] is always
     * `0`.
     *
     * @return [Resource.Success] with [ImportSummary.notes] always containing
     *   [GoodreadsCsvImporter.NOT_IMPORTED_COLUMNS_NOTICE] -- see that constant's KDoc for why this
     *   notice must never be silently dropped (the user decision this phase implements). Structural
     *   file problems (no recognizable `Title` column, a row with the wrong field count, a CSV
     *   parse failure) refuse the whole import via [Resource.Error] before any write, exactly like
     *   [execute]'s structural failures.
     */
    public override suspend fun executeGoodreads(
        goodreadsCsv: String,
        duplicatePolicy: DuplicatePolicy,
    ): Resource<ImportSummary> {
        val parseResults = when (val table = GoodreadsCsvTableReader.read(goodreadsCsv)) {
            is GoodreadsCsvTableResult.Failure -> return Resource.Error("goodreads_library_export.csv: ${table.message}")
            is GoodreadsCsvTableResult.Success -> table.rows.map { row -> GoodreadsCsvImporter.parseRow(table.columnIndex, row) }
        }

        return try {
            val existingBooks = bookRepository.observeAllBooksWithDetails().first()
            val existingIdentifiersByMediaId = bookRepository.observeAllExternalIdentifiers().first().groupBy { it.mediaId }

            val bookResolution = resolveBookRows(existingBooks, existingIdentifiersByMediaId, parseResults, duplicatePolicy)

            val writeResult = importWriteRepository.importAtomically(
                bookInserts = bookResolution.inserts,
                bookUpdates = bookResolution.updates,
                sessionInserts = emptyList(),
                sessionUpdates = emptyList(),
            )
            if (writeResult is Resource.Error) return writeResult

            Resource.Success(
                ImportSummary(
                    booksImported = bookResolution.imported,
                    booksSkipped = bookResolution.skipped,
                    booksMerged = bookResolution.merged,
                    booksReplaced = bookResolution.replaced,
                    sessionsImported = 0,
                    sessionsSkipped = 0,
                    sessionsMerged = 0,
                    sessionsReplaced = 0,
                    rejections = bookResolution.rejections,
                    notes = listOf(GoodreadsCsvImporter.NOT_IMPORTED_COLUMNS_NOTICE),
                ),
            )
        } catch (e: Exception) {
            Resource.Error("Goodreads import failed: ${e.message ?: "Unknown error"}", e)
        }
    }

    /** Outcome of [resolveBookRows] -- everything [execute]/[executeGoodreads] need to finish the job. */
    private data class BookRowResolution(
        val inserts: List<ImportBookInsert>,
        val updates: List<ImportBookUpdate>,
        val rejections: List<ImportRejection>,
        val imported: Int,
        val skipped: Int,
        val merged: Int,
        val replaced: Int,
        /**
         * Every media id known after this resolution: pre-existing books, every fresh insert, and
         * -- for a row that matched an existing/in-file book on the isbn or title+year tier -- both
         * the matched book's real id and the row's own `media_id` (see class KDoc, "Ordering and
         * orphan sessions").
         */
        val knownMediaIds: Set<String>,
        /**
         * Every library row's own `media_id` (as it appeared in the file) mapped to the id it
         * actually resolved to in the database: itself for a fresh insert, or the matched book's
         * real id for a duplicate. Used to rewrite a session's `mediaId` onto the book it actually
         * landed on rather than the (possibly different) id its own file row carried.
         */
        val resolvedMediaId: Map<String, String>,
    )

    /**
     * The book-row half of [execute]'s original inline logic (ROADMAP Task 8 Phase B), extracted
     * (Phase D) so [executeGoodreads] can reuse the exact same duplicate-matching precedence,
     * per-[DuplicatePolicy] field rules, and insert/update construction documented in this class's
     * KDoc -- operating on already-parsed [LibraryRowParseResult]s rather than raw CSV text, which
     * is what makes it reusable across two completely different file formats. See this class's
     * top-level KDoc for the matching precedence (`media_id` -> `isbn` -> `title`+`release_year`)
     * and the per-policy field rules; nothing about that logic changed by being extracted here, only
     * where it lives.
     */
    private fun resolveBookRows(
        existingBooks: List<BookWithDetails>,
        existingIdentifiersByMediaId: Map<String, List<ExternalIdentifierEntity>>,
        parseResults: List<LibraryRowParseResult>,
        duplicatePolicy: DuplicatePolicy,
    ): BookRowResolution {
        // Seeded from the pre-existing library, then kept current as each row below resolves, so a
        // later row in *this same file* matches an earlier one too -- see class KDoc, "In-file
        // duplicates" -- not only rows already in the database before this import started.
        val byMediaId = existingBooks.associateByTo(mutableMapOf()) { it.mediaItem.id }
        val byIsbn = existingBooks
            .mapNotNull { book -> book.details?.isbn?.takeIf { it.isNotBlank() }?.let { it to book } }
            .toMap(mutableMapOf())
        val byTitleYear = existingBooks.associateByTo(mutableMapOf()) { titleYearKey(it.mediaItem.title, it.mediaItem.releaseYear) }
        val currentIdentifiersByMediaId = existingIdentifiersByMediaId.toMutableMap()
        val knownMediaIds = existingBooks.mapTo(mutableSetOf()) { it.mediaItem.id }
        val resolvedMediaId = mutableMapOf<String, String>()

        val rejections = mutableListOf<ImportRejection>()
        val inserts = mutableListOf<ImportBookInsert>()
        val updates = mutableListOf<ImportBookUpdate>()
        var imported = 0
        var skipped = 0
        var merged = 0
        var replaced = 0

        // Records a book's current-as-of-this-file state into the three lookup tiers plus the
        // identifier tracker, so a later row in the file that duplicates `id` sees this row's
        // result rather than either nothing (Finding 1) or a stale pre-import snapshot.
        fun registerCurrentState(
            id: String,
            mediaItem: MediaItemEntity,
            details: BookDetailsEntity,
            identifiers: List<ExternalIdentifierEntity>,
        ) {
            val state = BookWithDetails(mediaItem, details)
            byMediaId[id] = state
            details.isbn?.takeIf { it.isNotBlank() }?.let { byIsbn[it] = state }
            byTitleYear[titleYearKey(mediaItem.title, mediaItem.releaseYear)] = state
            currentIdentifiersByMediaId[id] = identifiers
        }

        parseResults.forEachIndexed { index, parsed ->
            val rowNumber = index + 2
            when (parsed) {
                is LibraryRowParseResult.Rejected ->
                    rejections += ImportRejection(ImportRowSource.BOOK, rowNumber, parsed.reason)

                is LibraryRowParseResult.Parsed -> {
                    val row = parsed.row
                    val match = byMediaId[row.mediaId]
                        ?: row.isbn?.let(byIsbn::get)
                        ?: byTitleYear[titleYearKey(row.title, row.releaseYear)]

                    if (match == null) {
                        val insert = buildInsert(row)
                        inserts += insert
                        imported++
                        knownMediaIds += row.mediaId
                        resolvedMediaId[row.mediaId] = row.mediaId
                        registerCurrentState(row.mediaId, insert.mediaItem, insert.details, insert.identifiers)
                    } else {
                        val matchedId = match.mediaItem.id
                        // Both ids are "known": the book's real id, and this row's own media_id,
                        // which may differ from it (isbn/title+year tier) -- a session referencing
                        // either must not be treated as an orphan (Finding 2).
                        knownMediaIds += matchedId
                        knownMediaIds += row.mediaId
                        resolvedMediaId[row.mediaId] = matchedId
                        when (duplicatePolicy) {
                            DuplicatePolicy.SKIP -> skipped++
                            DuplicatePolicy.REPLACE -> {
                                val update = buildReplace(match, row)
                                updates += update
                                replaced++
                                registerCurrentState(matchedId, update.mediaItem, update.details, update.identifiers)
                            }
                            DuplicatePolicy.MERGE -> {
                                val existingIdentifiers = currentIdentifiersByMediaId[matchedId].orEmpty()
                                val update = buildMerge(match, row, existingIdentifiers)
                                updates += update
                                merged++
                                registerCurrentState(matchedId, update.mediaItem, update.details, existingIdentifiers + update.identifiers)
                            }
                        }
                    }
                }
            }
        }

        return BookRowResolution(inserts, updates, rejections, imported, skipped, merged, replaced, knownMediaIds, resolvedMediaId)
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

    private fun toSessionEntity(row: ParsedSessionRow, mediaId: String): ReadingSessionEntity = ReadingSessionEntity(
        id = row.sessionId,
        mediaId = mediaId,
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
