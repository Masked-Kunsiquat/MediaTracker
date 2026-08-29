package com.hub.media.features.portability.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.portability.csv.CSV_SCHEMA_VERSION
import com.hub.media.features.portability.csv.CsvUtil
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Integration test for [ExportDataUseCase] against a real (in-memory) [AppDatabase], following the
 * same pattern as `BookRepositoryTest`/`LogReadingSessionUseCaseTest` -- Room-touching, so this
 * lives in `com.hub.media.features.portability.domain` and lives in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81); `:shared:jvmTest` is
 * the authoritative gate. Exercises the whole pipeline end to end (repository writes -> use case
 * read -> CSV formatting), complementing `LibraryCsvExporterTest`/`ReadingLogCsvExporterTest`'s
 * pure formatting-only coverage.
 */
class ExportDataUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var readingSessionRepository: ReadingSessionRepository
    private lateinit var useCase: ExportDataUseCase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        bookRepository = BookRepository(db)
        readingSessionRepository = ReadingSessionRepository(db)
        useCase = ExportDataUseCase(MediaRepository(db), bookRepository, readingSessionRepository)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    /**
     * A movie in the library must appear in the exported CSV.
     *
     * This is a data-portability claim, not a formatting one: `library_export.csv` is the archival
     * format this app promises (AGENTS.md §1), so anything missing from it is data a user loses on
     * device loss with no error to warn them. `LibraryCsvExporter` was generalized to
     * `MediaWithDetails` in Issue #67 and handles movie rows, so the question is purely whether the
     * export use case ever hands them to it.
     */
    @Test
    fun execute_withAMovieInTheLibrary_includesItInTheLibraryCsv() =
        runTest {
            val movieRepository = MovieRepository(db)
            assertIs<Resource.Success<String>>(bookRepository.addBook(title = "Dune", format = BookFormat.PHYSICAL))
            assertIs<Resource.Success<String>>(
                movieRepository.addMovie(
                    title = "Arrival",
                    releaseYear = 2016,
                    runtimeMinutes = 116,
                    status = WatchStatus.WATCHED,
                ),
            )

            val result = useCase.execute()
            assertIs<Resource.Success<CsvExportBundle>>(result)

            val libraryCsv = result.data.libraryCsv
            assertTrue(libraryCsv.contains("Dune"), "the book must be exported")
            assertTrue(
                libraryCsv.contains("Arrival"),
                "the movie must be exported too -- a backup that silently omits it loses the row: $libraryCsv",
            )

            // The row alone is not the claim: a movie whose runtime and watch status are missing
            // from the file has been half-exported, and those two are the only things the movie
            // form records that a media_items row cannot hold.
            val movieLine = libraryCsv.split(CsvUtil.LINE_ENDING).single { it.contains("Arrival") }
            val fields = movieLine.split(",")
            assertEquals("116", fields[16], "runtime must reach the CSV: $movieLine")
            assertEquals("WATCHED", fields[17], "watch status must reach the CSV: $movieLine")
            assertTrue(fields[18].isNotEmpty(), "watchedAt must reach the CSV: $movieLine")
        }

    @Test
    fun execute_emptyDatabase_producesHeaderOnlyCsvForAllThreeFiles() =
        runTest {
            val result = useCase.execute()
            assertIs<Resource.Success<CsvExportBundle>>(result)

            val libraryLines =
                result.data.libraryCsv
                    .trimEnd()
                    .split(CsvUtil.LINE_ENDING)
            val logsLines =
                result.data.readingLogsCsv
                    .trimEnd()
                    .split(CsvUtil.LINE_ENDING)
            val episodesLines =
                result.data.episodesCsv
                    .trimEnd()
                    .split(CsvUtil.LINE_ENDING)
            assertEquals(1, libraryLines.size)
            assertEquals(1, logsLines.size)
            assertEquals(1, episodesLines.size)
        }

    /**
     * A show's quick-filled episodes must appear in `episodes_export.csv` with `watched_at`
     * populated for the ones ticked off and empty for the rest.
     *
     * This is the end-to-end claim [EpisodeCsvExporterTest]'s unit-level coverage cannot make on
     * its own: that the backup actually carries watched state all the way from
     * [TVShowRepository.setEpisodeWatched] through the real repository/DAO round-trip to the
     * generated file -- the exact same "a backup that drops this loses the row" concern
     * [execute_withAMovieInTheLibrary_includesItInTheLibraryCsv] makes for movies, applied to the
     * column [EpisodeCsvExporter]'s KDoc calls "the point of the file."
     */
    @Test
    fun execute_showWithQuickFilledEpisodes_exportsWatchedStatePerEpisode() =
        runTest {
            val tvShowRepository = TVShowRepository(db)
            val addResult =
                tvShowRepository.addShow(
                    title = "Chernobyl",
                    totalSeasons = 1,
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 3)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data
            val episodes = tvShowRepository.observeEpisodes(mediaId).first()
            val watchedEpisode = episodes.single { it.episodeNumber == 1 }
            val unwatchedEpisode = episodes.single { it.episodeNumber == 2 }
            assertIs<Resource.Success<Unit>>(tvShowRepository.setEpisodeWatched(watchedEpisode.id, watched = true))

            val result = useCase.execute()
            assertIs<Resource.Success<CsvExportBundle>>(result)
            val episodesCsv = result.data.episodesCsv

            val lines = episodesCsv.trimEnd().split(CsvUtil.LINE_ENDING).drop(1)
            assertEquals(3, lines.size, "every quick-filled episode must be exported: $episodesCsv")

            val watchedLine = lines.single { it.contains(watchedEpisode.id) }
            val watchedFields = watchedLine.split(",")
            assertTrue(watchedFields.last().isNotEmpty(), "watched_at must reach the CSV: $watchedLine")

            val unwatchedLine = lines.single { it.contains(unwatchedEpisode.id) }
            val unwatchedFields = unwatchedLine.split(",")
            assertEquals("", unwatchedFields.last(), "an unwatched episode's watched_at must be empty")
        }

    @Test
    fun execute_libraryWithBooksAndSessions_exportsEveryRowWithExternalIdentifiers() =
        runTest {
            val addResult =
                bookRepository.addBook(
                    title = "Sample Book, With a Comma",
                    releaseYear = 2021,
                    purchasePrice = 19.99,
                    format = BookFormat.PAPERBACK,
                    totalPages = 250,
                    isbn = "9781234567897",
                    externalIdentifiers =
                        listOf(
                            IdentifierProvider.ISBN to "9781234567897",
                            IdentifierProvider.OPEN_LIBRARY to "OL12345M",
                        ),
                )
            assertIs<Resource.Success<String>>(addResult)
            val mediaId = addResult.data

            val sessionResult =
                readingSessionRepository.logSession(
                    mediaId = mediaId,
                    timestampStart = Instant.fromEpochMilliseconds(1_700_000_000_000),
                    timestampEnd = Instant.fromEpochMilliseconds(1_700_000_600_000),
                    durationSeconds = null, // backlogged manual entry, unknown duration
                    startUnit = 0.0,
                    endUnit = 42.0,
                    deltaPages = 42,
                    notes = "Great start, \"couldn't put it down\"",
                )
            assertIs<Resource.Success<String>>(sessionResult)

            val result = useCase.execute()
            assertIs<Resource.Success<CsvExportBundle>>(result)
            val bundle = result.data

            val libraryDataLine = bundle.libraryCsv.trimEnd().split(CsvUtil.LINE_ENDING)[1]
            assertTrue(libraryDataLine.contains("\"Sample Book, With a Comma\""))
            assertTrue(libraryDataLine.contains("ISBN:9781234567897"))
            assertTrue(libraryDataLine.contains("OPEN_LIBRARY:OL12345M"))
            assertTrue(libraryDataLine.startsWith("$CSV_SCHEMA_VERSION,"))

            val logsDataLine = bundle.readingLogsCsv.trimEnd().split(CsvUtil.LINE_ENDING)[1]
            assertTrue(logsDataLine.contains(mediaId))
            // Unknown duration must appear as an empty field, never "0" -- see
            // ReadingLogCsvExporterTest for the dedicated unit-level coverage of this rule; this
            // integration test confirms it survives the full repository round-trip too.
            assertTrue(!logsDataLine.contains(",0,"))
            assertTrue(logsDataLine.contains("\"Great start, \"\"couldn't put it down\"\"\""))
        }
}
