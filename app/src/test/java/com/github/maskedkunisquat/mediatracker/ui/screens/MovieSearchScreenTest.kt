package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.hub.media.ui.MovieSearchUiState
import com.hub.media.ui.TmdbSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behavioural tests for the film search screen (ROADMAP Task 13 Phase D).
 *
 * Mirrors [TVShowSearchScreenTest] case for case: the two screens are structurally identical, so a
 * behaviour fixed on one and not the other shows up here as a missing test rather than only in the
 * app.
 *
 * In `app/src/test/` rather than `androidTest/` deliberately. The screen is stateless -- every value
 * arrives in [MovieSearchUiState] and every action is a callback -- so it needs no device and no
 * database, and here it runs inside `:app:testDebugUnitTest`, which is a required CI check. A
 * behavioural test that only runs when somebody remembers to attach a phone is the gap #83 was
 * opened about.
 *
 * What is deliberately *not* here: that the route hands this screen real callbacks rather than
 * stubs. Only a test starting at the real `MainActivity` can prove that (AGENTS.md section 7), and
 * that lane needs a device.
 */
@RunWith(RobolectricTestRunner::class)
class MovieSearchScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setContent(
        uiState: MovieSearchUiState,
        onSearch: () -> Unit = {},
        onResultClick: (Int) -> Unit = {},
        onQueryChange: (String) -> Unit = {},
        onNavigateToManualEntry: () -> Unit = {},
    ) {
        composeRule.setContent {
            Fixture(
                uiState = uiState,
                onSearch = onSearch,
                onResultClick = onResultClick,
                onQueryChange = onQueryChange,
                onNavigateToManualEntry = onNavigateToManualEntry,
            )
        }
    }

    @Composable
    private fun Fixture(
        uiState: MovieSearchUiState,
        onSearch: () -> Unit,
        onResultClick: (Int) -> Unit,
        onQueryChange: (String) -> Unit,
        onNavigateToManualEntry: () -> Unit,
    ) {
        MovieSearchScreen(
            uiState = uiState,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onResultClick = onResultClick,
            onDismissError = {},
            onNavigateBack = {},
            onNavigateToManualEntry = onNavigateToManualEntry,
        )
    }

    // ---- the empty list means two different things ---------------------------------------------

    @Test
    fun beforeAnySearch_showsThePromptAndNotNoResults() {
        setContent(MovieSearchUiState())

        composeRule.onNodeWithText(context.getString(R.string.movie_search_prompt)).assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.movie_search_no_results))
            .assertDoesNotExist()
    }

    @Test
    fun afterASearchThatMatchedNothing_showsNoResultsAndNotThePrompt() {
        // The distinction MovieSearchUiState.hasSearched exists for: an empty list is either "you
        // have not searched" or "nothing matched", and one message for both is wrong half the time.
        setContent(MovieSearchUiState(query = "zzz", hasSearched = true))

        composeRule.onNodeWithText(context.getString(R.string.movie_search_no_results)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.movie_search_prompt)).assertDoesNotExist()
    }

    @Test
    fun afterASearchThatFailed_saysWhyRatherThanClaimingNothingMatched() {
        // Caught on a device with no credential saved: the snackbar named the real problem while the
        // body underneath said "No shows matched that search", which is confidently wrong -- nothing
        // matched because the request never succeeded. The empty results area has to explain itself,
        // because the snackbar fades and the emptiness does not.
        val message = "No TMDB credential is set. Add one in Settings to look up films and shows."
        setContent(MovieSearchUiState(query = "chernobyl", hasSearched = true, searchError = message))

        composeRule.onNodeWithText(message).assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.movie_search_no_results))
            .assertDoesNotExist()
    }

    // ---- the search action ---------------------------------------------------------------------

    @Test
    fun searchButton_isDisabledOnABlankQuery() {
        // Disabled rather than enabled-and-ignored: a button that does nothing when pressed reads
        // as broken.
        setContent(MovieSearchUiState(query = "   "))

        composeRule.onNodeWithTag(TestTags.MovieSearch.SEARCH_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun searchButton_isEnabledOnceThereIsAQuery() {
        setContent(MovieSearchUiState(query = "chernobyl"))

        composeRule.onNodeWithTag(TestTags.MovieSearch.SEARCH_BUTTON).assertIsEnabled()
    }

    @Test
    fun searchButton_isDisabledWhileASearchIsRunning() {
        setContent(MovieSearchUiState(query = "chernobyl", isSearching = true))

        composeRule.onNodeWithTag(TestTags.MovieSearch.SEARCH_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun searchButton_invokesTheSearchCallback() {
        var searched = 0
        setContent(MovieSearchUiState(query = "chernobyl"), onSearch = { searched++ })

        composeRule.onNodeWithTag(TestTags.MovieSearch.SEARCH_BUTTON).performClick()

        assertEquals(1, searched)
    }

    @Test
    fun typing_reportsEveryChangeButNeverSearchesOnItsOwn() {
        // Search is an explicit action -- see TVShowSearchViewModel's KDoc on why there is no
        // debounce. If a keystroke ever starts a request, this is the test that says so.
        var searched = 0
        val typed = mutableListOf<String>()
        setContent(
            MovieSearchUiState(),
            onSearch = { searched++ },
            onQueryChange = { typed += it },
        )

        composeRule.onNodeWithTag(TestTags.MovieSearch.QUERY_FIELD).performTextInput("chern")

        assertTrue("the field must report what was typed", typed.isNotEmpty())
        assertEquals("typing must never issue a request", 0, searched)
    }

    // ---- results -------------------------------------------------------------------------------

    @Test
    fun tappingAResult_passesThatRowsTmdbId() {
        var clicked: Int? = null
        setContent(MovieSearchUiState(hasSearched = true, results = RESULTS), onResultClick = { clicked = it })

        composeRule.onNodeWithText("The Matrix").performClick()

        assertEquals("the tapped row's own id must be the one that is added", 603, clicked)
    }

    @Test
    fun whileOneRowIsBeingAdded_noRowResponds() {
        // A second tap would produce a second film. The ViewModel refuses re-entry too; this is the
        // half the user can see.
        var clicked: Int? = null
        setContent(
            MovieSearchUiState(hasSearched = true, results = RESULTS, addingTmdbId = 603),
            onResultClick = { clicked = it },
        )

        composeRule.onNodeWithText("The Matrix Reloaded").performClick()

        assertNull("no row may be tappable while an add is in flight", clicked)
    }

    @Test
    fun aResultWithNoKnownYear_saysSoRatherThanShowingNothing() {
        setContent(
            MovieSearchUiState(
                hasSearched = true,
                results = listOf(TmdbSearchResult(tmdbId = 1, title = "Undated Film")),
            ),
        )

        composeRule.onNodeWithText(context.getString(R.string.movie_search_unknown_year)).assertExists()
    }

    // ---- the escape hatch ----------------------------------------------------------------------

    @Test
    fun manualEntry_isOfferedEvenWhenSearchHasFailed() {
        // The reason it lives on this screen: the credential-missing failure is exactly when the
        // user needs the fallback, and it must be reachable without going back first.
        var manual = 0
        setContent(
            MovieSearchUiState(
                hasSearched = true,
                searchError = "No TMDB credential is set. Add one in Settings to look up films and shows.",
            ),
            onNavigateToManualEntry = { manual++ },
        )

        composeRule.onNodeWithTag(TestTags.MovieSearch.MANUAL_ENTRY).performClick()

        assertEquals(1, manual)
    }

    @Test
    fun manualEntry_isDisabledWhileAnAddIsInFlight() {
        // Leaving mid-add does not cancel it -- the ViewModel outlives the screen -- so returning
        // would re-run the effect that navigates on a saved id and drop the user onto the new show's
        // detail screen from wherever they had got to.
        setContent(MovieSearchUiState(hasSearched = true, results = RESULTS, addingTmdbId = 603))

        composeRule.onNodeWithTag(TestTags.MovieSearch.MANUAL_ENTRY).assertIsNotEnabled()
    }

    @Test
    fun manualEntry_isEnabledAgainOnceAnAddHasFailed() {
        // The escape hatch must come back the moment it is needed again -- an add that failed is
        // exactly when someone reaches for it.
        setContent(MovieSearchUiState(hasSearched = true, results = RESULTS, addError = "Could not reach TMDB"))

        composeRule.onNodeWithTag(TestTags.MovieSearch.MANUAL_ENTRY).assertIsEnabled()
    }

    private companion object {
        val RESULTS =
            listOf(
                TmdbSearchResult(tmdbId = 603, title = "The Matrix", year = "1999"),
                TmdbSearchResult(tmdbId = 604, title = "The Matrix Reloaded", year = "2003"),
            )
    }
}
