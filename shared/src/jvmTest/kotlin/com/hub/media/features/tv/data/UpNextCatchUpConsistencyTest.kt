package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.tv.domain.earlierUnwatched
import com.hub.media.features.tv.domain.isAired
import com.hub.media.features.tv.domain.upNextOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The "what comes next" rule exists twice: in SQL ([com.hub.media.core.database.dao.EpisodeDao.observeUpNext],
 * which must scale with the number of shows) and in Kotlin ([upNextOrder] / [earlierUnwatched], which
 * the tick prompt's catch-up uses, #131). This pins them to the same answer, so a change to either
 * side's ordering or "aired / allowed" predicate fails here instead of making Up next and the prompt
 * quietly disagree.
 */
class UpNextCatchUpConsistencyTest {
    private class FixedClock(
        val now: Instant,
    ) : Clock {
        override fun now(): Instant = now
    }

    private val clock = FixedClock(Instant.parse("2026-10-12T20:00:00Z"))
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = TVShowRepository(db, clock)
    }

    @AfterTest
    fun tearDown() = db.close()

    /**
     * Builds a show from `(season, episode, state)` triples, where state is "watched", "open" or
     * "future" (unwatched, airing after [clock]).
     */
    private suspend fun show(vararg spec: Triple<Int, Int, String>): String {
        val added = repo.addShow(title = "Show", seasons = emptyList())
        assertIs<Resource.Success<String>>(added)
        db.tvWriteDao().insertEpisodes(
            spec.map { (s, e, state) ->
                EpisodeEntity(
                    id = newId(),
                    mediaId = added.data,
                    seasonNumber = s,
                    episodeNumber = e,
                    watchedAt = if (state == "watched") clock.now - 30.days else null,
                    airDate = if (state == "future") clock.now + 7.days else clock.now - 60.days,
                )
            },
        )
        return added.data
    }

    /** Asserts SQL and Kotlin agree on [showId]'s next episode, remaining count and catch-up set. */
    private suspend fun assertAgree(
        showId: String,
        includeSpecials: Boolean,
    ) {
        val all = db.episodeDao().getByMediaId(showId)
        val candidates =
            all
                .filter {
                    it.watchedAt == null &&
                        isAired(it.airDate, clock.now) &&
                        (includeSpecials || it.seasonNumber > 0)
                }.sortedWith(upNextOrder)
        val row = repo.observeUpNext(includeSpecials).first().singleOrNull { it.mediaId == showId }

        if (candidates.isEmpty()) {
            assertEquals(
                null,
                row,
                "includeSpecials=$includeSpecials: Kotlin sees nothing next, so Up next must not list the show",
            )
            return
        }
        val label = "includeSpecials=$includeSpecials"
        assertEquals(candidates.first().id, row?.episodeId, "$label: Up next's next episode")
        assertEquals(candidates.size, row?.remaining, "$label: remaining")
        // Ticking the last candidate offers every other candidate as catch-up -- and nothing else.
        assertEquals(
            candidates.dropLast(1).map { it.id },
            earlierUnwatched(all, candidates.last(), includeSpecials, clock.now).map { it.id },
            "$label: catch-up for the last candidate",
        )
    }

    @Test
    fun midSeason_withAnUnwatchedSpecialAndAnUnairedEpisode() =
        runTest {
            val id =
                show(
                    Triple(0, 1, "open"),
                    Triple(1, 1, "watched"),
                    Triple(1, 2, "open"),
                    Triple(1, 3, "future"),
                    Triple(2, 1, "open"),
                )
            assertAgree(id, includeSpecials = true)
            assertAgree(id, includeSpecials = false)
        }

    @Test
    fun onlyASpecialLeft_andTheNextSeasonNotYetAired() =
        runTest {
            val id =
                show(
                    Triple(0, 1, "open"),
                    Triple(0, 2, "watched"),
                    Triple(1, 1, "watched"),
                    Triple(2, 1, "future"),
                )
            assertAgree(id, includeSpecials = true)
            assertAgree(id, includeSpecials = false)
        }

    @Test
    fun specialsAcrossSeveralRegularSeasons() =
        runTest {
            val id =
                show(
                    Triple(0, 2, "open"),
                    Triple(0, 1, "open"),
                    Triple(1, 1, "watched"),
                    Triple(2, 2, "open"),
                    Triple(2, 1, "open"),
                    Triple(3, 1, "open"),
                )
            assertAgree(id, includeSpecials = true)
            assertAgree(id, includeSpecials = false)
        }
}
