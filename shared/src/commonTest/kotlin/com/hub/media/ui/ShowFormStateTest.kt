package com.hub.media.ui

import com.hub.media.features.tv.data.SeasonEpisodes
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.domain.TmdbShowMapping
import kotlin.test.Test
import kotlin.test.assertEquals

/** #214: the show form's rules, written once for Add show, the TMDB review and Edit show. */
class ShowFormStateTest {
    @Test
    fun parsed_blankNumbers_areNull() {
        assertEquals(
            ShowFormParse.Valid(
                title = "Severance",
                releaseYear = null,
                totalSeasons = null,
                purchasePrice = null,
                seasons = emptyList(),
            ),
            ShowFormState(title = " Severance ").parsed(),
        )
    }

    @Test
    fun parsed_readsTheNumbersAndRows() {
        val parsed =
            ShowFormState(
                title = "Severance",
                releaseYear = "2022",
                totalSeasons = "2",
                purchasePrice = "19.99",
                seasonRows = listOf(SeasonRow("1", "9"), SeasonRow("2", "10")),
            ).parsed() as ShowFormParse.Valid

        assertEquals(2022, parsed.releaseYear)
        assertEquals(2, parsed.totalSeasons)
        assertEquals(19.99, parsed.purchasePrice)
        assertEquals(listOf(SeasonQuickFill(1, 9), SeasonQuickFill(2, 10)), parsed.seasons)
    }

    @Test
    fun parsed_unreadableTotalSeasons_namesTheField() {
        assertEquals(
            ShowFormParse.Unreadable("Enter a valid total seasons, or clear the field"),
            ShowFormState(title = "x", totalSeasons = "2.5").parsed(),
        )
    }

    @Test
    fun parsed_aRowWithNoEpisodeCount_namesTheRow() {
        assertEquals(
            ShowFormParse.Unreadable("Season row 1: enter an episode count"),
            ShowFormState(title = "x", seasonRows = listOf(SeasonRow("1", ""))).parsed(),
        )
    }

    @Test
    fun parsed_aRowWithNoSeasonNumber_namesTheRow() {
        assertEquals(
            ShowFormParse.Unreadable("Season row 2: enter a season number"),
            ShowFormState(title = "x", seasonRows = listOf(SeasonRow("1", "3"), SeasonRow("", "3"))).parsed(),
        )
    }

    @Test
    fun addingSeasonRow_numbersItOnePastTheHighest() {
        val rows = ShowFormState(seasonRows = listOf(SeasonRow("3", "8"))).addingSeasonRow().seasonRows

        assertEquals(SeasonRow(seasonNumber = "4"), rows.last())
    }

    @Test
    fun addingSeasonRow_toAnEmptyForm_startsAtOne() {
        assertEquals(listOf(SeasonRow(seasonNumber = "1")), ShowFormState().addingSeasonRow().seasonRows)
    }

    @Test
    fun removingSeasonRow_outOfRange_isIgnored() {
        val form = ShowFormState(seasonRows = listOf(SeasonRow("1", "8")))

        assertEquals(form, form.removingSeasonRow(5))
    }

    @Test
    fun toShowForm_fillsFromTmdbAndLeavesRowsAndPriceEmpty() {
        val form =
            TmdbShowMapping(
                title = "Severance",
                releaseYear = 2022,
                totalSeasons = 2,
                seasons = listOf(SeasonEpisodes(1, emptyList())),
            ).toShowForm()

        assertEquals(ShowFormState(title = "Severance", releaseYear = "2022", totalSeasons = "2"), form)
    }
}
