package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.tv.domain.WatchedWhen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The film "Watched" dialog's optional stars (#231): saved with the viewing, or left unrated. */
@RunWith(RobolectricTestRunner::class)
class FilmWatchedDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val confirmed = mutableListOf<Pair<WatchedWhen, Int?>>()

    private fun show(rateable: Boolean = true) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                FilmWatchedDialog(
                    title = "Watched Heat",
                    onConfirm = { whenWatched, rating -> confirmed += whenWatched to rating },
                    onDismiss = {},
                    rateable = rateable,
                )
            }
        }
    }

    @Test
    fun withStars_nowPassesTheRating() {
        show()
        composeRule
            .onNodeWithTag(TestTags.MovieDetail.WATCHED_STARS)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        composeRule.onNodeWithText(context.getString(R.string.film_watched_now)).performClick()
        assertEquals(listOf<Pair<WatchedWhen, Int?>>(WatchedWhen.Now to 8), confirmed)
    }

    @Test
    fun withoutStars_nowPassesNull() {
        show()
        composeRule.onNodeWithText(context.getString(R.string.film_watched_now)).performClick()
        assertEquals(listOf<Pair<WatchedWhen, Int?>>(WatchedWhen.Now to null), confirmed)
    }

    /** Add and Edit keep a plain dialog: no stars to show. */
    @Test
    fun notRateable_showsNoStars() {
        show(rateable = false)
        composeRule.onNodeWithText(context.getString(R.string.film_watched_now)).performClick()
        assertEquals(
            0,
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(TestTags.MovieDetail.WATCHED_STARS),
                ).fetchSemanticsNodes()
                .size,
        )
        assertTrue(confirmed.single().second == null)
    }
}
