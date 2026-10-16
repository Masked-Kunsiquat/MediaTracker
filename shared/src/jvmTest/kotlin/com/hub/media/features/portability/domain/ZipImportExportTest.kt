package com.hub.media.features.portability.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.dao.AppSettingsDao
import com.hub.media.core.database.entities.AppSettingEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.portability.csv.EXPORT_FORMAT_VERSION
import com.hub.media.features.portability.csv.ExportArchive
import com.hub.media.features.portability.csv.ExportManifest
import com.hub.media.features.portability.csv.unzipEntries
import com.hub.media.features.portability.csv.zipEntries
import com.hub.media.features.portability.data.ImportWriteRepository
import com.hub.media.features.settings.data.ReadingGoal
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.features.settings.data.getIncludeSpecials
import com.hub.media.features.settings.data.getLogVerbosity
import com.hub.media.features.settings.data.getWeekStartDay
import com.hub.media.features.settings.data.observeReadingGoal
import com.hub.media.features.settings.data.setIncludeSpecials
import com.hub.media.features.settings.data.setLogVerbosity
import com.hub.media.features.settings.data.setReadingGoal
import com.hub.media.features.settings.data.setWeekStartDay
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Importing the one-file export (#196): a zip or a lone library CSV, refusals that write nothing,
 * row-count notes rather than failures, and settings restored only when asked.
 */
class ZipImportExportTest {
    private val t1 = Instant.parse("2024-03-03T12:00:00Z")
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

    private fun settings(db: AppDatabase) = SettingsRepository(db.appSettingsDao())

    private fun exporter(db: AppDatabase) =
        ExportArchiveUseCase(
            ExportDataUseCase(MediaRepository(db), BookRepository(db), ReadingSessionRepository(db)),
            settings(db),
        )

    private fun importer(db: AppDatabase) =
        ImportArchiveUseCase(
            ImportDataUseCase(
                MediaRepository(db),
                BookRepository(db),
                ReadingSessionRepository(db),
                ImportWriteRepository(db),
            ),
            settings(db),
        )

    /** A watched show with three episodes and a watched film. */
    private suspend fun seed() {
        val show =
            (
                TVShowRepository(
                    source,
                ).addShow(
                    title = "Fleabag",
                    seasons = listOf(SeasonQuickFill(1, episodeCount = 3)),
                ) as Resource.Success
            ).data
        val e1 = source.episodeDao().getByMediaIdAndSeason(show, 1).first()
        source.tvWriteDao().markEpisodesWatchedAt(mapOf(e1.id to t1))
        MovieRepository(source).addMovie(title = "Heat", status = WatchStatus.WATCHED)
    }

    private suspend fun zip(db: AppDatabase = source) = (exporter(db).export("test") as Resource.Success).data.bytes

    private suspend fun titles(db: AppDatabase) =
        MediaRepository(db)
            .observeAllMediaWithDetails()
            .first()
            .map { it.item.title }
            .sorted()

    private fun rewrite(
        zip: ByteArray,
        manifest: (ExportManifest) -> ExportManifest = { it },
        entry: Pair<String, (String) -> String>? = null,
    ): ByteArray {
        val json = Json { ignoreUnknownKeys = true }
        val entries = unzipEntries(zip).toMutableMap()
        val old =
            json.decodeFromString(
                ExportManifest.serializer(),
                entries.getValue(ExportArchive.MANIFEST).decodeToString(),
            )
        entries[ExportArchive.MANIFEST] =
            json.encodeToString(ExportManifest.serializer(), manifest(old)).encodeToByteArray()
        entry?.let { (name, f) -> entries[name] = f(entries.getValue(name).decodeToString()).encodeToByteArray() }
        return zipEntries(entries.toList())
    }

    private suspend fun ok(r: Resource<ImportSummary>) = assertIs<Resource.Success<ImportSummary>>(r).data

    @Test
    fun zipRoundTrip_isLossless() =
        runTest {
            seed()
            val summary = ok(importer(target).import(zip(), DuplicatePolicy.SKIP, restoreSettings = false))
            assertEquals(emptyList(), summary.notes)
            assertEquals(listOf("Fleabag", "Heat"), titles(target))
            assertEquals(3, MediaRepository(target).observeAllEpisodes().first().size)
            assertEquals(
                2,
                MediaRepository(target).observeAllViewings().first().size,
                "one episode and one film viewing",
            )
        }

    @Test
    fun reimport_createsNothing() =
        runTest {
            seed()
            val z = zip()
            importer(target).import(z, DuplicatePolicy.SKIP, false)
            val again = ok(importer(target).import(z, DuplicatePolicy.SKIP, false))
            assertEquals(0, again.viewingsImported)
            assertEquals(2, again.viewingsSkipped)
        }

    @Test
    fun newerVersion_withRestoreTicked_changesNothing() =
        runTest {
            seed()
            settings(source).setIncludeSpecials(false)
            val tampered = rewrite(zip(), manifest = { it.copy(formatVersion = EXPORT_FORMAT_VERSION + 1) })
            val r = importer(target).import(tampered, DuplicatePolicy.SKIP, restoreSettings = true)
            assertEquals(ExportArchive.NEWER, assertIs<Resource.Error>(r).message)
            assertEquals(emptyList(), titles(target))
            assertNull(settings(target).getString("include_specials"))
        }

    @Test
    fun restoreSettings_false_leavesSettings() =
        runTest {
            settings(source).setIncludeSpecials(false)
            val summary = ok(importer(target).import(zip(), DuplicatePolicy.SKIP, restoreSettings = false))
            assertEquals(0, summary.settingsRestored)
            assertNull(settings(target).getString("include_specials"))
        }

    @Test
    fun restoreSettings_true_appliesAllFour() =
        runTest {
            settings(source).setIncludeSpecials(false)
            settings(source).setWeekStartDay(WeekStartDay.MONDAY)
            settings(source).setLogVerbosity(LogLevel.WARN)
            settings(source).setReadingGoal(year = 2026, books = 12)
            val summary = ok(importer(target).import(zip(), DuplicatePolicy.SKIP, restoreSettings = true))
            assertEquals(4, summary.settingsRestored)
            assertEquals(ReadingGoal(2026, 12), settings(target).observeReadingGoal().first())
            assertEquals(false, settings(target).getIncludeSpecials())
            assertEquals(WeekStartDay.MONDAY, settings(target).getWeekStartDay())
            assertEquals(LogLevel.WARN, settings(target).getLogVerbosity())
        }

    @Test
    fun invalidSettingValue_isSkipped_andNoted() =
        runTest {
            val bad = rewrite(zip(), manifest = { it.copy(settings = mapOf("log_verbosity" to "LOUD")) })
            val summary = ok(importer(target).import(bad, DuplicatePolicy.SKIP, restoreSettings = true))
            assertEquals(0, summary.settingsRestored)
            assertNull(settings(target).getString("log_verbosity"))
            assertTrue(summary.notes.any { "log_verbosity" in it }, "notes: ${summary.notes}")
        }

    @Test
    fun anInvalidReadingGoal_isSkipped_andNoted() =
        runTest {
            for (bad in listOf("2026:0", "abc")) {
                val zipWithBadGoal = rewrite(zip(), manifest = { it.copy(settings = mapOf("reading_goal" to bad)) })
                val summary = ok(importer(target).import(zipWithBadGoal, DuplicatePolicy.SKIP, restoreSettings = true))
                assertEquals(0, summary.settingsRestored, "\"$bad\" must not be restored")
                assertNull(settings(target).getString("reading_goal"))
                assertTrue(summary.notes.any { "reading_goal" in it }, "notes: ${summary.notes}")
            }
        }

    @Test
    fun unknownSettingKey_isIgnored_andNoted() =
        runTest {
            val sneaky = rewrite(zip(), manifest = { it.copy(settings = mapOf("google_books_api_key" to "x")) })
            val summary = ok(importer(target).import(sneaky, DuplicatePolicy.SKIP, restoreSettings = true))
            assertNull(settings(target).getString("google_books_api_key"))
            assertTrue(summary.notes.any { "google_books_api_key" in it }, "notes: ${summary.notes}")
        }

    @Test
    fun rowCountMismatch_isANote_notARefusal() =
        runTest {
            seed()
            val off =
                rewrite(zip(), manifest = { m ->
                    m.copy(
                        files =
                            m.files.map {
                                if (it.name ==
                                    ExportArchive.LIBRARY
                                ) {
                                    it.copy(rows = it.rows + 1)
                                } else {
                                    it
                                }
                            },
                    )
                })
            val summary = ok(importer(target).import(off, DuplicatePolicy.SKIP, false))
            assertEquals(listOf("Fleabag", "Heat"), titles(target))
            assertEquals(1, summary.notes.count { ExportArchive.LIBRARY in it }, "notes: ${summary.notes}")
        }

    @Test
    fun crlfAndBom_importCleanly() =
        runTest {
            seed()
            // As a spreadsheet saves it: a BOM, and LF endings where the export wrote CRLF.
            val resaved = rewrite(zip(), entry = ExportArchive.LIBRARY to { "\uFEFF" + it.replace("\r\n", "\n") })
            val summary = ok(importer(target).import(resaved, DuplicatePolicy.SKIP, false))
            assertEquals(emptyList(), summary.notes)
            assertEquals(listOf("Fleabag", "Heat"), titles(target))
        }

    @Test
    fun aLoneLibraryCsv_imports() =
        runTest {
            seed()
            val library =
                (
                    ExportDataUseCase(
                        MediaRepository(source),
                        BookRepository(source),
                        ReadingSessionRepository(source),
                    ).execute() as Resource.Success
                ).data.libraryCsv
            ok(importer(target).import(library.encodeToByteArray(), DuplicatePolicy.SKIP, false))
            assertEquals(listOf("Fleabag", "Heat"), titles(target))
        }

    @Test
    fun aLoneLibraryCsvWithABom_imports() =
        runTest {
            // Excel's "CSV UTF-8" adds a byte-order mark (final review, #196).
            seed()
            val library =
                (
                    ExportDataUseCase(MediaRepository(source), BookRepository(source), ReadingSessionRepository(source))
                        .execute() as Resource.Success
                ).data.libraryCsv
            val withBom = "\uFEFF" + library
            assertEquals(0xFEFF, withBom.first().code, "positive control: the BOM is really there")
            ok(importer(target).import(withBom.encodeToByteArray(), DuplicatePolicy.SKIP, false))
            assertEquals(listOf("Fleabag", "Heat"), titles(target))
        }

    @Test
    fun aSettingThatFailsToSave_isANote_andTheImportStillSucceeds() =
        runTest {
            // CodeRabbit (#199): the library is already imported when settings are restored, so a failing
            // settings write must not throw that away.
            seed()
            settings(source).setIncludeSpecials(false)
            val zip = zip()
            val failingSettings =
                SettingsRepository(
                    object : AppSettingsDao by target.appSettingsDao() {
                        override suspend fun upsert(setting: AppSettingEntity): Unit =
                            throw IllegalStateException("disk full")
                    },
                )
            val importer =
                ImportArchiveUseCase(
                    ImportDataUseCase(
                        MediaRepository(target),
                        BookRepository(target),
                        ReadingSessionRepository(target),
                        ImportWriteRepository(target),
                    ),
                    failingSettings,
                )
            val summary = ok(importer.import(zip, DuplicatePolicy.SKIP, restoreSettings = true))
            assertEquals(listOf("Fleabag", "Heat"), titles(target), "positive control: the library was imported")
            assertEquals(0, summary.settingsRestored)
            assertTrue(summary.notes.any { "include_specials" in it }, "notes: ${summary.notes}")
        }

    @Test
    fun aLoneCsvThatIsNotTheLibrary_isRefusedWithAHint() =
        runTest {
            // An old loose export's reading_logs_export.csv, picked by mistake (#196 re-grade).
            seed()
            val logs =
                (
                    ExportDataUseCase(MediaRepository(source), BookRepository(source), ReadingSessionRepository(source))
                        .execute() as Resource.Success
                ).data.episodesCsv
            val r = importer(target).import(logs.encodeToByteArray(), DuplicatePolicy.SKIP, false)
            assertEquals(ExportArchive.NOT_LIBRARY_CSV, assertIs<Resource.Error>(r).message)
            assertEquals(emptyList(), titles(target))
        }

    @Test
    fun aNonCsvFile_isRefused() =
        runTest {
            val png =
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64) { it.toByte() }
            assertIs<Resource.Error>(importer(target).import(png, DuplicatePolicy.SKIP, false))
            assertEquals(emptyList(), titles(target))
        }
}
