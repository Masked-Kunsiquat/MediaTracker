package com.hub.media.features.tv.domain

import com.hub.media.core.database.entities.EpisodeEntity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The pure date rules behind the tick prompt (#131) -- see `WatchTimes.kt`. */
class WatchTimesTest {
    private val la = TimeZone.of("America/Los_Angeles")
    private val now = Instant.parse("2026-10-12T20:00:00Z") // 13:00 in Los Angeles

    private fun ep(
        s: Int,
        e: Int,
        watched: Boolean = false,
        air: String? = null,
    ) = EpisodeEntity(
        id = "$s-$e",
        mediaId = "m",
        seasonNumber = s,
        episodeNumber = e,
        watchedAt = if (watched) Instant.parse("2026-01-01T00:00:00Z") else null,
        airDate = air?.let { Instant.parse("${it}T00:00:00Z") },
    )

    @Test
    fun now_isNow() = assertEquals(now, resolveWatchedAt(WatchedWhen.Now, null, now, la))

    @Test
    fun pickedDate_isNoonLocal() {
        val at = resolveWatchedAt(WatchedWhen.On(LocalDate(2025, 3, 3)), null, now, la)!!
        val local = at.toLocalDateTime(la)
        assertEquals(LocalDate(2025, 3, 3), local.date)
        assertEquals(12, local.hour)
    }

    @Test
    fun pickingTodayBeforeNoon_isClampedToNow() {
        val morning = Instant.parse("2026-10-12T16:00:00Z") // 09:00 in Los Angeles
        assertEquals(morning, resolveWatchedAt(WatchedWhen.On(LocalDate(2026, 10, 12)), null, morning, la))
    }

    @Test
    fun onAirDate_keepsTheCalendarDate_westOfUtc() {
        // Stored as 2024-02-16T00:00Z; read raw in Los Angeles it would be the 15th.
        val air = Instant.parse("2024-02-16T00:00:00Z")
        val at = resolveWatchedAt(WatchedWhen.OnAirDate, air, now, la)!!
        assertEquals(LocalDate(2024, 2, 16), at.toLocalDateTime(la).date)
    }

    @Test
    fun onAirDate_unknownOrFuture_isNull() {
        assertNull(resolveWatchedAt(WatchedWhen.OnAirDate, null, now, la))
        assertNull(resolveWatchedAt(WatchedWhen.OnAirDate, now + 48.hours, now, la))
    }

    @Test
    fun earlierUnwatched_usesUpNextOrder_andSkipsWatchedUnairedAndExcludedSpecials() {
        val all =
            listOf(ep(0, 1), ep(1, 1, watched = true), ep(1, 2), ep(1, 3, air = "2027-01-01"), ep(2, 1))
        val target = all.first { it.id == "2-1" }
        assertEquals(listOf("1-2"), earlierUnwatched(all, target, includeSpecials = true, now = now).map { it.id })

        // A special as the target: every regular episode sorts before it.
        val special = all.first { it.id == "0-1" }
        assertEquals(
            listOf("1-2", "2-1"),
            earlierUnwatched(all, special, includeSpecials = true, now = now).map { it.id },
        )

        // An earlier special is a candidate only while specials are included.
        val withLaterSpecial = all + ep(0, 2)
        val target2 = withLaterSpecial.last()
        assertEquals(
            listOf("0-1"),
            earlierUnwatched(withLaterSpecial, target2, includeSpecials = true, now = now)
                .filter {
                    it.seasonNumber == 0
                }.map { it.id },
        )
        assertEquals(
            emptyList(),
            earlierUnwatched(withLaterSpecial, target2, includeSpecials = false, now = now).filter {
                it.seasonNumber ==
                    0
            },
        )
    }

    @Test
    fun pickedDay_isReadInUtc_andFutureDaysAreNotPickable() {
        // Material's DatePicker hands back UTC-midnight millis for the chosen day.
        val feb16 = Instant.parse("2024-02-16T00:00:00Z").toEpochMilliseconds()
        assertEquals(WatchedWhen.On(LocalDate(2024, 2, 16)), watchedOnPickedDay(feb16))

        val today = Instant.parse("2026-10-12T00:00:00Z").toEpochMilliseconds()
        val tomorrow = Instant.parse("2026-10-13T00:00:00Z").toEpochMilliseconds()
        assertEquals(true, isPickableDay(today, now, la))
        assertEquals(false, isPickableDay(tomorrow, now, la))
        assertEquals(2026, pickableUpToYear(now, la))
    }
}
