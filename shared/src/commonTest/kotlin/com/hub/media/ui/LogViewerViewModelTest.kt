package com.hub.media.ui

import com.hub.media.core.storage.LogFileStore
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [LogViewerViewModel]'s snapshot/boundary model (ROADMAP Task 15 Phase B2). The boundary is
 * the part worth testing hard: it is the one piece of this screen that can be silently wrong --
 * a divider in the wrong place still renders, it just lies about which entries are new.
 *
 * Uses a real [LogFileStore] over a temp directory rather than a fake, so these exercise the actual
 * `readRecent` ordering and sequence numbering the boundary comparison depends on. `flushInterval`
 * is 0 throughout so nothing flushes behind the test's back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerViewModelTest {
    private lateinit var tempDir: String
    private val viewModels = ViewModelRegistry()

    // Shut down from tearDown rather than at the end of each test body: a failing assertion returns
    // early, which would otherwise leak the store's background scope for the rest of the run.
    private val stores = mutableListOf<LogFileStore>()

    @BeforeTest
    fun setUp() =
        runTest {
            viewModels.installMain()
            tempDir = createTestTempDir()
        }

    @AfterTest
    fun tearDown() =
        runTest {
            viewModels.clearAll()
            stores.forEach { it.shutdown() }
            cleanupTestTempDir(tempDir)
            Dispatchers.resetMain()
        }

    private fun newStore() = track(LogFileStore(directoryPath = tempDir, flushIntervalMillis = 0))

    private fun track(store: LogFileStore): LogFileStore = store.also { stores += it }

    private fun viewModel(store: LogFileStore) = viewModels.track(LogViewerViewModel(store))

    /**
     * Waits for an in-flight load/refresh to finish. Real-time polling, not virtual: the store's
     * reads suspend on Dispatchers.IO, which runTest's scheduler does not control, so advancing
     * virtual time alone would never observe them complete.
     */
    private suspend fun LogViewerViewModel.awaitLoaded() {
        var attempts = 0
        while (uiState.value.isLoading && attempts < 400) {
            withContext(Dispatchers.Default) { delay(5) }
            attempts++
        }
        assertTrue(!uiState.value.isLoading, "load/refresh did not complete within 2s")
    }

    private suspend fun LogFileStore.log(vararg messages: String) {
        messages.forEach { append(LogLevel.WARN, "T", it) }
        flush()
    }

    @Test
    fun uiState_onFirstOpen_hasNoBoundaryBecauseNothingIsNewYet() =
        runTest {
            val store = newStore()
            store.log("a", "b")

            val vm = viewModel(store)
            vm.awaitLoaded()

            assertEquals(
                listOf("a", "b"),
                vm.uiState.value.entries
                    .map { it.message },
            )
            assertNull(vm.uiState.value.newEntryBoundary, "first open has nothing to mark as new")
            assertNull(vm.uiState.value.firstNewEntryIndex)
        }

    @Test
    fun refresh_withEntriesAddedSinceOpen_marksTheDividerAtTheFirstNewEntry() =
        runTest {
            val store = newStore()
            store.log("old-1", "old-2")
            val vm = viewModel(store)
            vm.awaitLoaded()

            store.log("new-1", "new-2")
            vm.refresh()
            vm.awaitLoaded()

            val state = vm.uiState.value
            // Positive control: the refresh genuinely brought the new entries in. Without this, the
            // index assertion below could pass on a snapshot that never changed.
            assertEquals(listOf("old-1", "old-2", "new-1", "new-2"), state.entries.map { it.message })
            assertEquals(2, state.firstNewEntryIndex, "divider must sit immediately above new-1")
        }

    @Test
    fun refresh_calledRepeatedly_movesTheDividerToEachRefreshsOwnNewEntries() =
        runTest {
            val store = newStore()
            store.log("old")
            val vm = viewModel(store)
            vm.awaitLoaded()

            store.log("batch1")
            vm.refresh()
            vm.awaitLoaded()
            assertEquals(1, vm.uiState.value.firstNewEntryIndex)

            // The second refresh must re-anchor on the *current* snapshot, not keep pointing at the
            // first refresh's boundary -- the failure mode a boundary derived after reloading, or one
            // stored as an index, would both produce.
            store.log("batch2")
            vm.refresh()
            vm.awaitLoaded()

            val state = vm.uiState.value
            assertEquals(listOf("old", "batch1", "batch2"), state.entries.map { it.message })
            assertEquals(2, state.firstNewEntryIndex, "divider must follow batch2, not stay at batch1")
        }

    @Test
    fun refresh_withNothingNewSinceOpen_marksNothing() =
        runTest {
            val store = newStore()
            store.log("only")
            val vm = viewModel(store)
            vm.awaitLoaded()

            vm.refresh()
            vm.awaitLoaded()

            val state = vm.uiState.value
            assertEquals(listOf("only"), state.entries.map { it.message }, "snapshot is unchanged")
            assertNull(
                state.firstNewEntryIndex,
                "a boundary equal to the highest seq present must match no entry, so no divider renders",
            )
        }

    @Test
    fun refresh_whenOlderEntriesRotatedOffDiskInBetween_stillMarksTheCorrectEntry() =
        runTest {
            // The case a position- or count-based boundary gets wrong: the window the second read
            // returns starts later than the first, so every index shifts. Comparing seq is immune.
            // A tiny cap forces a real rotation between the two reads.
            val store =
                track(
                    LogFileStore(
                        directoryPath = tempDir,
                        maxFileSizeBytes = 200L,
                        flushIntervalMillis = 0,
                    ),
                )
            store.log("old-1", "old-2")
            val vm = viewModel(store)
            vm.awaitLoaded()
            val boundarySeq =
                vm.uiState.value.entries
                    .maxOf { it.seq }

            repeat(20) { store.log("filler-$it") }
            vm.refresh()
            vm.awaitLoaded()

            val state = vm.uiState.value
            val firstNew =
                assertNotNull(
                    state.firstNewEntryIndex,
                    "entries were added, so a divider must be placed",
                )
            assertTrue(
                state.entries[firstNew].seq > boundarySeq,
                "the marked entry must be genuinely newer than the pre-refresh snapshot",
            )
            assertTrue(
                firstNew == 0 || state.entries[firstNew - 1].seq <= boundarySeq,
                "the entry before the divider must not itself be new",
            )
        }

    @Test
    fun readFullLogForExport_returnsEveryRetainedEntryNotJustTheWindow() =
        runTest {
            val store = newStore()
            store.log("first", "second")

            val exported = viewModel(store).readFullLogForExport()

            assertTrue(exported.contains("first") && exported.contains("second"))
            assertEquals(2, exported.lines().size, "one line per entry")
        }
}
