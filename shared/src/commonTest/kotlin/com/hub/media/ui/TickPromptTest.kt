package com.hub.media.ui

import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.features.tv.domain.resolveWatchedAt
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [buildPendingTick] / [buildSeasonTick] (#131): the flags that decide what the prompt offers. Pure,
 * so every availability rule in the spec is pinned here rather than only through hand-built
 * [PendingTick]s in the dialog tests.
 */
class TickPromptTest {
    private val la = TimeZone.of("America/Los_Angeles")
    private val now = Instant.parse("2026-10-12T20:00:00Z") // 13:00 in Los Angeles

    private fun ep(
        s: Int,
        e: Int,
        air: String? = null,
    ) = EpisodeEntity(
        id = "$s-$e",
        mediaId = "m",
        seasonNumber = s,
        episodeNumber = e,
        airDate = air?.let { Instant.parse("${it}T00:00:00Z") },
    )

    @Test
    fun datedTarget_withDatedEarlierEpisodes_offersAirDateEitherWay_andPreselectsIt() {
        val all = listOf(ep(1, 1, "2024-01-01"), ep(1, 2, "2024-01-08"))
        val tick = buildPendingTick(all[1], all, includeSpecials = true, now = now, zone = la)
        assertEquals(listOf("1-1"), tick.earlierIds) // positive control: catch-up is offered
        assertTrue(tick.airDateAvailable)
        assertTrue(tick.airDateAvailableWithEarlier)
        assertEquals(all[1].airDate!!.toEpochMilliseconds(), tick.initialPickMillis)
    }

    @Test
    fun anEarlierEpisodeWithoutAnAirDate_hidesAirDateOnlyWithCatchUp() {
        val all = listOf(ep(1, 1), ep(1, 2, "2024-01-08"))
        val tick = buildPendingTick(all[1], all, includeSpecials = true, now = now, zone = la)
        assertTrue(tick.airDateAvailable)
        assertFalse(tick.airDateAvailableWithEarlier)
    }

    @Test
    fun unairedTarget_getsNoCatchUp_noAirDate_andNoFuturePreselection() {
        val all = listOf(ep(1, 1), ep(1, 2, "2027-01-01"))
        val tick = buildPendingTick(all[1], all, includeSpecials = true, now = now, zone = la)
        assertEquals(emptyList(), tick.earlierIds)
        assertFalse(tick.airDateAvailable)
        assertNull(tick.initialPickMillis, "the picker must not open on a future, disabled day")
    }

    @Test
    fun excludedSpecialTarget_getsNoCatchUp() {
        val all = listOf(ep(1, 1), ep(0, 1))
        val tick = buildPendingTick(all[1], all, includeSpecials = false, now = now, zone = la)
        assertEquals(emptyList(), tick.earlierIds)
        val included = buildPendingTick(all[1], all, includeSpecials = true, now = now, zone = la)
        assertEquals(listOf("1-1"), included.earlierIds) // positive control
    }

    @Test
    fun eveOfTheAirDate_westOfUtc_doesNotOfferAirDate() {
        // 17:00 on 4 March in Los Angeles is already 01:00 on 5 March UTC, so an episode dated
        // 5 March has "aired" as an instant -- but storing it would land on 4 March locally.
        val eve = Instant.parse("2026-03-05T01:00:00Z")
        val target = ep(1, 1, "2026-03-05")
        assertNull(resolveWatchedAt(WatchedWhen.OnAirDate, target.airDate, eve, la))
        val tick = buildPendingTick(target, listOf(target), includeSpecials = true, now = eve, zone = la)
        assertFalse(tick.airDateAvailable)
    }

    @Test
    fun seasonTick_countsOnlyAiredUnwatched_andIsNullWhenNothingIsMarkable() {
        val season = listOf(ep(1, 1, "2024-01-01"), ep(1, 2, "2027-01-01"))
        val tick = buildSeasonTick(1, season, now, la)!!
        assertEquals(listOf("1-1"), tick.targetIds)
        assertTrue(tick.airDateAvailable)
        assertNull(buildSeasonTick(1, listOf(ep(1, 3, "2027-01-01")), now, la))
    }
}
