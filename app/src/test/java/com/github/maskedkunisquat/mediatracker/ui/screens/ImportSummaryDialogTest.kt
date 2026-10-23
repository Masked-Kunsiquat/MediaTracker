package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.portability.domain.ImportSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The import summary offers each backfill only when the import gave it something to do (#149):
 * books for the cover/author pass, films and shows for the TMDB pass, both for a mixed import.
 */
@RunWith(RobolectricTestRunner::class)
class ImportSummaryDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private val booksButton get() =
        composeRule.onAllNodes(hasText(string(R.string.import_summary_backfill_books_button)) and hasClickAction())

    private val filmsAndShowsButton get() =
        composeRule.onAllNodes(
            hasText(string(R.string.import_summary_backfill_films_shows_button)) and hasClickAction(),
        )

    private var booksClicks = 0
    private var filmsAndShowsClicks = 0

    private fun showSummary(
        itemsImported: Int,
        booksImported: Int,
    ) {
        composeRule.setContent {
            ImportSummaryDialog(
                summary =
                    ImportSummary(
                        itemsImported = itemsImported,
                        itemsSkipped = 0,
                        itemsMerged = 0,
                        itemsReplaced = 0,
                        booksImported = booksImported,
                        sessionsImported = 0,
                        sessionsSkipped = 0,
                        sessionsMerged = 0,
                        sessionsReplaced = 0,
                        episodesImported = 0,
                        episodesSkipped = 0,
                        episodesMerged = 0,
                        episodesReplaced = 0,
                        rejections = emptyList(),
                    ),
                onDismiss = {},
                onBackfillBooksClick = { booksClicks++ },
                onBackfillFilmsAndShowsClick = { filmsAndShowsClicks++ },
            )
        }
    }

    @Test
    fun anImportOfOnlyFilmsAndShows_offersTheirBackfill_andNotTheBooksOne() {
        showSummary(itemsImported = 2, booksImported = 0)

        filmsAndShowsButton.assertCountEquals(1)
        booksButton.assertCountEquals(0)
        filmsAndShowsButton[0].performClick()
        assertEquals(1, filmsAndShowsClicks)
        assertEquals(0, booksClicks)
    }

    @Test
    fun anImportOfOnlyBooks_offersTheBooksBackfill_andNotTheFilmsOne() {
        showSummary(itemsImported = 3, booksImported = 3)

        booksButton.assertCountEquals(1)
        filmsAndShowsButton.assertCountEquals(0)
        booksButton[0].performClick()
        assertEquals(1, booksClicks)
        assertEquals(0, filmsAndShowsClicks)
    }

    @Test
    fun aMixedImport_offersBoth_eachStartingItsOwnPass() {
        showSummary(itemsImported = 3, booksImported = 1)

        booksButton.assertCountEquals(1)
        filmsAndShowsButton.assertCountEquals(1)
        filmsAndShowsButton[0].performClick()
        assertEquals(1, filmsAndShowsClicks)
        assertEquals(0, booksClicks)
    }

    /**
     * Three buttons at the largest font on a small phone: each keeps its label on one line, as tall as
     * the OK button, rather than being squeezed into a wrapped column by its neighbour.
     */
    @Test
    @Config(qualifiers = "w360dp-h640dp")
    // Text only wraps under native graphics; the legacy pipeline never overflows a line (AGENTS.md §7).
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aMixedImport_atTheLargestFont_keepsEveryButtonOnOneLine() {
        // The device's own setting, not a LocalDensity override: a dialog is its own window, whose
        // root provides LocalDensity afresh from the configuration.
        RuntimeEnvironment.setFontScale(2f)
        showSummary(itemsImported = 3, booksImported = 1)

        val okHeight = buttonHeight(string(R.string.ok_button))
        assertEquals(okHeight, buttonHeight(string(R.string.import_summary_backfill_books_button)), 0.5f)
        assertEquals(okHeight, buttonHeight(string(R.string.import_summary_backfill_films_shows_button)), 0.5f)
    }

    private fun buttonHeight(label: String): Float =
        composeRule
            .onNode(hasText(label) and hasClickAction())
            .fetchSemanticsNode()
            .boundsInRoot.height

    @Test
    fun anImportThatAddedNothing_offersNeither() {
        showSummary(itemsImported = 0, booksImported = 0)

        booksButton.assertCountEquals(0)
        filmsAndShowsButton.assertCountEquals(0)
        // Positive control: the dialog itself is up.
        composeRule.onAllNodes(hasText(string(R.string.import_summary_title))).assertCountEquals(1)
    }
}
