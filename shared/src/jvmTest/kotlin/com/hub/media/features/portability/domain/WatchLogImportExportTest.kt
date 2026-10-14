package com.hub.media.features.portability.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.portability.data.ImportWriteRepository
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `watch_logs_export.csv` (#90): viewings survive an export/import, are never duplicated by a
 * re-import, attach to the right episode when ids did not round-trip, and the import leaves every
 * touched episode's `watchedAt` equal to its latest viewing.
 */
class WatchLogImportExportTest {
    private val t1 = Instant.parse("2024-03-03T12:00:00Z")
    private val t2 = Instant.parse("2025-01-10T12:00:00Z")
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase

    @BeforeTest
    fun setUp() {
        source = testAppDatabase()
        target = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun exporter(db: AppDatabase) =
        ExportDataUseCase(MediaRepository(db), BookRepository(db), ReadingSessionRepository(db))

    private fun importer(db: AppDatabase) =
        ImportDataUseCase(
            MediaRepository(db),
            BookRepository(db),
            ReadingSessionRepository(db),
            ImportWriteRepository(db),
        )

    /** A show with two episodes: E1 watched at [t1] and re-watched at [t2], E2 watched at [t1]. */
    private suspend fun seedSource(): List<EpisodeEntity> {
        val r =
            TVShowRepository(
                source,
            ).addShow(title = "Fleabag", seasons = listOf(SeasonQuickFill(1, episodeCount = 2)))
        assertIs<Resource.Success<String>>(r)
        val (e1, e2) = source.episodeDao().getByMediaIdAndSeason(r.data, 1)
        source.tvWriteDao().markEpisodesWatchedAt(mapOf(e1.id to t1, e2.id to t1))
        source.tvWriteDao().addViewing(e1.id, t2)
        return listOf(e1, e2)
    }

    private suspend fun export(db: AppDatabase): CsvExportBundle = (exporter(db).execute() as Resource.Success).data

    private suspend fun import(
        db: AppDatabase,
        bundle: CsvExportBundle,
        policy: DuplicatePolicy = DuplicatePolicy.SKIP,
        withWatchLogs: Boolean = true,
    ) = importer(db).execute(
        bundle.libraryCsv,
        bundle.readingLogsCsv,
        bundle.episodesCsv,
        policy,
        if (withWatchLogs) bundle.watchLogsCsv else null,
    )

    private suspend fun allViewings(db: AppDatabase): List<WatchLogEntity> = db.watchLogDao().observeAll().first()

    private suspend fun assertInvariantEverywhere(db: AppDatabase) {
        val byEpisode = allViewings(db).groupBy { it.episodeId }
        for (e in MediaRepository(db).observeAllEpisodes().first()) {
            assertEquals(byEpisode[e.id]?.maxOf { it.watchedAt }, e.watchedAt, "episode ${e.episodeNumber}")
        }
    }

    @Test
    fun roundTrip_isLossless() =
        runTest {
            seedSource()
            assertIs<Resource.Success<*>>(import(target, export(source)))
            assertEquals(
                allViewings(source).map { it.id to it.watchedAt }.toSet(),
                allViewings(target).map { it.id to it.watchedAt }.toSet(),
            )
            assertEquals(3, allViewings(target).size, "positive control: the three viewings arrived")
            assertInvariantEverywhere(target)
        }

    @Test
    fun reimportingTheSameExport_createsNoViewings() =
        runTest {
            seedSource()
            val bundle = export(source)
            import(target, bundle)
            import(target, bundle)
            import(target, bundle, DuplicatePolicy.MERGE)
            assertEquals(3, allViewings(target).size)
            assertInvariantEverywhere(target)
        }

    @Test
    fun slotMatchedImport_attachesViewingsToTheLibrarysEpisode() =
        runTest {
            val sourceEpisodes = seedSource()
            val bundle = export(source)
            // Target already holds the same show and episodes under DIFFERENT episode ids: the library
            // file brings the show (same media id), and target quick-fills its own episode rows first.
            importer(target).execute(bundle.libraryCsv, null, null, DuplicatePolicy.SKIP)
            val mediaId = sourceEpisodes.first().mediaId
            TVShowRepository(target).setSeasonLength(mediaId, 1, 2)
            val targetIds =
                target
                    .episodeDao()
                    .getByMediaIdAndSeason(mediaId, 1)
                    .map { it.id }
                    .toSet()
            assertEquals(emptySet(), targetIds intersect sourceEpisodes.map { it.id }.toSet(), "ids must differ")

            assertIs<Resource.Success<*>>(import(target, bundle))
            val viewings = allViewings(target)
            assertEquals(3, viewings.size)
            assertEquals(targetIds, viewings.mapNotNull { it.episodeId }.toSet(), "viewings land on target's own rows")
            assertInvariantEverywhere(target)
        }

    @Test
    fun episodeImportedWatched_withNoViewingsFile_getsOneViewing() =
        runTest {
            seedSource()
            import(target, export(source), withWatchLogs = false)
            assertEquals(2, allViewings(target).size, "one per watched episode, at watched_at")
            assertInvariantEverywhere(target)
        }

    @Test
    fun replace_replacesThatEpisodesViewings() =
        runTest {
            val (e1, _) = seedSource()
            import(target, export(source))
            // Source loses E1's re-watch; a REPLACE import must drop it in target too.
            val rewatch =
                source
                    .watchLogDao()
                    .observeForEpisode(e1.id)
                    .first()
                    .first { it.watchedAt == t2 }
            source.tvWriteDao().deleteViewing(rewatch.id)

            import(target, export(source), DuplicatePolicy.REPLACE)
            assertEquals(
                listOf(t1),
                target
                    .watchLogDao()
                    .observeForEpisode(e1.id)
                    .first()
                    .map { it.watchedAt },
            )
            assertInvariantEverywhere(target)
        }

    @Test
    fun v5Files_withoutWatchLogs_stillImport() =
        runTest {
            seedSource()
            val result = import(target, export(source), withWatchLogs = false)
            assertIs<Resource.Success<*>>(result)
            assertEquals(2, MediaRepository(target).observeAllEpisodes().first().count { it.watchedAt != null })
        }

    @Test
    fun replace_withTheFileSayingUnwatched_unticksTheEpisode() =
        runTest {
            val (e1, _) = seedSource()
            import(target, export(source))
            source.tvWriteDao().clearEpisodeReturningPrevious(e1.id)

            import(target, export(source), DuplicatePolicy.REPLACE)
            assertEquals(
                null,
                target.episodeDao().getById(e1.id)!!.watchedAt,
                "REPLACE: the file's blank watched_at wins",
            )
            assertEquals(emptyList(), target.watchLogDao().observeForEpisode(e1.id).first())
            assertInvariantEverywhere(target)
        }

    @Test
    fun replace_withoutAWatchLogsFile_theFilesDateWins() =
        runTest {
            val (_, e2) = seedSource()
            import(target, export(source))
            val only =
                source
                    .watchLogDao()
                    .observeForEpisode(e2.id)
                    .first()
                    .single()
            source.tvWriteDao().updateViewingDate(only.id, t2)

            import(target, export(source), DuplicatePolicy.REPLACE, withWatchLogs = false)
            assertEquals(t2, target.episodeDao().getById(e2.id)!!.watchedAt)
            assertEquals(
                listOf(t2),
                target
                    .watchLogDao()
                    .observeForEpisode(e2.id)
                    .first()
                    .map { it.watchedAt },
            )
            assertInvariantEverywhere(target)
        }

    @Test
    fun summary_reportsViewingsImportedAndSkipped() =
        runTest {
            seedSource()
            val bundle = export(source)
            val first = assertIs<Resource.Success<ImportSummary>>(import(target, bundle)).data
            assertEquals(3, first.viewingsImported)
            val again = assertIs<Resource.Success<ImportSummary>>(import(target, bundle)).data
            assertEquals(0, again.viewingsImported)
            assertEquals(3, again.viewingsSkipped)
        }

    @Test
    fun aViewingWhoseMediaIdDisagreesWithItsEpisode_isRejected() =
        runTest {
            val (e1, _) = seedSource()
            val bundle = export(source)
            // Point E1's viewings at a show that does not own E1.
            val tampered = bundle.watchLogsCsv.replace(e1.mediaId + "," + e1.id, "not-this-show," + e1.id)
            assertEquals(true, tampered != bundle.watchLogsCsv, "positive control: the tamper took")

            val summary =
                assertIs<Resource.Success<ImportSummary>>(
                    import(target, bundle.copy(watchLogsCsv = tampered)),
                ).data
            assertEquals(1, summary.viewingsImported, "only E2's untampered viewing is accepted")
            assertEquals(2, summary.rejections.count { it.source == ImportRowSource.WATCH_LOG })
        }

    @Test
    fun anEpisodeWhoseDateHasNoViewingInTheFile_keepsThatDate() =
        runTest {
            val (e1, _) = seedSource()
            val bundle = export(source)
            // The episodes file says E1 was last watched at t2; drop that viewing from the viewings file.
            val lines = bundle.watchLogsCsv.lines()
            val trimmed = lines.filterNot { e1.id in it && "2025" in it }.joinToString("\n")
            assertEquals(lines.size - 1, trimmed.lines().size, "positive control: exactly one row dropped")

            import(target, bundle.copy(watchLogsCsv = trimmed))
            assertEquals(t2, target.episodeDao().getById(e1.id)!!.watchedAt)
            assertEquals(
                setOf(t1, t2),
                target
                    .watchLogDao()
                    .observeForEpisode(e1.id)
                    .first()
                    .map { it.watchedAt }
                    .toSet(),
            )
            assertInvariantEverywhere(target)
        }

    // ---- Film viewings (#192) ------------------------------------------------------------------

    private fun clockAt(at: Instant) =
        object : Clock {
            override fun now(): Instant = at
        }

    /** "Heat": watched at [t1], then watched again at [t2]. */
    private suspend fun seedFilm(): String {
        val id =
            (
                MovieRepository(source, clockAt(t1)).addMovie(
                    title = "Heat",
                    status = WatchStatus.WATCHED,
                ) as Resource.Success
            ).data
        source.movieWriteDao().addFilmViewing(id, t2)
        return id
    }

    private suspend fun filmViewings(
        db: AppDatabase,
        id: String,
    ) = db
        .watchLogDao()
        .observeForFilm(id)
        .first()
        .map { it.watchedAt }

    private suspend fun assertFilmInvariant(
        db: AppDatabase,
        id: String,
    ) {
        val d = db.movieDetailsDao().getByMediaId(id)!!
        val expected = if (d.status == WatchStatus.WATCHED) filmViewings(db, id).maxOrNull() else null
        assertEquals(expected, d.watchedAt)
    }

    @Test
    fun filmViewings_roundTrip() =
        runTest {
            val film = seedFilm()
            import(target, export(source))
            assertEquals(listOf(t2, t1), filmViewings(target, film))
            assertEquals(t2, target.movieDetailsDao().getByMediaId(film)!!.watchedAt)
            assertFilmInvariant(target, film)
        }

    @Test
    fun filmViewings_reimport_createsNothing() =
        runTest {
            seedFilm()
            val bundle = export(source)
            import(target, bundle)
            val again = assertIs<Resource.Success<ImportSummary>>(import(target, bundle)).data
            assertEquals(0, again.viewingsImported)
            assertEquals(2, again.viewingsSkipped)
        }

    @Test
    fun twoFilmsWithSameDate_bothImported() =
        runTest {
            val heat = seedFilm()
            val ronin =
                (
                    MovieRepository(source, clockAt(t1)).addMovie(
                        title = "Ronin",
                        status = WatchStatus.WATCHED,
                    ) as Resource.Success
                ).data
            import(target, export(source))
            assertEquals(listOf(t1), filmViewings(target, ronin))
            assertEquals(listOf(t2, t1), filmViewings(target, heat))
        }

    @Test
    fun filmViewing_pointingAtAShow_isRejected() =
        runTest {
            val (e1, _) = seedSource()
            val film = seedFilm()
            val bundle = export(source)
            val tampered = bundle.watchLogsCsv.replace("$film,,", "${e1.mediaId},,")
            assertNotEquals(bundle.watchLogsCsv, tampered, "positive control: the tamper took")
            val summary =
                assertIs<Resource.Success<ImportSummary>>(import(target, bundle.copy(watchLogsCsv = tampered))).data
            assertEquals(2, summary.rejections.count { it.source == ImportRowSource.WATCH_LOG })
            assertEquals(emptyList(), filmViewings(target, e1.mediaId))
        }

    @Test
    fun replace_filmFileWins() =
        runTest {
            val film = seedFilm()
            import(target, export(source))
            target.movieWriteDao().addFilmViewing(film, Instant.parse("2025-06-01T12:00:00Z"))
            import(target, export(source), DuplicatePolicy.REPLACE)
            assertEquals(listOf(t2, t1), filmViewings(target, film))
            assertFilmInvariant(target, film)
        }

    @Test
    fun film_watchedAtWithoutMatchingViewing_keepsItsDate() =
        runTest {
            val film = seedFilm()
            val bundle = export(source)
            val lines = bundle.watchLogsCsv.lines()
            val trimmed = lines.filterNot { film in it && "2025" in it }.joinToString("\n")
            assertEquals(lines.size - 1, trimmed.lines().size, "positive control: exactly one row dropped")
            import(target, bundle.copy(watchLogsCsv = trimmed))
            assertEquals(t2, target.movieDetailsDao().getByMediaId(film)!!.watchedAt)
            assertFilmInvariant(target, film)
        }
}
