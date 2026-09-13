package com.github.maskedkunisquat.mediatracker.ui.screens

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.MediaTrackerApplication
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.hub.media.core.util.Resource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Characterization tests for the four SAF launcher chains in `SettingsScreenRoute` (export,
 * import, backup, restore) -- pinned before #81 splits that route into smaller composables, so the
 * split can be checked against this file rather than against "looks the same on screen." Covers
 * the happy path and every cancel branch each chain's own comments call out.
 *
 * ### Mechanism
 * `rememberLauncherForActivityResult` resolves its registry from
 * `LocalActivityResultRegistryOwner`. [RecordingActivityResultRegistry] overrides `onLaunch` to
 * record the request instead of starting a real activity; each test answers a pick with
 * `dispatchResult(requestCode, output)` on the main thread (`composeRule.runOnIdle {}`), where a
 * `null` output is what a cancelled picker looks like. Picked/created documents are plain `file://`
 * `Uri`s under this test's own cache subdirectory -- the app's `copyFileToUri`/`copyUriToFile`/
 * `readCsvFromUri`/`writeCsvToUri` all go through `ContentResolver`, which handles `file://` the
 * same as `content://`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRouteSafChainsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentationContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val application: MediaTrackerApplication
        get() = instrumentationContext.applicationContext as MediaTrackerApplication

    private fun string(
        @StringRes id: Int,
    ): String = instrumentationContext.getString(id)

    private lateinit var registry: RecordingActivityResultRegistry
    private lateinit var workDir: File

    /** Temp files that existed before this test, so [tearDown] only deletes what the test created. */
    private var restoreIncomingBefore: Set<File> = emptySet()
    private var stagedBackupBefore: Set<File> = emptySet()

    /** Files this test created outside of [workDir] (e.g. a backup staged in the database directory). */
    private val extraFilesToDelete = mutableListOf<File>()

    @Before
    fun setUp() {
        registry = RecordingActivityResultRegistry()
        workDir = File(instrumentationContext.cacheDir, "safChainsTest").apply { mkdirs() }
        restoreIncomingBefore = restoreIncomingFiles().toSet()
        stagedBackupBefore = stagedBackupFiles().toSet()

        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides FakeRegistryOwner(registry)) {
                SettingsScreenRoute(
                    appContainer = application.appContainer,
                    onNavigateBack = {},
                    onNavigateToLogViewer = {},
                    onNavigateToChangelog = {},
                    onNavigateToAbout = {},
                    onNavigateToMismatchReview = {},
                )
            }
        }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
        extraFilesToDelete.forEach { it.delete() }
        extraFilesToDelete.clear()
        // A test that fails before production cleanup runs would otherwise leave a whole-database copy
        // behind on the device.
        (restoreIncomingFiles() - restoreIncomingBefore).forEach { it.delete() }
        (stagedBackupFiles() - stagedBackupBefore).forEach { it.delete() }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun scrollToAndClick(text: String) {
        // Clickable, not just text: the Goodreads row's label and button share the same words.
        val button = hasText(text) and hasClickAction()
        composeRule.onNodeWithTag(TestTags.Settings.LIST).performScrollToNode(button)
        composeRule.onNode(button).performClick()
    }

    private fun nodeCountWithText(text: String): Int = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** Waits for [registry] to have recorded exactly [count] launches so far. */
    private fun awaitLaunchCount(count: Int) {
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) { registry.launches.size == count }
    }

    /** Answers the most recently recorded launch, on the main thread, as `dispatchResult` requires. */
    private fun answerLatestLaunch(output: Any?) {
        val requestCode = registry.launches.last().requestCode
        composeRule.runOnIdle { registry.dispatchResult(requestCode, output) }
    }

    private fun awaitTextVisible(text: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) { nodeCountWithText(text) > 0 }
    }

    private fun awaitTextGone(text: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) { nodeCountWithText(text) == 0 }
    }

    private fun awaitFileGone(file: File) {
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) { !file.exists() }
    }

    /** Writes [content] to a fresh file under [workDir] with [name] and returns its `Uri`. */
    private fun sourceUri(
        name: String,
        content: String,
    ): Uri {
        val file = File(workDir, name)
        file.writeText(content)
        return Uri.fromFile(file)
    }

    /**
     * A real export of the current library, produced directly (bypassing the SAF chain) so the
     * import scenarios below have something to import that changes nothing: with the default
     * (SKIP) duplicate policy, importing the app's own current data back into itself is a no-op.
     */
    private fun currentExportBundle() =
        runBlocking {
            (application.appContainer.exportDataUseCase.execute() as Resource.Success).data
        }

    /** The database directory's own backup-staging files -- see [DATABASE_FILE_NAME]'s KDoc. */
    private fun stagedBackupFiles(): List<File> {
        val dir = instrumentationContext.getDatabasePath(DATABASE_FILE_NAME).parentFile ?: return emptyList()
        return dir
            .listFiles { f -> f.name.startsWith("$DATABASE_FILE_NAME.backup-") && f.name.endsWith(".tmp") }
            ?.toList()
            .orEmpty()
    }

    private fun restoreIncomingFiles(): List<File> =
        instrumentationContext.cacheDir
            .listFiles { f -> f.name.startsWith("restore-incoming-") && f.name.endsWith(".tmp") }
            ?.toList()
            .orEmpty()

    /** A `file://` Uri inside a directory that does not exist, so opening it for read or write fails. */
    private fun unopenableUri(): Uri = Uri.fromFile(File(workDir, "missing-dir/unopenable.csv"))

    // ---- export --------------------------------------------------------------------------------------

    private fun runExportHappyPath(): Triple<File, File, File> {
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)
        val libraryFile = File(workDir, "library_export.csv")
        answerLatestLaunch(Uri.fromFile(libraryFile))

        awaitLaunchCount(2)
        val readingLogsFile = File(workDir, "reading_logs_export.csv")
        answerLatestLaunch(Uri.fromFile(readingLogsFile))

        awaitLaunchCount(3)
        val episodesFile = File(workDir, "episodes_export.csv")
        answerLatestLaunch(Uri.fromFile(episodesFile))

        awaitTextVisible(string(R.string.export_success_message))
        return Triple(libraryFile, readingLogsFile, episodesFile)
    }

    @Test
    fun export_happyPath_writesThreeNonEmptyFilesInOrder() {
        val (library, readingLogs, episodes) = runExportHappyPath()

        listOf(library, readingLogs, episodes).forEach { file ->
            assertTrue("${file.name} was not written", file.exists() && file.length() > 0)
            // Each exporter emits at least a header line -- see LibraryCsvExporter/
            // ReadingLogCsvExporter/EpisodeCsvExporter.
            assertTrue("${file.name} has no header line", file.readLines().isNotEmpty())
        }

        val inputs = registry.launches.map { it.input }
        assertEquals(
            listOf("library_export.csv", "reading_logs_export.csv", "episodes_export.csv"),
            inputs,
        )
    }

    @Test
    fun export_cancelAtFirstPicker_showsCancelledAndLaunchesNoMore() {
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)

        answerLatestLaunch(null)

        awaitTextVisible(string(R.string.export_cancelled_message))
        // Give a genuine second launch a chance to appear before asserting its absence.
        composeRule.waitForIdle()
        assertEquals(1, registry.launches.size)
    }

    @Test
    fun export_cancelAtSecondPicker_showsCancelledAndFirstFileWasWritten() {
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)
        val libraryFile = File(workDir, "library_export.csv")
        answerLatestLaunch(Uri.fromFile(libraryFile))

        awaitLaunchCount(2)
        answerLatestLaunch(null)

        awaitTextVisible(string(R.string.export_cancelled_message))
        composeRule.waitForIdle()
        assertEquals(2, registry.launches.size)
        assertTrue("first file was not written before the cancel", libraryFile.exists() && libraryFile.length() > 0)
    }

    @Test
    fun export_cancelAtThirdPicker_showsCancelled() {
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)
        answerLatestLaunch(Uri.fromFile(File(workDir, "library_export.csv")))

        awaitLaunchCount(2)
        answerLatestLaunch(Uri.fromFile(File(workDir, "reading_logs_export.csv")))

        awaitLaunchCount(3)
        answerLatestLaunch(null)

        awaitTextVisible(string(R.string.export_cancelled_message))
    }

    @Test
    fun export_writeFailsAtFirstPicker_showsFailureAndLaunchesNoMore() {
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)

        answerLatestLaunch(unopenableUri())

        awaitTextVisible(string(R.string.export_failure_message))
        assertEquals(1, registry.launches.size)
    }

    @Test
    fun export_writeFailsAtThirdPicker_showsFailure() {
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)
        answerLatestLaunch(Uri.fromFile(File(workDir, "library_export.csv")))

        awaitLaunchCount(2)
        answerLatestLaunch(Uri.fromFile(File(workDir, "reading_logs_export.csv")))

        awaitLaunchCount(3)
        answerLatestLaunch(unopenableUri())

        awaitTextVisible(string(R.string.export_failure_message))
    }

    // ---- import --------------------------------------------------------------------------------------

    @Test
    fun import_cancelAtFirstPicker_showsCancelledAndLaunchesNoMore() {
        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)
        assertMimeArray(registry.launches.single().input)

        answerLatestLaunch(null)

        awaitTextVisible(string(R.string.import_cancelled_message))
        composeRule.waitForIdle()
        assertEquals(1, registry.launches.size)
    }

    /**
     * Later pickers cancelled is a legitimate "no reading logs / no episodes" import, per
     * `readingLogsImportLauncher`'s and `episodesImportLauncher`'s own comments -- the import still
     * runs on whatever was gathered, rather than being treated as an overall cancel.
     */
    @Test
    fun import_libraryPickedLaterPickersCancelled_stillRunsAndShowsSummary() {
        val bundle = currentExportBundle()
        val libraryUri = sourceUri("library_export.csv", bundle.libraryCsv)

        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)
        answerLatestLaunch(libraryUri)

        awaitLaunchCount(2)
        answerLatestLaunch(null)

        awaitLaunchCount(3)
        answerLatestLaunch(null)

        awaitTextVisible(string(R.string.import_summary_title))
        composeRule.onNodeWithText(string(R.string.ok_button)).performClick()
    }

    @Test
    fun import_happyPath_allThreePicked_showsSummary() {
        val bundle = currentExportBundle()

        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)
        answerLatestLaunch(sourceUri("library_export.csv", bundle.libraryCsv))

        awaitLaunchCount(2)
        answerLatestLaunch(sourceUri("reading_logs_export.csv", bundle.readingLogsCsv))

        awaitLaunchCount(3)
        answerLatestLaunch(sourceUri("episodes_export.csv", bundle.episodesCsv))

        awaitTextVisible(string(R.string.import_summary_title))
        composeRule.onNodeWithText(string(R.string.ok_button)).performClick()
    }

    @Test
    fun import_unreadableLibraryFile_showsFailureAndLaunchesNoMore() {
        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)

        answerLatestLaunch(unopenableUri())

        awaitTextVisible(string(R.string.import_failure_message))
        assertEquals(1, registry.launches.size)
    }

    /** A failed read of a file the user did pick abandons the chain, unlike a cancel of that picker. */
    @Test
    fun import_unreadableReadingLogsFile_showsFailureAndSkipsEpisodesPicker() {
        val bundle = currentExportBundle()

        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)
        answerLatestLaunch(sourceUri("library_export.csv", bundle.libraryCsv))

        awaitLaunchCount(2)
        answerLatestLaunch(unopenableUri())

        awaitTextVisible(string(R.string.import_failure_message))
        assertEquals(2, registry.launches.size)
        assertEquals(0, nodeCountWithText(string(R.string.import_summary_title)))
    }

    @Test
    fun import_unreadableEpisodesFile_showsFailureAndNoSummary() {
        val bundle = currentExportBundle()

        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)
        answerLatestLaunch(sourceUri("library_export.csv", bundle.libraryCsv))

        awaitLaunchCount(2)
        answerLatestLaunch(null)

        awaitLaunchCount(3)
        answerLatestLaunch(unopenableUri())

        awaitTextVisible(string(R.string.import_failure_message))
        assertEquals(0, nodeCountWithText(string(R.string.import_summary_title)))
    }

    @Test
    fun importGoodreads_unreadableFile_showsFailure() {
        scrollToAndClick(string(R.string.settings_import_goodreads_button))
        awaitLaunchCount(1)

        answerLatestLaunch(unopenableUri())

        awaitTextVisible(string(R.string.import_failure_message))
    }

    @Test
    fun importGoodreads_launchesOnceThenCancelShowsCancelled() {
        scrollToAndClick(string(R.string.settings_import_goodreads_button))
        awaitLaunchCount(1)
        assertMimeArray(registry.launches.single().input)

        answerLatestLaunch(null)

        awaitTextVisible(string(R.string.import_cancelled_message))
        composeRule.waitForIdle()
        assertEquals(1, registry.launches.size)
    }

    private fun assertMimeArray(input: Any?) {
        @Suppress("UNCHECKED_CAST")
        val array = input as? Array<String>
        assertTrue("unexpected OpenDocument input: $input", array != null && array.contentEquals(arrayOf("text/*")))
    }

    // ---- backup --------------------------------------------------------------------------------------

    @Test
    fun backup_happyPath_writesDestinationAndDeletesStagedFile() {
        val before = stagedBackupFiles()

        scrollToAndClick(string(R.string.settings_backup_button))
        awaitLaunchCount(1)

        val suggestedName = registry.launches.single().input as String
        assertTrue(
            "unexpected suggested backup file name: $suggestedName",
            suggestedName.isNotBlank() && suggestedName.startsWith("media_tracker_backup_"),
        )

        val staged = (stagedBackupFiles() - before.toSet()).singleOrNull()
        checkNotNull(staged) { "expected exactly one new staged backup file" }
        assertTrue("staged backup file is missing or empty", staged.exists() && staged.length() > 0)

        val destination = File(workDir, "restored.db")
        answerLatestLaunch(Uri.fromFile(destination))

        awaitTextVisible(string(R.string.backup_success_message))
        assertTrue("backup destination file was not written", destination.exists() && destination.length() > 0)
        awaitFileGone(staged)
    }

    @Test
    fun backup_cancel_showsCancelledAndDeletesStagedFile() {
        val before = stagedBackupFiles()

        scrollToAndClick(string(R.string.settings_backup_button))
        awaitLaunchCount(1)

        val staged = (stagedBackupFiles() - before.toSet()).singleOrNull()
        checkNotNull(staged) { "expected exactly one new staged backup file" }

        answerLatestLaunch(null)

        awaitTextVisible(string(R.string.backup_cancelled_message))
        awaitFileGone(staged)
    }

    // ---- restore -------------------------------------------------------------------------------------

    @Test
    fun restore_cancel_showsCancelled() {
        scrollToAndClick(string(R.string.settings_restore_button))
        awaitLaunchCount(1)

        answerLatestLaunch(null)

        awaitTextVisible(string(R.string.restore_cancelled_message))
    }

    @Test
    fun restore_unreadableFile_showsReadFailureAndLeavesNoTempFile() {
        scrollToAndClick(string(R.string.settings_restore_button))
        awaitLaunchCount(1)

        answerLatestLaunch(unopenableUri())

        awaitTextVisible(string(R.string.restore_read_failure_message))
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) { restoreIncomingFiles().isEmpty() }
    }

    @Test
    fun restore_junkFile_isRejectedAndLeavesNoTempFile() {
        val junkUri = sourceUri("not-a-database.db", "this is not a sqlite file")

        scrollToAndClick(string(R.string.settings_restore_button))
        awaitLaunchCount(1)
        answerLatestLaunch(junkUri)

        // The literal text is RestoreDatabaseUseCase.stage's own rejection message (not a string
        // resource -- it lives in the shared/common module, which has no Android resources).
        awaitTextVisible(NOT_A_SQLITE_FILE_MESSAGE)

        assertEquals(0, nodeCountWithText(string(R.string.restore_confirm_title)))
        composeRule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS) { restoreIncomingFiles().isEmpty() }
    }

    @Test
    fun restore_realBackup_showsConfirmationThenCancelDeletesStagedFile() {
        val backupResult =
            runBlocking {
                (application.appContainer.backupDatabaseUseCase.execute() as Resource.Success).data
            }
        extraFilesToDelete += File(backupResult.stagedFilePath)

        scrollToAndClick(string(R.string.settings_restore_button))
        awaitLaunchCount(1)
        answerLatestLaunch(Uri.fromFile(File(backupResult.stagedFilePath)))

        awaitTextVisible(string(R.string.restore_confirm_title))

        val stagedIncoming = restoreIncomingFiles().singleOrNull()
        checkNotNull(stagedIncoming) { "expected exactly one staged restore-incoming file" }

        // NEVER press confirm here: it closes the AppContainer and kills the process.
        composeRule.onNodeWithText(string(R.string.restore_cancel_button)).performClick()

        awaitTextGone(string(R.string.restore_confirm_title))
        awaitFileGone(stagedIncoming)
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS = 15_000L

        /**
         * Matches `DatabaseFactory.android.kt`'s `APP_DATABASE_FILE_NAME` constant -- not
         * importable across modules (it's `internal` to `shared`), so pinned here by value. A
         * rename of that constant is the one change that would need this updated too.
         */
        const val DATABASE_FILE_NAME = "media_tracker.db"

        /** See `DefaultRestoreDatabaseUseCase.stage`'s "not a SQLite database" rejection branch. */
        const val NOT_A_SQLITE_FILE_MESSAGE =
            "This doesn't look like a MediaTracker backup file (not a SQLite database). " +
                "Nothing was changed."
    }
}

/** Records every launch instead of starting a real activity; see this file's class KDoc. */
private class RecordingActivityResultRegistry : ActivityResultRegistry() {
    data class Launch(
        val requestCode: Int,
        val input: Any?,
    )

    val launches: MutableList<Launch> = CopyOnWriteArrayList()

    override fun <I : Any?, O : Any?> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launches.add(Launch(requestCode, input))
    }
}

private class FakeRegistryOwner(
    override val activityResultRegistry: ActivityResultRegistry,
) : ActivityResultRegistryOwner
