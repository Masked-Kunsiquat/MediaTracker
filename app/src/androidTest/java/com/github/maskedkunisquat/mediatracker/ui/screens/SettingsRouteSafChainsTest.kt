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
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import com.hub.media.features.portability.csv.ArchiveReadResult
import com.hub.media.features.portability.csv.ExportArchive
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

    /** Hosts the route, so a test can recreate it mid-chain the way a rotation inside a picker does. */
    private val restorationTester by lazy { StateRestorationTester(composeRule) }
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

        restorationTester.setContent {
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
     * A real export of the current library as the one-file zip (#196), produced directly rather than
     * through the dialog, so the import scenarios have something to import that changes nothing: with
     * the default (SKIP) duplicate policy, importing the app's own data back into itself is a no-op.
     */
    private fun currentExportZip(): ByteArray =
        runBlocking { (application.appContainer.exportArchiveUseCase.export("test") as Resource.Success).data.bytes }

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

    // ---- export (#196: one .zip, one dialog) ----------------------------------------------------------

    @Test
    fun export_happyPath_writesOneZipThatDecodes() {
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)
        val suggested = registry.launches.single().input as String
        assertTrue(
            "unexpected suggested name: $suggested",
            suggested.startsWith("media-tracker-export-") && suggested.endsWith(".zip"),
        )

        val destination = File(workDir, "export.zip")
        answerLatestLaunch(Uri.fromFile(destination))

        awaitTextVisible(string(R.string.export_success_message))
        val read = ExportArchive.decode(destination.readBytes())
        assertTrue("the written file must decode as an export: $read", read is ArchiveReadResult.Read)
        assertEquals(4, (read as ArchiveReadResult.Read).manifest.files.size)
        composeRule.waitForIdle()
        assertEquals("one dialog, not a chain", 1, registry.launches.size)
    }

    @Test
    fun export_cancel_showsCancelled() {
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)
        answerLatestLaunch(null)
        awaitTextVisible(string(R.string.export_cancelled_message))
        composeRule.waitForIdle()
        assertEquals(1, registry.launches.size)
    }

    @Test
    fun export_recreatedWhileThePickerIsOpen_writesTheZip_andOpensNoSecondPicker() {
        // The #162 class of bug: a rotation inside the dialog recreates the screen.
        scrollToAndClick(string(R.string.settings_export_button))
        awaitLaunchCount(1)

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals("a recreated screen must not open the dialog again", 1, registry.launches.size)

        val destination = File(workDir, "after-recreation.zip")
        answerLatestLaunch(Uri.fromFile(destination))
        awaitTextVisible(string(R.string.export_success_message))
        assertTrue(ExportArchive.decode(destination.readBytes()) is ArchiveReadResult.Read)
    }

    // ---- import (#196: a .zip or a lone library CSV, one dialog) ----------------------------------------

    @Test
    fun import_ofAnExportZip_showsTheSummary() {
        val zip = File(workDir, "import.zip").apply { writeBytes(currentExportZip()) }
        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)
        @Suppress("UNCHECKED_CAST")
        val mimes = registry.launches.single().input as Array<String>
        assertTrue("the dialog must offer zips: ${mimes.toList()}", "application/zip" in mimes)

        answerLatestLaunch(Uri.fromFile(zip))

        awaitTextVisible(string(R.string.import_summary_title))
        assertEquals("one dialog, not a chain", 1, registry.launches.size)
    }

    @Test
    fun import_cancel_showsCancelled() {
        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)
        answerLatestLaunch(null)
        awaitTextVisible(string(R.string.import_cancelled_message))
        composeRule.waitForIdle()
        assertEquals(1, registry.launches.size)
    }

    @Test
    fun import_unreadableFile_showsFailure() {
        scrollToAndClick(string(R.string.settings_import_button))
        awaitLaunchCount(1)
        answerLatestLaunch(unopenableUri())
        awaitTextVisible(string(R.string.import_failure_message))
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

    @Test
    fun backup_recreatedWhileThePickerIsOpen_writesTheBackup_andOpensNoSecondPicker() {
        // #162: a rotation inside the destination picker recreates the screen. The staged snapshot used
        // to live in the composable and was lost (an empty file, a leaked snapshot), and the screen
        // re-opened the picker because the ViewModel was still at Success.
        val before = stagedBackupFiles()

        scrollToAndClick(string(R.string.settings_backup_button))
        awaitLaunchCount(1)
        val staged = (stagedBackupFiles() - before.toSet()).singleOrNull()
        checkNotNull(staged) { "expected exactly one new staged backup file" }

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals("a recreated screen must not open the picker again", 1, registry.launches.size)

        val destination = File(workDir, "after-recreation.db")
        answerLatestLaunch(Uri.fromFile(destination))

        awaitTextVisible(string(R.string.backup_success_message))
        assertTrue("backup destination file was not written", destination.exists() && destination.length() > 0)
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
