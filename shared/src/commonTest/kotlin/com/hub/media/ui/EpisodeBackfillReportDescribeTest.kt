package com.hub.media.ui

import com.hub.media.features.tv.domain.EpisodeBackfillReport
import com.hub.media.features.tv.domain.SeasonCountMismatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [describe] -- the one sentence a user reads after a backfill.
 *
 * Pure, so it lives in `commonTest`. The value here is not the wording but the branching: which
 * clauses appear depends on what happened, and a report that silently omits something is worse
 * than one that never ran.
 */
class EpisodeBackfillReportDescribeTest {
    @Test
    fun nothingFilled_describesEpisodeDetailsRatherThanClaimingNothingToAdd() {
        // #167: "Nothing to add" read as a verdict on the whole show, including seasons the
        // seasonFindings section can now offer to create. episodesFilled counts episode *detail*
        // fills, so the zero case says that and nothing broader.
        val text = EpisodeBackfillReport().describe()

        assertEquals("No episode details needed filling in.", text)
    }

    @Test
    fun oneEpisodeIsSingular() {
        assertEquals("Updated 1 episode.", EpisodeBackfillReport(episodesFilled = 1).describe())
    }

    @Test
    fun severalEpisodesArePlural() {
        assertEquals("Updated 5 episodes.", EpisodeBackfillReport(episodesFilled = 5).describe())
    }

    @Test
    fun aMismatchNoLongerAppearsInTheSentence() {
        // #167: the per-season disagreement moved to TVShowDetailUiState.Ready.seasonFindings, which
        // stays on screen and can act on it -- restating it in a snackbar that fades would be the
        // same fact told twice, once actionable and once not.
        val text =
            EpisodeBackfillReport(
                episodesFilled = 3,
                mismatches = listOf(SeasonCountMismatch(seasonNumber = 1, localEpisodes = 3, providerEpisodes = 5)),
            ).describe()

        assertEquals("Updated 3 episodes.", text)
    }

    @Test
    fun everyMismatchIsExcludedRegardlessOfCount() {
        val text =
            EpisodeBackfillReport(
                episodesFilled = 0,
                mismatches =
                    listOf(
                        SeasonCountMismatch(1, localEpisodes = 3, providerEpisodes = 5),
                        SeasonCountMismatch(2, localEpisodes = 9, providerEpisodes = 8),
                    ),
            ).describe()

        assertEquals("No episode details needed filling in.", text)
    }

    @Test
    fun seasonsBeyondOneRequestAreReportedRatherThanPassedOverInSilence() {
        // "Nothing to fill" and "never looked" are different answers, and a user seeing a season
        // untouched deserves to know which one happened.
        val one = EpisodeBackfillReport(seasonsNotFetched = listOf(21)).describe()
        val many = EpisodeBackfillReport(seasonsNotFetched = listOf(21, 22)).describe()

        assertTrue(one.contains("Season 21 could not be checked"), one)
        assertTrue(many.contains("Seasons 21, 22 could not be checked"), many)
    }
}
