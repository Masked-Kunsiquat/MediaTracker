package com.hub.media.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [ChangelogViewModel] (ROADMAP Task 15 Phase B2b) -- the fold state and the "open on the
 * current version" rule.
 *
 * These are unit tests rather than UI tests because the ViewModel deliberately takes its content as
 * a suspending lambda instead of touching `context.assets`; see that class's KDoc. That is what
 * makes the interesting behaviour reachable without the Compose test harness this project does not
 * yet have.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangelogViewModelTest {
    private val viewModels = ViewModelRegistry()

    private val markdown =
        """
        ## [Unreleased]

        ### Added

        - **New thing** — detail.

        ## [0.8.0] - 2026-08-08

        A preamble.

        ### Added

        - **Shipped thing** — detail.
        - **Another shipped thing** — detail.

        ## [0.7.0] - 2026-08-03

        Older preamble.

        ### Added

        - **Old thing** — detail.
        """.trimIndent()

    @BeforeTest
    fun setUp() = runTest { viewModels.installMain() }

    @AfterTest
    fun tearDown() =
        runTest {
            viewModels.clearAll()
            Dispatchers.resetMain()
        }

    private fun viewModel(
        currentVersion: String = "0.8.0",
        content: String? = markdown,
    ) = viewModels.track(ChangelogViewModel(currentVersion) { content })

    @Test
    fun init_currentVersionPresentInChangelog_opensOnThatVersionOnly() =
        runTest {
            val state = viewModel(currentVersion = "0.8.0").uiState.value

            assertEquals(setOf("0.8.0"), state.expandedVersions)
            assertFalse(state.isLoading)
            // Positive control: the other versions were genuinely parsed, so "only 0.8.0" is a choice
            // rather than the parser having found nothing else.
            assertEquals(listOf("Unreleased", "0.8.0", "0.7.0"), state.document.versions.map { it.version })
        }

    @Test
    fun init_currentVersionNotYetWrittenUp_fallsBackToTheNewestSection() =
        runTest {
            // The normal case on a development build: versionName points at a release whose notes do
            // not exist yet. Opening everything collapsed would make the screen look broken.
            val state = viewModel(currentVersion = "0.9.0").uiState.value

            assertEquals(setOf("Unreleased"), state.expandedVersions)
        }

    @Test
    fun init_assetCouldNotBeRead_reportsFailureRatherThanAnEmptyDocument() =
        runTest {
            val state = viewModel(content = null).uiState.value

            assertTrue(state.failedToLoad, "a missing asset is worth telling the user about")
            assertFalse(state.isLoading)
            assertEquals(0, state.document.versions.size)
        }

    @Test
    fun init_readableButEmptyChangelog_isNotReportedAsAFailure() =
        runTest {
            // Distinct from the case above: nothing went wrong, there is simply nothing to show.
            val state = viewModel(content = "").uiState.value

            assertFalse(state.failedToLoad)
            assertEquals(0, state.document.versions.size)
        }

    @Test
    fun toggleVersion_expandsAndCollapsesWithoutDisturbingOtherVersions() =
        runTest {
            val vm = viewModel(currentVersion = "0.8.0")

            vm.toggleVersion("0.7.0")
            assertEquals(setOf("0.8.0", "0.7.0"), vm.uiState.value.expandedVersions)

            vm.toggleVersion("0.7.0")
            assertEquals(setOf("0.8.0"), vm.uiState.value.expandedVersions, "0.8.0 must be untouched")
        }

    @Test
    fun toggleEntry_twoEntriesSharingAHeading_doNotToggleTogether() =
        runTest {
            // Why entryKey includes the index: headings repeat across releases, and two entries sharing
            // an expand state would open and close in unison for no visible reason.
            val vm = viewModel()
            val first = entryKey("0.8.0", "Added", 0)
            val second = entryKey("0.8.0", "Added", 1)

            vm.toggleEntry(first)

            assertTrue(first in vm.uiState.value.expandedEntries)
            assertFalse(second in vm.uiState.value.expandedEntries)
        }
}
