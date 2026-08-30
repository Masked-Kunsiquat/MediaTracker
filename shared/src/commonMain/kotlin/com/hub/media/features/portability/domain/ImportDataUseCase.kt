package com.hub.media.features.portability.domain

import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.dao.ImportDetails
import com.hub.media.core.database.dao.ImportMediaInsert
import com.hub.media.core.database.dao.ImportMediaUpdate
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.core.util.info
import com.hub.media.core.util.warn
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.features.portability.csv.CsvTableReader
import com.hub.media.features.portability.csv.CsvTableResult
import com.hub.media.features.portability.csv.EpisodeCsvExporter
import com.hub.media.features.portability.csv.EpisodeCsvImporter
import com.hub.media.features.portability.csv.EpisodeRowParseResult
import com.hub.media.features.portability.csv.LibraryCsvExporter
import com.hub.media.features.portability.csv.LibraryCsvImporter
import com.hub.media.features.portability.csv.LibraryRowParseResult
import com.hub.media.features.portability.csv.ParsedEpisodeRow
import com.hub.media.features.portability.csv.ParsedLibraryRow
import com.hub.media.features.portability.csv.ParsedRowDetails
import com.hub.media.features.portability.csv.ParsedSessionRow
import com.hub.media.features.portability.csv.ReadingLogCsvExporter
import com.hub.media.features.portability.csv.ReadingLogCsvImporter
import com.hub.media.features.portability.csv.SessionRowParseResult
import com.hub.media.features.portability.data.ImportWriteRepository
import com.hub.media.features.portability.goodreads.GoodreadsCsvImporter
import com.hub.media.features.portability.goodreads.GoodreadsCsvTableReader
import com.hub.media.features.portability.goodreads.GoodreadsCsvTableResult
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

/** See [ImportDataUseCase.execute] and [ImportDataUseCase.executeGoodreads]. */
public interface ImportUseCase {
    public suspend fun execute(
        libraryCsv: String?,
        readingLogsCsv: String?,
        episodesCsv: String?,
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
 * 3. **`title` + `release_year`** (case-insensitive title, exact year match) -- fallback for a book
 *    with no ISBN on record, when both sides agree on a release year. **Known weakness**: this
 *    schema has no author column, so two different books sharing a title and release year (a
 *    common title, a reboot/revival, etc.) would incorrectly match here. Accepted because it only
 *    ever applies when the stronger ISBN tier already failed to match, and the alternative (never
 *    matching at all) means every ISBN-less duplicate is treated as brand new every re-import.
 * 4. **`title` only** (case-insensitive, ignoring `release_year` entirely) -- last-resort fallback
 *    for when tiers 1-3 all failed to match, reached only when the release years disagree or either
 *    side is missing one. This exists because ISBN ingestion ([com.hub.media.features.books.domain.
 *    AddBookByIsbnUseCase] via [com.hub.media.features.books.network.OpenLibraryClient]) stores the
 *    scanned *edition*'s publish year, while [GoodreadsCsvImporter] deliberately prefers the
 *    *work*'s `Original Publication Year` -- so the same book added by ISBN and later re-imported
 *    from Goodreads can carry two different `release_year` values (a 2026 anniversary printing vs.
 *    the 1926 original) and/or two different ISBNs (the scanned edition vs. whichever edition
 *    Goodreads happened to catalog the shelved book under). Without this tier, such a book would
 *    silently miss all three stronger tiers and be inserted as a brand-new duplicate -- exactly the
 *    failure `DuplicatePolicy.MERGE` exists to prevent. This tier carries strictly more collision
 *    risk than tier 3 (two unrelated books sharing only a title, with no year to disambiguate, are
 *    now enough to match), so every match made *only* by this tier is additionally recorded as an
 *    [ImportSummary.notes] entry naming the row, the matched title, and both release years -- never
 *    silently applied. See [titleOnlyReviewNote].
 *
 * A row matching none of the four is always a fresh insert, regardless of [DuplicatePolicy].
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
 * - [DuplicatePolicy.REPLACE]: every field this importer manages (title, authors, releaseYear,
 *   purchasePrice, isbn, format, totalPages, status, finishedAt, trackingMode, external
 *   identifiers) is overwritten with the imported row's value. **`createdAt` and
 *   `coverImageHash` are deliberately never touched by REPLACE** on an existing book: `createdAt`
 *   is "when this device first learned about the book," which a metadata correction shouldn't
 *   silently rewrite, and CSV import carries no image bytes at all (see the cover-image note
 *   below) -- a fresh INSERT (brand new to this device) does use the imported `createdAt`, since
 *   that *is* the correct restore semantics for a book this device has never seen before.
 * - [DuplicatePolicy.MERGE]: only backfills fields the existing row left null/blank
 *   (authors, releaseYear, purchasePrice, isbn, totalPages) and adds any external identifier for a
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
 * for a library row that matched an existing (or in-file) book via the isbn, title+year, or
 * title-only tier rather than an exact `media_id` match -- both the matched book's real id *and* the file's own
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

/** Log tag for this file's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "ImportDataUseCase"

/**
 * Row references carried by a single rejection-summary entry. Bounded because the log file is
 * capped: an import of a badly-formed export can reject thousands of rows, and one entry long
 * enough to push everything else out of the file is its own kind of data loss.
 */
private const val MAX_LOGGED_REJECTED_ROWS = 20

public class ImportDataUseCase(
    private val mediaRepository: MediaRepository,
    private val bookRepository: BookRepository,
    private val readingSessionRepository: ReadingSessionRepository,
    private val importWriteRepository: ImportWriteRepository,
    private val logger: Logger = AppLogger,
) : ImportUseCase {
    /**
     * Refuses an import before anything is written, recording why at WARN.
     *
     * WARN rather than ERROR: a malformed file is the user's to fix, not a fault in the app -- but
     * it is the single most likely thing to be asked about, and the log is where that question gets
     * answered. The message describes file structure (headers, row shape), never cell contents, so
     * the identifier rule holds.
     */
    private fun <T> refuse(message: String): Resource<T> {
        logger.warn(TAG) { "Import refused before writing: $message" }
        return Resource.Error(message)
    }

    /**
     * Records the outcome of a completed import.
     *
     * Rejections are summarised by **count and row number, never by reason**. The reasons are
     * genuinely the useful part for diagnosis, and they are deliberately left out anyway: they are
     * built by `reject("$field is not a valid integer: '$raw'")` and friends, so they embed the raw
     * cell that failed -- which for a title column is book content, straight into a file that
     * outlives the import. Row numbers point at the same rows without carrying any of them, and the
     * full reasons are already in front of the user in the import summary, where they belong.
     *
     * A per-row entry was the obvious alternative and is worse: one malformed export turns into
     * hundreds of entries and buries everything else in a capped log.
     */
    private fun logRejectionSummary(
        pipeline: String,
        rejections: List<ImportRejection>,
    ) {
        if (rejections.isEmpty()) return
        val rows =
            rejections.take(MAX_LOGGED_REJECTED_ROWS).joinToString(", ") {
                "${it.source}#${it.rowNumber}"
            }
        val more = (rejections.size - MAX_LOGGED_REJECTED_ROWS).coerceAtLeast(0)
        logger.warn(TAG) {
            "$pipeline: ${rejections.size} row(s) rejected" +
                " [$rows${if (more > 0) ", and $more more" else ""}]"
        }
    }

    public override suspend fun execute(
        libraryCsv: String?,
        readingLogsCsv: String?,
        episodesCsv: String?,
        duplicatePolicy: DuplicatePolicy,
    ): Resource<ImportSummary> {
        if (libraryCsv == null && readingLogsCsv == null && episodesCsv == null) {
            return refuse("Nothing to import -- no file was selected.")
        }

        val libraryParseResults =
            if (libraryCsv != null) {
                when (
                    val table =
                        CsvTableReader.read(
                            libraryCsv,
                            LibraryCsvExporter.HEADER,
                            // Every header shape this app has ever exported must still import
                            // cleanly: v1 (no `authors` column, ROADMAP Task 9 Phase A), v2 (no
                            // movie columns, Task 13 Phase B), and v3 (no `total_seasons`, Task 13
                            // Phase C) -- see each adapter's KDoc.
                            legacyHeaders =
                                mapOf(
                                    LibraryCsvExporter.HEADER_V1 to LibraryCsvImporter::padLegacyV1Row,
                                    LibraryCsvExporter.HEADER_V2 to LibraryCsvImporter::padLegacyV2Row,
                                    LibraryCsvExporter.HEADER_V3 to LibraryCsvImporter::padLegacyV3Row,
                                ),
                        )
                ) {
                    is CsvTableResult.Failure -> return refuse("library_export.csv: ${table.message}")
                    is CsvTableResult.Success -> table.rows.map { row -> LibraryCsvImporter.parseRow(row) }
                }
            } else {
                emptyList()
            }

        val sessionDataRows =
            if (readingLogsCsv != null) {
                when (val table = CsvTableReader.read(readingLogsCsv, ReadingLogCsvExporter.HEADER)) {
                    is CsvTableResult.Failure -> return refuse("reading_logs_export.csv: ${table.message}")
                    is CsvTableResult.Success -> table.rows
                }
            } else {
                emptyList()
            }

        val episodeDataRows =
            if (episodesCsv != null) {
                when (
                    val table =
                        CsvTableReader.read(
                            episodesCsv,
                            EpisodeCsvExporter.HEADER,
                            // A v4 episodes file predates the four columns CSV v5 appended -- see
                            // EpisodeCsvImporter.padLegacyV4Row.
                            legacyHeaders =
                                mapOf(
                                    EpisodeCsvExporter.HEADER_V4 to EpisodeCsvImporter::padLegacyV4Row,
                                ),
                        )
                ) {
                    is CsvTableResult.Failure -> return refuse("episodes_export.csv: ${table.message}")
                    is CsvTableResult.Success -> table.rows
                }
            } else {
                emptyList()
            }

        return try {
            // Every media type, not just books. This read went through
            // BookRepository.observeAllBooksWithDetails() until Issue #106, which filters to
            // MediaType.BOOK at the DAO -- so an existing film was invisible to duplicate matching
            // and a re-import would have inserted a second copy of it rather than recognising the
            // first. ExportDataUseCase had already made exactly this move, for the same reason.
            val existingMedia = mediaRepository.observeAllMediaWithDetails().first()
            val existingIdentifiersByMediaId =
                bookRepository.observeAllExternalIdentifiers().first().groupBy {
                    it.mediaId
                }
            val existingSessionsById = readingSessionRepository.observeAllSessions().first().associateBy { it.id }
            val allEpisodes = mediaRepository.observeAllEpisodes().first()
            val existingEpisodesById = allEpisodes.associateByTo(mutableMapOf()) { it.id }
            // The second way an episode row can already exist. `episodes` carries a unique index on
            // (mediaId, seasonNumber, episodeNumber), so a row matching an existing episode's *slot*
            // is a duplicate even when its `episode_id` matches nothing -- and insertEpisode is
            // ABORT, so treating it as new does not create a duplicate, it rolls back the entire
            // import. Both maps are kept current as rows resolve below, so two rows in the same file
            // claiming one slot cannot collide either (the book path's "In-file duplicates" rule,
            // applied to episodes).
            val existingEpisodesBySlot =
                allEpisodes.associateByTo(mutableMapOf()) {
                    episodeSlotKey(it.mediaId, it.seasonNumber, it.episodeNumber)
                }

            val mediaResolution =
                resolveMediaRows(existingMedia, existingIdentifiersByMediaId, libraryParseResults, duplicatePolicy)
            val rejections = mediaResolution.rejections.toMutableList()
            val knownMediaIds = mediaResolution.knownMediaIds.toMutableSet()

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
                            rejections +=
                                ImportRejection(
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
                            val resolvedMediaId = mediaResolution.resolvedMediaId[row.mediaId] ?: row.mediaId
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

            val episodeInserts = mutableListOf<EpisodeEntity>()
            val episodeUpdates = mutableListOf<EpisodeEntity>()
            var episodesImported = 0
            var episodesSkipped = 0
            var episodesMerged = 0
            var episodesReplaced = 0

            episodeDataRows.forEachIndexed { index, rawRow ->
                val rowNumber = index + 2
                when (val parsed = EpisodeCsvImporter.parseRow(rawRow)) {
                    is EpisodeRowParseResult.Rejected ->
                        rejections += ImportRejection(ImportRowSource.EPISODE, rowNumber, parsed.reason)

                    is EpisodeRowParseResult.Parsed -> {
                        val row = parsed.row
                        val showType = mediaResolution.typeOfKnownMediaId[row.mediaId]
                        when {
                            showType == null ->
                                rejections +=
                                    ImportRejection(
                                        ImportRowSource.EPISODE,
                                        rowNumber,
                                        "media_id '${row.mediaId}' is not a known show (not already in your " +
                                            "library, and not present in the library file being imported) -- " +
                                            "episode skipped",
                                    )

                            // An episode hanging off a book or a film is not an orphan, it is a
                            // category error -- and one the database would catch far less clearly.
                            // `episodes.mediaId` only constrains the row to *some* media item, so
                            // without this check the episode would insert cleanly against a book and
                            // simply never be shown by anything, which is worse than a rejection.
                            showType != MediaType.TV_SHOW ->
                                rejections +=
                                    ImportRejection(
                                        ImportRowSource.EPISODE,
                                        rowNumber,
                                        "media_id '${row.mediaId}' is a $showType, not a TV show -- " +
                                            "episode skipped",
                                    )

                            else -> {
                                // Same rewrite the session loop performs, for the same reason: a
                                // show matched under a different id than the file used must still
                                // collect its episodes. See the class KDoc.
                                val resolvedMediaId = mediaResolution.resolvedMediaId[row.mediaId] ?: row.mediaId
                                // Id first -- it is the primary key, and the strongest statement the
                                // file can make about which row it means. The slot is the fallback,
                                // for the case ids did not round-trip: see existingEpisodesBySlot.
                                val existing =
                                    existingEpisodesById[row.episodeId]
                                        ?: existingEpisodesBySlot[
                                            episodeSlotKey(resolvedMediaId, row.seasonNumber, row.episodeNumber),
                                        ]
                                val resolved =
                                    if (existing == null) {
                                        val insert = toEpisodeEntity(row, resolvedMediaId)
                                        episodeInserts += insert
                                        episodesImported++
                                        insert
                                    } else {
                                        when (duplicatePolicy) {
                                            DuplicatePolicy.SKIP -> {
                                                episodesSkipped++
                                                existing
                                            }
                                            DuplicatePolicy.REPLACE -> {
                                                val update = replacedEpisode(existing, row, resolvedMediaId)
                                                episodeUpdates += update
                                                episodesReplaced++
                                                update
                                            }
                                            DuplicatePolicy.MERGE -> {
                                                val update = mergeEpisode(existing, row)
                                                episodeUpdates += update
                                                episodesMerged++
                                                update
                                            }
                                        }
                                    }
                                // Record this row's outcome so a later row in the same file sees it,
                                // under both keys -- the file's own episode_id as well as the row's,
                                // since a slot match means the two differ and a later row could cite
                                // either.
                                existingEpisodesById[resolved.id] = resolved
                                existingEpisodesById[row.episodeId] = resolved
                                existingEpisodesBySlot[
                                    episodeSlotKey(resolved.mediaId, resolved.seasonNumber, resolved.episodeNumber),
                                ] = resolved
                            }
                        }
                    }
                }
            }

            val writeResult =
                importWriteRepository.importAtomically(
                    mediaInserts = mediaResolution.inserts,
                    mediaUpdates = mediaResolution.updates,
                    sessionInserts = sessionInserts,
                    sessionUpdates = sessionUpdates,
                    episodeInserts = episodeInserts,
                    episodeUpdates = episodeUpdates,
                )
            if (writeResult is Resource.Error) return writeResult

            logger.info(TAG) {
                "CSV import completed: ${mediaResolution.imported} imported, " +
                    "${mediaResolution.skipped} skipped, ${mediaResolution.merged} merged, " +
                    "${mediaResolution.replaced} replaced, $sessionsImported session(s) imported, " +
                    "$episodesImported episode(s) imported"
            }
            logRejectionSummary("CSV import", rejections)
            Resource.Success(
                ImportSummary(
                    itemsImported = mediaResolution.imported,
                    itemsSkipped = mediaResolution.skipped,
                    itemsMerged = mediaResolution.merged,
                    itemsReplaced = mediaResolution.replaced,
                    booksImported = mediaResolution.importedBooks,
                    sessionsImported = sessionsImported,
                    sessionsSkipped = sessionsSkipped,
                    sessionsMerged = sessionsMerged,
                    sessionsReplaced = sessionsReplaced,
                    episodesImported = episodesImported,
                    episodesSkipped = episodesSkipped,
                    episodesMerged = episodesMerged,
                    episodesReplaced = episodesReplaced,
                    rejections = rejections,
                    notes = mediaResolution.reviewNotes,
                ),
            )
        } catch (e: CancellationException) {
            // Rethrown ahead of the Exception catch -- on JVM CancellationException is an Exception, so
            // swallowing it would break structured concurrency and log a cancelled screen as a failure.
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Import failed" }
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
        val parseResults =
            when (val table = GoodreadsCsvTableReader.read(goodreadsCsv)) {
                is GoodreadsCsvTableResult.Failure -> return refuse("goodreads_library_export.csv: ${table.message}")
                is GoodreadsCsvTableResult.Success ->
                    table.rows.map { row ->
                        GoodreadsCsvImporter.parseRow(table.columnIndex, row)
                    }
            }

        return try {
            // Every media type, for duplicate-matching reasons rather than because a Goodreads file
            // can contain one: matching is scoped by type (see titleYearKey), so a books-only
            // snapshot would work here -- but reading the same whole-library view execute() reads
            // keeps one definition of "what is already in the library" instead of two that could
            // drift.
            val existingMedia = mediaRepository.observeAllMediaWithDetails().first()
            val existingIdentifiersByMediaId =
                bookRepository.observeAllExternalIdentifiers().first().groupBy {
                    it.mediaId
                }

            val mediaResolution =
                resolveMediaRows(existingMedia, existingIdentifiersByMediaId, parseResults, duplicatePolicy)

            val writeResult =
                importWriteRepository.importAtomically(
                    mediaInserts = mediaResolution.inserts,
                    mediaUpdates = mediaResolution.updates,
                    sessionInserts = emptyList(),
                    sessionUpdates = emptyList(),
                    episodeInserts = emptyList(),
                    episodeUpdates = emptyList(),
                )
            if (writeResult is Resource.Error) return writeResult

            logger.info(TAG) {
                "Goodreads import completed: ${mediaResolution.imported} imported, " +
                    "${mediaResolution.skipped} skipped, ${mediaResolution.merged} merged, " +
                    "${mediaResolution.replaced} replaced"
            }
            logRejectionSummary("Goodreads import", mediaResolution.rejections)
            Resource.Success(
                ImportSummary(
                    itemsImported = mediaResolution.imported,
                    itemsSkipped = mediaResolution.skipped,
                    itemsMerged = mediaResolution.merged,
                    itemsReplaced = mediaResolution.replaced,
                    booksImported = mediaResolution.importedBooks,
                    sessionsImported = 0,
                    sessionsSkipped = 0,
                    sessionsMerged = 0,
                    sessionsReplaced = 0,
                    // A Goodreads export has no episodes any more than it has reading sessions.
                    episodesImported = 0,
                    episodesSkipped = 0,
                    episodesMerged = 0,
                    episodesReplaced = 0,
                    rejections = mediaResolution.rejections,
                    notes = listOf(GoodreadsCsvImporter.NOT_IMPORTED_COLUMNS_NOTICE) + mediaResolution.reviewNotes,
                ),
            )
        } catch (e: CancellationException) {
            // Rethrown ahead of the Exception catch -- on JVM CancellationException is an Exception, so
            // swallowing it would break structured concurrency and log a cancelled screen as a failure.
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Goodreads import failed" }
            Resource.Error("Goodreads import failed: ${e.message ?: "Unknown error"}", e)
        }
    }

    /** Outcome of [resolveMediaRows] -- everything [execute]/[executeGoodreads] need to finish the job. */
    private data class MediaRowResolution(
        val inserts: List<ImportMediaInsert>,
        val updates: List<ImportMediaUpdate>,
        val rejections: List<ImportRejection>,
        val imported: Int,
        /** How many of [imported] were books -- see [ImportSummary.booksImported] for why. */
        val importedBooks: Int,
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
        /**
         * One entry per row matched *only* by the title-only tier (see class KDoc, tier 4) -- never
         * populated for a media_id/isbn/title+year match. Surfaced verbatim as
         * [ImportSummary.notes] so a lower-confidence match is never silently applied (Finding 2).
         */
        val reviewNotes: List<String>,
        /**
         * What each id in [knownMediaIds] actually is. An episode row must land on a `TV_SHOW` and
         * nothing else -- `episodes.mediaId` alone would happily accept a book, producing a row no
         * screen ever reads rather than an error (Issue #106). Keyed by the same ids
         * [knownMediaIds] holds, so a show matched under the file's id resolves through either.
         */
        val typeOfKnownMediaId: Map<String, MediaType>,
    )

    /**
     * The library-row half of [execute]'s original inline logic (ROADMAP Task 8 Phase B), extracted
     * (Phase D) so [executeGoodreads] can reuse the exact same duplicate-matching precedence,
     * per-[DuplicatePolicy] field rules, and insert/update construction documented in this class's
     * KDoc -- operating on already-parsed [LibraryRowParseResult]s rather than raw CSV text, which
     * is what makes it reusable across two completely different file formats. See this class's
     * top-level KDoc for the matching precedence (`media_id` -> `isbn` -> `title`+`release_year` ->
     * `title` only) and the per-policy field rules.
     *
     * Called `resolveBookRows` and typed to [MediaWithDetails.Book] until Issue #106. Every matching
     * key was *already* scoped by media type (see [titleYearKey]/[titleOnlyKey]), so widening it to
     * the whole library changed no matching behaviour for books: a film and a book sharing a title
     * could not match each other before and still cannot. What changed is that films and shows are
     * now in the snapshot at all, so a re-import recognises one instead of inserting a duplicate.
     */
    private fun resolveMediaRows(
        existingMedia: List<MediaWithDetails>,
        existingIdentifiersByMediaId: Map<String, List<ExternalIdentifierEntity>>,
        parseResults: List<LibraryRowParseResult>,
        duplicatePolicy: DuplicatePolicy,
    ): MediaRowResolution {
        // Seeded from the pre-existing library, then kept current as each row below resolves, so a
        // later row in *this same file* matches an earlier one too -- see class KDoc, "In-file
        // duplicates" -- not only rows already in the database before this import started.
        val byMediaId = existingMedia.associateByTo(mutableMapOf()) { it.item.id }
        // Books only, and not for want of generality: `isbn` is a column on BookDetailsEntity and
        // nothing else, because it identifies an edition of a printed work. A film has no ISBN to
        // match on, so tier 2 simply never fires for one -- it falls through to title+year.
        val byIsbn =
            existingMedia
                .mapNotNull { media ->
                    (media as? MediaWithDetails.Book)
                        ?.details
                        ?.isbn
                        ?.takeIf { it.isNotBlank() }
                        ?.let { it to (media as MediaWithDetails) }
                }.toMap(mutableMapOf())
        val byTitleYear =
            existingMedia.associateByTo(mutableMapOf()) {
                titleYearKey(it.item.type, it.item.title, it.item.releaseYear)
            }
        // Tier 4 (title only, ignoring release_year) -- see class KDoc. Deliberately last resort:
        // two different pre-existing items sharing a title would collide here (the later one wins
        // the map entry), a strictly higher collision risk than tier 3's title+year pairing. Every
        // match resolved *through this map* is reported via reviewNotes below, never applied silently.
        val byTitleOnly = existingMedia.associateByTo(mutableMapOf()) { titleOnlyKey(it.item.type, it.item.title) }
        val currentIdentifiersByMediaId = existingIdentifiersByMediaId.toMutableMap()
        val knownMediaIds = existingMedia.mapTo(mutableSetOf()) { it.item.id }
        val typeOfKnownMediaId = existingMedia.associateTo(mutableMapOf()) { it.item.id to it.item.type }
        val resolvedMediaId = mutableMapOf<String, String>()
        val reviewNotes = mutableListOf<String>()

        val rejections = mutableListOf<ImportRejection>()
        val inserts = mutableListOf<ImportMediaInsert>()
        val updates = mutableListOf<ImportMediaUpdate>()
        var imported = 0
        var importedBooks = 0
        var skipped = 0
        var merged = 0
        var replaced = 0

        // Records an item's current-as-of-this-file state into the four lookup tiers plus the
        // identifier tracker, so a later row in the file that duplicates `id` sees this row's
        // result rather than either nothing (Finding 1) or a stale pre-import snapshot.
        fun registerCurrentState(
            id: String,
            state: MediaWithDetails,
            identifiers: List<ExternalIdentifierEntity>,
        ) {
            val mediaItem = state.item
            byMediaId[id] = state
            (state as? MediaWithDetails.Book)
                ?.details
                ?.isbn
                ?.takeIf { it.isNotBlank() }
                ?.let { byIsbn[it] = state }
            byTitleYear[titleYearKey(mediaItem.type, mediaItem.title, mediaItem.releaseYear)] = state
            byTitleOnly[titleOnlyKey(mediaItem.type, mediaItem.title)] = state
            currentIdentifiersByMediaId[id] = identifiers
        }

        parseResults.forEachIndexed { index, parsed ->
            val rowNumber = index + 2
            when (parsed) {
                is LibraryRowParseResult.Rejected ->
                    rejections += ImportRejection(ImportRowSource.MEDIA, rowNumber, parsed.reason)

                is LibraryRowParseResult.Parsed -> {
                    val row = parsed.row
                    val strongMatch =
                        byMediaId[row.mediaId]
                            ?: (row.details as? ParsedRowDetails.Book)?.isbn?.let(byIsbn::get)
                            ?: byTitleYear[titleYearKey(row.type, row.title, row.releaseYear)]
                    // Tier 4 only runs when tiers 1-3 all failed -- reached when the release years
                    // disagree (edition year vs. work year, Finding 2) or either side is missing one.
                    val titleOnlyMatch =
                        if (strongMatch == null) {
                            byTitleOnly[titleOnlyKey(row.type, row.title)]
                        } else {
                            null
                        }
                    val match = strongMatch ?: titleOnlyMatch

                    if (titleOnlyMatch != null) {
                        reviewNotes +=
                            titleOnlyReviewNote(
                                rowNumber,
                                row.title,
                                row.releaseYear,
                                titleOnlyMatch.item.releaseYear,
                            )
                    }

                    if (match == null) {
                        val insert = buildInsert(row)
                        inserts += insert
                        imported++
                        if (row.type == MediaType.BOOK) importedBooks++
                        knownMediaIds += row.mediaId
                        typeOfKnownMediaId[row.mediaId] = row.type
                        resolvedMediaId[row.mediaId] = row.mediaId
                        registerCurrentState(row.mediaId, stateOf(insert.mediaItem, insert.details), insert.identifiers)
                    } else {
                        val matchedId = match.item.id
                        // Both ids are "known": the item's real id, and this row's own media_id,
                        // which may differ from it (isbn/title+year/title-only tier) -- a session or
                        // episode referencing either must not be treated as an orphan (Finding 2).
                        knownMediaIds += matchedId
                        knownMediaIds += row.mediaId
                        // The *matched* item's type under both ids, not the row's. They can only
                        // differ if the row was rejected already (every matching key is scoped by
                        // type), but an episode must be checked against what is actually in the
                        // library, not what the file claimed.
                        typeOfKnownMediaId[matchedId] = match.item.type
                        typeOfKnownMediaId[row.mediaId] = match.item.type
                        resolvedMediaId[row.mediaId] = matchedId
                        when (duplicatePolicy) {
                            DuplicatePolicy.SKIP -> skipped++
                            DuplicatePolicy.REPLACE -> {
                                val update = buildReplace(match, row)
                                updates += update
                                replaced++
                                registerCurrentState(
                                    matchedId,
                                    stateOf(update.mediaItem, update.details),
                                    update.identifiers,
                                )
                            }
                            DuplicatePolicy.MERGE -> {
                                val existingIdentifiers = currentIdentifiersByMediaId[matchedId].orEmpty()
                                val update = buildMerge(match, row, existingIdentifiers)
                                updates += update
                                merged++
                                registerCurrentState(
                                    matchedId,
                                    stateOf(update.mediaItem, update.details),
                                    existingIdentifiers + update.identifiers,
                                )
                            }
                        }
                    }
                }
            }
        }

        return MediaRowResolution(
            inserts,
            updates,
            rejections,
            imported,
            importedBooks,
            skipped,
            merged,
            replaced,
            knownMediaIds,
            resolvedMediaId,
            reviewNotes,
            typeOfKnownMediaId,
        )
    }

    /**
     * Re-pairs an item with its details as a [MediaWithDetails], so a row this import just resolved
     * can be matched against by a later row in the same file (see `registerCurrentState`). The
     * inverse of the [ImportDetails] split, and exhaustive over it for the same reason.
     */
    private fun stateOf(
        mediaItem: MediaItemEntity,
        details: ImportDetails,
    ): MediaWithDetails =
        when (details) {
            is ImportDetails.Book -> MediaWithDetails.Book(mediaItem, details.details)
            is ImportDetails.Movie -> MediaWithDetails.Movie(mediaItem, details.details)
            is ImportDetails.TVShow -> MediaWithDetails.TVShow(mediaItem, details.details)
        }

    private fun titleYearKey(
        type: MediaType,
        title: String,
        releaseYear: Int?,
    ): String = "${type.name}::${title.trim().lowercase()}::${releaseYear ?: ""}"

    private fun titleOnlyKey(
        type: MediaType,
        title: String,
    ): String = "${type.name}::${title.trim().lowercase()}"

    /**
     * Human-readable note for a book-row resolved only by tier 4 (title-only, see class KDoc) --
     * surfaced via [ImportSummary.notes] so the user can confirm this wasn't two different books
     * sharing a title (Finding 2's "never silent" requirement).
     */
    private fun titleOnlyReviewNote(
        rowNumber: Int,
        importedTitle: String,
        importedYear: Int?,
        existingYear: Int?,
    ): String =
        "Row $rowNumber: '$importedTitle' matched an existing book by title only, with no ISBN to confirm it -- " +
            "release years disagree or are missing (existing: ${existingYear?.toString() ?: "unknown"}, " +
            "imported: ${importedYear?.toString() ?: "unknown"}). Please verify this is the same book and not " +
            "an accidental duplicate/incorrect merge before trusting the result."

    private fun buildInsert(row: ParsedLibraryRow): ImportMediaInsert {
        val mediaItem =
            MediaItemEntity(
                id = row.mediaId,
                type = row.type,
                title = row.title,
                releaseYear = row.releaseYear,
                purchasePrice = row.purchasePrice,
                createdAt = row.createdAt,
                // Never restored from CSV -- see class KDoc's cover-image note.
                coverImageHash = null,
            )
        val identifiers =
            row.externalIdentifiers.map { (provider, id) ->
                ExternalIdentifierEntity(mediaId = row.mediaId, provider = provider, externalId = id)
            }
        return ImportMediaInsert(mediaItem, freshDetails(row.mediaId, row.details), identifiers)
    }

    /**
     * Builds the details row for a fresh insert, or for a REPLACE (which overwrites every managed
     * field, so it wants the same full-row construction).
     *
     * The show branch leaves `airingStatus`/`overview`/`firstAirDate`/`lastAirDate` at their
     * defaults, and the film branch has no such columns: `library_export.csv` has never carried
     * them. They were added to the schema in PR #86 for the Task 13 Phase D backfill to fill from
     * TMDB, and a CSV round trip legitimately drops what the file does not contain -- unlike the
     * episode columns this same change added to `episodes_export.csv`, which it does now carry.
     * Widening the library file to match is a separate decision from making it readable at all.
     */
    private fun freshDetails(
        mediaId: String,
        details: ParsedRowDetails,
    ): ImportDetails =
        when (details) {
            is ParsedRowDetails.Book ->
                ImportDetails.Book(
                    BookDetailsEntity(
                        mediaId = mediaId,
                        isbn = details.isbn,
                        format = details.format,
                        totalPages = details.totalPages,
                        status = details.status,
                        finishedAt = details.finishedAt,
                        trackingMode = details.trackingMode,
                        authors = details.authors,
                    ),
                )
            is ParsedRowDetails.Movie ->
                ImportDetails.Movie(
                    MovieDetailsEntity(
                        mediaId = mediaId,
                        runtimeMinutes = details.runtimeMinutes,
                        status = details.status,
                        watchedAt = details.watchedAt,
                    ),
                )
            is ParsedRowDetails.TVShow ->
                ImportDetails.TVShow(
                    TVDetailsEntity(
                        mediaId = mediaId,
                        totalSeasons = details.totalSeasons,
                        status = details.status,
                    ),
                )
        }

    private fun buildReplace(
        existing: MediaWithDetails,
        row: ParsedLibraryRow,
    ): ImportMediaUpdate {
        val mediaId = existing.item.id
        val mediaItem =
            existing.item.copy(
                type = row.type,
                title = row.title,
                releaseYear = row.releaseYear,
                purchasePrice = row.purchasePrice,
                // createdAt/coverImageHash intentionally untouched -- see class KDoc.
            )
        val identifiers =
            row.externalIdentifiers.map { (provider, id) ->
                ExternalIdentifierEntity(mediaId = mediaId, provider = provider, externalId = id)
            }
        return ImportMediaUpdate(
            mediaItem,
            replacedDetails(mediaId, existing, row.details),
            identifiers,
            replaceIdentifiers = true,
        )
    }

    /**
     * REPLACE's rule -- overwrite every field this importer manages -- applied to whichever details
     * table this item has.
     *
     * Identical to [freshDetails] except for a show's `airingStatus`/`overview`/`firstAirDate`/
     * `lastAirDate`, which are **preserved rather than overwritten**. REPLACE overwrites what the
     * *file* says, and the file says nothing about those four: they were added in PR #86 for the
     * Task 13 Phase D backfill to fill from TMDB and `library_export.csv` has never carried them.
     * Building the row from [freshDetails] here would have defaulted all four to `null`, so
     * re-importing a backup under REPLACE would silently delete every piece of metadata a backfill
     * had fetched -- the same class of loss that already keeps REPLACE away from `createdAt` and
     * `coverImageHash` on the item itself (see class KDoc).
     */
    private fun replacedDetails(
        mediaId: String,
        existing: MediaWithDetails,
        row: ParsedRowDetails,
    ): ImportDetails =
        when (row) {
            is ParsedRowDetails.TVShow -> {
                val existingDetails = (existing as? MediaWithDetails.TVShow)?.details
                ImportDetails.TVShow(
                    TVDetailsEntity(
                        mediaId = mediaId,
                        totalSeasons = row.totalSeasons,
                        status = row.status,
                        airingStatus = existingDetails?.airingStatus,
                        overview = existingDetails?.overview,
                        firstAirDate = existingDetails?.firstAirDate,
                        lastAirDate = existingDetails?.lastAirDate,
                    ),
                )
            }
            // Books and films carry no column the file omits, so a full rebuild loses nothing.
            is ParsedRowDetails.Book,
            is ParsedRowDetails.Movie,
            -> freshDetails(mediaId, row)
        }

    private fun buildMerge(
        existing: MediaWithDetails,
        row: ParsedLibraryRow,
        existingIdentifiers: List<ExternalIdentifierEntity>,
    ): ImportMediaUpdate {
        val mediaId = existing.item.id
        val mediaItem =
            existing.item.copy(
                releaseYear = existing.item.releaseYear ?: row.releaseYear,
                purchasePrice = existing.item.purchasePrice ?: row.purchasePrice,
                // type/title/createdAt/coverImageHash never backfilled -- identity/local-owned fields.
            )
        val existingProviders = existingIdentifiers.map { it.provider }.toSet()
        val newIdentifiers =
            row.externalIdentifiers
                .filter { (provider, _) -> provider !in existingProviders }
                .map { (provider, id) ->
                    ExternalIdentifierEntity(mediaId = mediaId, provider = provider, externalId = id)
                }
        return ImportMediaUpdate(
            mediaItem,
            mergedDetails(mediaId, existing, row.details),
            newIdentifiers,
            replaceIdentifiers = false,
        )
    }

    /**
     * MERGE's per-field rule -- backfill only what the existing row left null/blank -- applied to
     * whichever details table this item has.
     *
     * The `existing` and `row` variants always agree, because every duplicate-matching key is scoped
     * by media type (see [titleYearKey]), so a book row can only ever match a book. The `else`
     * branches below are therefore unreachable rather than defensive; each falls back to treating
     * the imported row as authoritative, which is the same thing [freshDetails] would produce, so a
     * future change that did break the scoping would degrade to REPLACE semantics for that row
     * rather than silently dropping it.
     *
     * Watch state is *not* backfilled onto a film or show that already has one, matching how a
     * book's `status`/`finishedAt` are left alone: whether you have seen something is a fact about
     * this device's owner, not metadata a re-import should overwrite. `WATCHLIST` is the default a
     * row carries when the file said nothing, so treating it as "unset" is what makes a merge from
     * a file that never recorded watch state leave the real value in place.
     */
    private fun mergedDetails(
        mediaId: String,
        existing: MediaWithDetails,
        row: ParsedRowDetails,
    ): ImportDetails =
        when (row) {
            is ParsedRowDetails.Book -> {
                val existingDetails = (existing as? MediaWithDetails.Book)?.details
                ImportDetails.Book(
                    BookDetailsEntity(
                        mediaId = mediaId,
                        isbn = existingDetails?.isbn?.takeIf { it.isNotBlank() } ?: row.isbn,
                        format = existingDetails?.format ?: row.format,
                        totalPages = existingDetails?.totalPages ?: row.totalPages,
                        status = existingDetails?.status ?: row.status,
                        finishedAt = existingDetails?.finishedAt ?: row.finishedAt,
                        trackingMode = existingDetails?.trackingMode ?: row.trackingMode,
                        authors = existingDetails?.authors?.takeIf { it.isNotBlank() } ?: row.authors,
                    ),
                )
            }
            is ParsedRowDetails.Movie -> {
                val existingDetails = (existing as? MediaWithDetails.Movie)?.details
                ImportDetails.Movie(
                    MovieDetailsEntity(
                        mediaId = mediaId,
                        runtimeMinutes = existingDetails?.runtimeMinutes ?: row.runtimeMinutes,
                        status =
                            existingDetails
                                ?.status
                                ?.takeIf { it != WatchStatus.WATCHLIST }
                                ?: row.status,
                        watchedAt = existingDetails?.watchedAt ?: row.watchedAt,
                    ),
                )
            }
            is ParsedRowDetails.TVShow -> {
                val existingDetails = (existing as? MediaWithDetails.TVShow)?.details
                ImportDetails.TVShow(
                    TVDetailsEntity(
                        mediaId = mediaId,
                        totalSeasons = existingDetails?.totalSeasons ?: row.totalSeasons,
                        status =
                            existingDetails
                                ?.status
                                ?.takeIf { it != WatchStatus.WATCHLIST }
                                ?: row.status,
                        // Phase D's columns are preserved rather than defaulted away: they are not
                        // in the file, so a merge has nothing to say about them and must not blank
                        // what a backfill already wrote.
                        airingStatus = existingDetails?.airingStatus,
                        overview = existingDetails?.overview,
                        firstAirDate = existingDetails?.firstAirDate,
                        lastAirDate = existingDetails?.lastAirDate,
                    ),
                )
            }
        }

    private fun toSessionEntity(
        row: ParsedSessionRow,
        mediaId: String,
    ): ReadingSessionEntity =
        ReadingSessionEntity(
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

    private fun mergeSession(
        existing: ReadingSessionEntity,
        row: ParsedSessionRow,
    ): ReadingSessionEntity =
        existing.copy(
            durationSeconds = existing.durationSeconds ?: row.durationSeconds,
            deltaPages = existing.deltaPages ?: row.deltaPages,
            notes = existing.notes?.takeIf { it.isNotBlank() } ?: row.notes,
            // timestamps/positions/mediaId are the session's identity -- merge never touches them.
        )

    /**
     * The `episodes` unique index, as a map key -- see `existingEpisodesBySlot`.
     *
     * Season and episode numbers are `Int`, so no normalisation is needed and the separator only has
     * to be something a `mediaId` cannot contain. It cannot contain `/`: ids are either generated
     * UUIDs or come from the file, and a file's `media_id` reaches here only after matching a real
     * item (an unmatched one is rejected as an unknown show before this is called).
     */
    private fun episodeSlotKey(
        mediaId: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): String = "$mediaId/$seasonNumber/$episodeNumber"

    /**
     * REPLACE onto an episode that already exists here.
     *
     * Two things are taken from the existing row rather than the file, and both are the same rule
     * [buildReplace] follows for a media item:
     * - **`id`**, because the match may have been on the season/episode slot rather than on
     *   `episode_id` (see `existingEpisodesBySlot`). The update is applied by `@Update`, which
     *   matches on the primary key, so building this with the *file's* id would update nothing and
     *   then be reported as a replacement that never happened.
     * - **`stillImageHash`**, because the CSV carries the hash naming an image and never the image
     *   itself. Taking the file's value would point the row at a file this device does not have;
     *   clearing it would discard one it does. Neither is what "the file wins" should mean for a
     *   column whose content the file does not contain.
     *
     * A genuinely new episode still gets `null` -- there is no local image to keep.
     */
    private fun replacedEpisode(
        existing: EpisodeEntity,
        row: ParsedEpisodeRow,
        mediaId: String,
    ): EpisodeEntity =
        toEpisodeEntity(row, mediaId).copy(
            id = existing.id,
            stillImageHash = existing.stillImageHash,
        )

    /**
     * `stillImageHash` is deliberately dropped, never written -- see [ParsedEpisodeRow.stillImageHash]
     * and the class KDoc's image note. Everything else the file carries is restored.
     */
    private fun toEpisodeEntity(
        row: ParsedEpisodeRow,
        mediaId: String,
    ): EpisodeEntity =
        EpisodeEntity(
            id = row.episodeId,
            mediaId = mediaId,
            seasonNumber = row.seasonNumber,
            episodeNumber = row.episodeNumber,
            title = row.title,
            airDate = row.airDate,
            watchedAt = row.watchedAt,
            runtimeMinutes = row.runtimeMinutes,
            overview = row.overview,
            stillImageHash = null,
            communityRating = row.communityRating,
        )

    /**
     * MERGE onto an episode that already exists here: backfill only what the existing row left
     * unknown.
     *
     * **`watchedAt` is never touched**, and it is the one that matters. It is simultaneously this
     * row's watched *state* and its timestamp ([EpisodeEntity]'s "watched state and its timestamp
     * are one column deliberately"), so backfilling it would let a re-import mark episodes watched
     * that the user has not watched on this device -- and clearing it is not representable either.
     * Under MERGE the device's own view of what it has seen wins outright; REPLACE is the policy
     * that exists for "the file is authoritative".
     *
     * `seasonNumber`/`episodeNumber`/`mediaId` are the episode's identity and are left alone for the
     * same reason [mergeSession] leaves a session's timestamps and positions alone.
     */
    private fun mergeEpisode(
        existing: EpisodeEntity,
        row: ParsedEpisodeRow,
    ): EpisodeEntity =
        existing.copy(
            title = existing.title?.takeIf { it.isNotBlank() } ?: row.title,
            airDate = existing.airDate ?: row.airDate,
            runtimeMinutes = existing.runtimeMinutes ?: row.runtimeMinutes,
            overview = existing.overview?.takeIf { it.isNotBlank() } ?: row.overview,
            communityRating = existing.communityRating ?: row.communityRating,
        )
}
