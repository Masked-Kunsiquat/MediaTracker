package com.github.maskedkunisquat.mediatracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.DuplicatePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Imports the CSV fixtures under `docs/sample-data` into the debug app's real database (ROADMAP Task 14 Phase B).
 *
 * ### Why a test does this
 * The debug build installs under its own `applicationId` and therefore starts empty, which makes
 * the library UI impossible to look at on a device — nothing to long-press, filter, or select. The
 * obvious route, importing through Settings, needs a SAF file picker and real taps.
 *
 * It does not need to. [com.hub.media.features.portability.domain.ImportDataUseCase] takes CSV
 * *strings*; the picker is only the app module's way of obtaining them. So this reads the fixtures
 * from the test APK's assets and imports them directly, seeding the device in a couple of seconds.
 *
 * ### Running the suite does NOT seed your device
 * A correction worth stating plainly, because the opposite is the intuitive assumption:
 * `./gradlew :app:connectedDebugAndroidTest` **uninstalls the app when it finishes**, taking this
 * import with it. Verified directly — the package is present before the run and gone after.
 *
 * To actually leave data on a device, install the APKs and drive this test yourself, which skips
 * Gradle's teardown:
 *
 * ```
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 * adb shell am instrument -w -e class com.github.maskedkunisquat.mediatracker.SampleDataSeedTest com.github.maskedkunisquat.mediatracker.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * (one line -- the KDoc renders a leading asterisk on wrapped lines, which breaks a paste)
 *
 * [DuplicatePolicy.SKIP] makes that idempotent, so re-running tops the data up rather than
 * multiplying it.
 *
 * ### Its value inside the suite is different
 * Run by Gradle it is still the only end-to-end exercise of the CSV import path against a real
 * database on real hardware — everything else covering import runs in-memory on the JVM. It just
 * is not a seeding mechanism there.
 */
@RunWith(AndroidJUnit4::class)
class SampleDataSeedTest {

    private val application: MediaTrackerApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MediaTrackerApplication

    /** Reads a fixture from the *test* APK's assets, where the Gradle copy task puts them. */
    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).bufferedReader().use { it.readText() }

    @Test
    fun sampleData_importsIntoTheDebugAppsLibrary() = runBlocking {
        val container = application.appContainer
        val booksBefore = container.bookRepository.getAllBooksWithDetails().size

        val result = container.importDataUseCase.execute(
            libraryCsv = asset("library_sample.csv"),
            readingLogsCsv = asset("reading_logs_sample.csv"),
            duplicatePolicy = DuplicatePolicy.SKIP,
        )

        val summary = when (result) {
            is Resource.Success -> result.data
            else -> throw AssertionError("sample data failed to import: $result")
        }

        // Rejections are reported rather than fatal, so an import can "succeed" having skipped
        // every row. Unconditional: the previous `|| booksBefore > 0` meant a device that already
        // held books would pass no matter how badly the fixture failed to import.
        assertTrue(
            "no sample row should be rejected on a real device: $summary",
            summary.rejections.isEmpty(),
        )

        // Named records rather than a count, for the same reason: a count is satisfied by whatever
        // happened to be on the device already.
        val titles = container.bookRepository.getAllBooksWithDetails().map { it.mediaItem.title }
        listOf("The Way of Kings", "Beowulf", "A Wizard of Earthsea").forEach { expected ->
            assertTrue("fixture book missing after import: $expected (had $booksBefore before)", expected in titles)
        }
        assertTrue(
            "the fixture's reading sessions must import too, not just the books",
            container.readingSessionRepository.observeAllSessions().first().isNotEmpty(),
        )
    }
}
