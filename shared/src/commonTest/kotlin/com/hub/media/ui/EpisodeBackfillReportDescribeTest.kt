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
 * clauses appear depends on what happened, and a report that silently omits a disagreement is worse
 * than one that never ran.
 */
class EpisodeBackfillReportDescribeTest {
    @Test
    fun nothingFilled_saysSoOutrightRatherThanSayingNothing() {
        // A screen that changes in no visible way looks like a button that did not work.
        val text = EpisodeBackfillReport().describe()

        assertEquals("Nothing to add — every episode already has its details.", text)
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
    fun aDisagreementNamesBothNumbers() {
        // A count without the one it disagrees with is not actionable, and #123 exists precisely
        // because acting on it is a decision this app does not make for the user.
        val text =
            EpisodeBackfillReport(
                episodesFilled = 3,
                mismatches = listOf(SeasonCountMismatch(seasonNumber = 1, localEpisodes = 3, providerEpisodes = 5)),
            ).describe()

        assertEquals("Updated 3 episodes. Season 1: you have 3, TMDB lists 5.", text)
    }

    @Test
    fun everyDisagreeingSeasonIsNamed() {
        val text =
            EpisodeBackfillReport(
                episodesFilled = 0,
                mismatches =
                    listOf(
                        SeasonCountMismatch(1, localEpisodes = 3, providerEpisodes = 5),
                        SeasonCountMismatch(2, localEpisodes = 9, providerEpisodes = 8),
                    ),
            ).describe()

        assertTrue(text.contains("Season 1: you have 3, TMDB lists 5."), text)
        assertTrue(text.contains("Season 2: you have 9, TMDB lists 8."), text)
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
