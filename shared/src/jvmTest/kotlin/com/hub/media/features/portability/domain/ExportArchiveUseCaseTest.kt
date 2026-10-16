package com.hub.media.features.portability.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.portability.csv.ArchiveReadResult
import com.hub.media.features.portability.csv.CSV_SCHEMA_VERSION
import com.hub.media.features.portability.csv.ExportArchive
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.features.settings.data.getGoogleBooksApiKey
import com.hub.media.features.settings.data.setGoogleBooksApiKey
import com.hub.media.features.settings.data.setIncludeSpecials
import com.hub.media.features.settings.data.setLogVerbosity
import com.hub.media.features.settings.data.setReadingGoal
import com.hub.media.features.settings.data.setTmdbCredential
import com.hub.media.features.settings.data.setWeekStartDay
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

/** The one-file export (#196): the same CSVs as before, a manifest counting them, and only allowlisted settings. */
class ExportArchiveUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val now = Instant.parse("2026-10-14T03:00:00Z")

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        settings = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private val exporter get() =
        ExportDataUseCase(
            MediaRepository(db),
            BookRepository(db),
            ReadingSessionRepository(db),
        )

    private val useCase
        get() =
            ExportArchiveUseCase(
                exporter,
                settings,
                clock =
                    object : Clock {
                        override fun now(): Instant = this@ExportArchiveUseCaseTest.now
                    },
                zone = TimeZone.UTC,
            )

    private suspend fun manifestOf() =
        assertIs<ArchiveReadResult.Read>(
            ExportArchive.decode((useCase.export("x") as Resource.Success).data.bytes),
        ).manifest

    @Test
    fun archive_holdsTheSameCsvsAsTheBundle_andAManifestCountingRows() =
        runTest {
            TVShowRepository(db).addShow(title = "Fleabag", seasons = listOf(SeasonQuickFill(1, episodeCount = 3)))
            val bundle = (exporter.execute() as Resource.Success).data
            val file = (useCase.export("0.25.0") as Resource.Success).data
            assertEquals("media-tracker-export-2026-10-14.zip", file.suggestedFileName)
            val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(file.bytes))
            assertEquals(bundle.libraryCsv, read.libraryCsv)
            assertEquals(bundle.episodesCsv, read.episodesCsv)
            assertEquals(bundle.watchLogsCsv, read.watchLogsCsv)
            assertEquals("0.25.0", read.manifest.appVersion)
            assertEquals("2026-10-14T03:00:00Z", read.manifest.exportedAt)
            assertEquals(
                listOf(
                    ExportArchive.LIBRARY,
                    ExportArchive.READING_LOGS,
                    ExportArchive.EPISODES,
                    ExportArchive.WATCH_LOGS,
                ),
                read.manifest.files.map { it.name },
            )
            assertEquals(
                3,
                read.manifest.files
                    .single {
                        it.name == ExportArchive.EPISODES
                    }.rows,
                "positive control: three episodes",
            )
            assertEquals(
                1,
                read.manifest.files
                    .single { it.name == ExportArchive.LIBRARY }
                    .rows,
            )
            assertEquals(true, read.manifest.files.all { it.csvSchemaVersion == CSV_SCHEMA_VERSION })
        }

    @Test
    fun settings_onlyTheAllowlist_neverCredentials() =
        runTest {
            settings.setIncludeSpecials(false)
            settings.setWeekStartDay(WeekStartDay.MONDAY)
            settings.setLogVerbosity(LogLevel.WARN)
            settings.setReadingGoal(year = 2026, books = 12)
            settings.setGoogleBooksApiKey("secret-key")
            settings.setTmdbCredential("secret-token")
            assertEquals("secret-key", settings.getGoogleBooksApiKey(), "positive control: the credential is stored")

            val m = manifestOf()
            assertEquals(
                mapOf(
                    "include_specials" to "false",
                    "week_start_day" to "MONDAY",
                    "log_verbosity" to "WARN",
                    "reading_goal" to "2026:12",
                ),
                m.settings,
            )
            assertFalse(m.settings.values.any { "secret" in it })
        }

    @Test
    fun unsetSettings_areOmitted() =
        runTest {
            assertEquals(emptyMap(), manifestOf().settings)
        }
}
