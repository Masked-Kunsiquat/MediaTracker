# Rating Stats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Your ratings" card to the Books, Films and Shows Stats tabs, and a "Your favourites of
<year>" page to the year in review, from ratings Parts 1 and 2 already store.

**Architecture:** One DAO query returns every rated item of a type with the date it takes from its
read, film viewing or watch-through. One pure function turns those rows into per-title scores, the top
list, the average and the half-star spread. The Stats and year-review view models feed it their own
bounds; one shared card and one new page draw it. No schema change.

**Tech Stack:** Kotlin Multiplatform, Room KMP, Coroutines/Flow, Jetpack Compose, Robolectric +
Roborazzi.

**Spec:** `docs/superpowers/specs/2026-10-22-rating-stats-design.md`

## Global Constraints

- Ratings are stored in halves, `1..10` (2 = one star). Scores in the new code are **stars**
  (`Double`), as `TitleRating.averageStars` already is.
- Only a period's own ratings count. All time (both bounds `null`) includes dateless items; a bounded
  period never does. Bounds are `[from, to)`.
- Ties: score descending, then latest dated item descending (dateless last), then title A to Z, case-insensitive.
- Average and spread count each title once, at its period score. Spread: 10 buckets, index =
  `(stars * 2).roundToInt().coerceIn(1, 10) - 1`.
- A top row shows the exact score ("4.3", one decimal, viewer's locale) only when `stars * 2` is not
  a whole number.
- One stats engine (#126): no per-domain fork of `StatsRepository` or `StatsDao`.
- Room-touching tests live in `shared/src/jvmTest`; pure tests in `commonTest`; VM tests await
  `uiState.first { … }`, never `.value` after an action (AGENTS.md §7).
- Gate: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`.
- Commit trailers on every commit:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01X3dhMjqKB1M3voUhWUrqxJ`. Never commit `gradle.properties`.

## Review Focus

1. **A DNF read with sessions but no `finishedAt`**: dated by its latest session end, so it lands in
   the month you stopped. Test in Task 2.
2. **A rating exactly at the period's `to`**: excluded, and exactly at `from`: included. Test in Task 2.
3. **A title rated twice in a period at different scores**: one entry, averaged; the average across
   titles counts it once. Test in Task 1.
4. **A year whose best book was rated higher in an earlier year**: the favourites page uses only
   that year's rating. Test in Task 4.
5. **A long title in the top-5 row**: the title ellipsizes, and the stars and the exact score stay on screen at font scale
   2. Test in Task 5.

---

### Task 1: `ratingStats` — scores, ranking, average, spread

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/RatingStats.kt`
- Test: `shared/src/commonTest/kotlin/com/hub/media/features/stats/data/RatingStatsTest.kt`

**Interfaces:**
- Consumes: `RatedItemRow` (defined in this task, in `core/database/dao/StatsDao.kt`'s package, so
  Task 2's query returns it).
- Produces:
  - `public data class RatedItemRow(val mediaId: String, val title: String, val rating: Int, val at: Instant?)`
    in `com.hub.media.core.database.dao` (file `RatedItemRow.kt` beside `StatsDao.kt`).
  - `public data class RatedTitle(val mediaId: String, val title: String, val stars: Double, val latestAt: Instant?, val rated: Int)`
    with `val roundedHalves: Int` and `val showsExactScore: Boolean`.
  - `public data class RatingStats(val averageStars: Double, val titles: Int, val top: List<RatedTitle>, val spread: List<Int>)`
    with `val mostCommonHalves: Int`.
  - `public fun ratingStats(rows: List<RatedItemRow>, top: Int = 5): RatingStats?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.RatedItemRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RatingStatsTest {
    private fun at(day: Int) = Instant.parse("2025-03-${day.toString().padStart(2, '0')}T12:00:00Z")

    private fun row(id: String, rating: Int, at: Instant?, title: String = id) = RatedItemRow(id, title, rating, at)

    @Test
    fun nothingRated_isNull() {
        assertNull(ratingStats(emptyList()))
    }

    @Test
    fun aTitleRatedTwice_isOneEntry_atItsAverage_andCountsOnceInTheAverage() {
        val stats = ratingStats(listOf(row("a", 10, at(1)), row("a", 6, at(2)), row("b", 4, at(3))))!!
        assertEquals(2, stats.titles)
        val a = stats.top.first { it.mediaId == "a" }
        assertEquals(4.0, a.stars)
        assertEquals(2, a.rated)
        assertEquals(at(2), a.latestAt)
        assertEquals(3.0, stats.averageStars, "mean of 4.0 and 2.0, not of 5, 3 and 2")
    }

    @Test
    fun ties_goToTheMostRecent_datelessLast_thenAToZ() {
        val stats =
            ratingStats(
                listOf(
                    row("old", 10, at(1)),
                    row("new", 10, at(9)),
                    row("none", 10, null),
                    row("b", 10, at(5), title = "Beta"),
                    row("a", 10, at(5), title = "alpha"),
                ),
            )!!
        assertEquals(listOf("new", "a", "b", "old", "none"), stats.top.map { it.mediaId })
    }

    @Test
    fun theTopList_isCapped_andHighestFirst() {
        val rows = (1..7).map { row("t$it", it + 2, at(it)) }
        val stats = ratingStats(rows, top = 5)!!
        assertEquals(listOf("t7", "t6", "t5", "t4", "t3"), stats.top.map { it.mediaId })
        assertEquals(7, stats.titles)
    }

    @Test
    fun theSpread_hasTenBuckets_andAnInBetweenScoreGoesWhereItsStarsRound() {
        // 8 and 9 halves average to 8.5 halves = 4.25 stars, which rounds to 4.5 (index 8).
        val stats = ratingStats(listOf(row("x", 8, at(1)), row("x", 9, at(2)), row("y", 2, at(3))))!!
        assertEquals(10, stats.spread.size)
        assertEquals(1, stats.spread[8])
        assertEquals(1, stats.spread[1])
        assertEquals(2, stats.spread.sum())
        val x = stats.top.first { it.mediaId == "x" }
        assertEquals(9, x.roundedHalves)
        assertTrue(x.showsExactScore)
        assertFalse(stats.top.first { it.mediaId == "y" }.showsExactScore)
    }

    @Test
    fun mostCommon_isTheFullestBar_andATiePicksTheHigherScore() {
        val stats = ratingStats(listOf(row("a", 6, at(1)), row("b", 8, at(2))))!!
        assertEquals(8, stats.mostCommonHalves)
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :shared:jvmTest --tests "*RatingStatsTest*"`
Expected: FAIL to compile: `Unresolved reference: RatedItemRow` / `ratingStats`.

- [ ] **Step 3: Implement**

`shared/src/commonMain/kotlin/com/hub/media/core/database/dao/RatedItemRow.kt`:

```kotlin
package com.hub.media.core.database.dao

import kotlin.time.Instant

/**
 * One rated read, film viewing or watch-through (#232): its title, its rating in halves, and the date
 * it takes from what it is on (see [StatsDao.observeRatedItems]); `null` when that has no date.
 */
public data class RatedItemRow(
    val mediaId: String,
    val title: String,
    val rating: Int,
    val at: Instant?,
)
```

`shared/src/commonMain/kotlin/com/hub/media/features/stats/data/RatingStats.kt`:

```kotlin
package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.RatedItemRow
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * One title's score for a period (#232): the mean of its rated items in it, in stars.
 *
 * @property latestAt Its most recent dated rated item in the period, for breaking ties; `null` when
 *   none is dated (All time only).
 * @property rated How many of its items are rated in the period.
 */
public data class RatedTitle(
    val mediaId: String,
    val title: String,
    val stars: Double,
    val latestAt: Instant?,
    val rated: Int,
) {
    /** The score as the stars draw it: rounded to the nearest half, in halves. */
    val roundedHalves: Int get() = (stars * 2).roundToInt().coerceIn(1, 10)

    /** Whether the stars cannot show the score exactly, so the row also prints it ("4.3"). */
    val showsExactScore: Boolean get() = (stars * 2) != (stars * 2).roundToInt().toDouble()
}

/**
 * A period's ratings for one medium (#232): each title once, at its score.
 *
 * @property averageStars The mean of the titles' scores.
 * @property titles How many titles have a score.
 * @property top The highest scores first; ties go to the most recent, dateless last, then A to Z.
 * @property spread Ten counts of titles, index 0 = half a star … index 9 = five stars.
 */
public data class RatingStats(
    val averageStars: Double,
    val titles: Int,
    val top: List<RatedTitle>,
    val spread: List<Int>,
) {
    /** The fullest bar, in halves; a tie picks the higher score. */
    val mostCommonHalves: Int get() = spread.indices.maxWith(compareBy({ spread[it] }, { it })) + 1
}

/** [rows] as scores, a top [top], the average and the spread; `null` when nothing is rated. */
public fun ratingStats(
    rows: List<RatedItemRow>,
    top: Int = 5,
): RatingStats? {
    if (rows.isEmpty()) return null
    val titles =
        rows.groupBy { it.mediaId }.map { (id, items) ->
            RatedTitle(
                mediaId = id,
                title = items.first().title,
                stars = items.sumOf { it.rating } / 2.0 / items.size,
                latestAt = items.mapNotNull { it.at }.maxOrNull(),
                rated = items.size,
            )
        }
    val ranked =
        titles.sortedWith(
            compareByDescending<RatedTitle> { it.stars }
                .thenByDescending { it.latestAt }
                .thenBy { it.title.lowercase() },
        )
    val spread = IntArray(10)
    titles.forEach { spread[it.roundedHalves - 1]++ }
    return RatingStats(
        averageStars = titles.sumOf { it.stars } / titles.size,
        titles = titles.size,
        top = ranked.take(top),
        spread = spread.toList(),
    )
}
```

- [ ] **Step 4: Run to see it pass**

Run: `./gradlew :shared:jvmTest --tests "*RatingStatsTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/hub/media/core/database/dao/RatedItemRow.kt shared/src/commonMain/kotlin/com/hub/media/features/stats/data/RatingStats.kt shared/src/commonTest/kotlin/com/hub/media/features/stats/data/RatingStatsTest.kt
git commit -m "Rating stats: scores, top list, average and spread (#232)"
```

---

### Task 2: `StatsDao.observeRatedItems` and the repository methods

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/StatsDao.kt` (add the query after `observeViewings`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/StatsRepository.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/core/database/StatsDaoRatedItemsTest.kt`

**Interfaces:**
- Consumes: `RatedItemRow` (Task 1).
- Produces:
  - `fun StatsDao.observeRatedItems(from: Instant?, to: Instant?, type: MediaType): Flow<List<RatedItemRow>>`
  - `StatsRepository.observeRatedItems(period, timeZone, clock, weekStartDay, type): Flow<List<RatedItemRow>>`
  - `StatsRepository.observeRatedItemsIn(bounds: Pair<Instant, Instant>, type: MediaType): Flow<List<RatedItemRow>>`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.hub.media.core.database

import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchThroughEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** [com.hub.media.core.database.dao.StatsDao.observeRatedItems] (#232): what a rating is dated by. */
class StatsDaoRatedItemsTest {
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun t(s: String) = Instant.parse(s)

    private val march = t("2025-03-01T00:00:00Z") to t("2025-04-01T00:00:00Z")

    private suspend fun rated(type: MediaType, from: Instant?, to: Instant?) =
        db.statsDao().observeRatedItems(from, to, type).first()

    private suspend fun book(id: String) = db.mediaItemDao().insert(sampleMediaItem(id = id, title = id))

    @Test
    fun aFinishedRead_isDatedByItsFinish_andAnUnratedOneIsNeverReturned() =
        runTest {
            book("b")
            db.readThroughDao().insert(
                ReadThroughEntity("r1", "b", 1, ReadingStatus.FINISHED, finishedAt = t("2025-03-10T12:00:00Z"), rating = 8),
            )
            db.readThroughDao().insert(
                ReadThroughEntity("r2", "b", 2, ReadingStatus.FINISHED, finishedAt = t("2025-03-11T12:00:00Z")),
            )
            assertEquals(listOf(8), rated(MediaType.BOOK, march.first, march.second).map { it.rating })
        }

    @Test
    fun aDnfRead_isDatedByItsLatestSession_elseByItsStart() =
        runTest {
            book("b")
            db.readThroughDao().insert(
                ReadThroughEntity("r1", "b", 1, ReadingStatus.DNF, startedAt = t("2025-02-01T12:00:00Z"), rating = 4),
            )
            db.readingSessionDao().insert(
                sampleReadingSession(
                    mediaId = "b",
                    timestampStart = t("2025-03-20T10:00:00Z"),
                    timestampEnd = t("2025-03-20T11:00:00Z"),
                    readThroughId = "r1",
                ),
            )
            book("c")
            db.readThroughDao().insert(
                ReadThroughEntity("r9", "c", 1, ReadingStatus.DNF, startedAt = t("2025-03-05T12:00:00Z"), rating = 2),
            )
            val inMarch = rated(MediaType.BOOK, march.first, march.second)
            assertEquals(t("2025-03-20T11:00:00Z"), inMarch.first { it.mediaId == "b" }.at)
            assertEquals(t("2025-03-05T12:00:00Z"), inMarch.first { it.mediaId == "c" }.at)
        }

    @Test
    fun periodEdges_includeFrom_andExcludeTo() =
        runTest {
            db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "f", title = "Arrival", type = MediaType.MOVIE))
            db.movieWriteDao().insertViewing(WatchLogEntity("v1", "f", watchedAt = march.first, rating = 10))
            db.movieWriteDao().insertViewing(WatchLogEntity("v2", "f", watchedAt = march.second, rating = 2))
            assertEquals(listOf(10), rated(MediaType.MOVIE, march.first, march.second).map { it.rating })
        }

    @Test
    fun aWatchThrough_isDatedByItsLatestViewing_andADatelessOne_isAllTimeOnly() =
        runTest {
            db.tvWriteDao().insertMediaItem(sampleMediaItem(id = "s", title = "Chernobyl", type = MediaType.TV_SHOW))
            db.tvWriteDao().insertEpisodes(listOf(EpisodeEntity(id = "e1", mediaId = "s", seasonNumber = 1, episodeNumber = 1)))
            db.watchThroughDao().insert(WatchThroughEntity("w1", "s", 1, rating = 9))
            db.watchThroughDao().insert(WatchThroughEntity("w2", "s", 2, abandoned = true, rating = 3))
            db.movieWriteDao().insertViewing(
                WatchLogEntity("l1", "s", episodeId = "e1", watchedAt = t("2025-02-27T20:00:00Z"), watchThroughId = "w1"),
            )
            db.movieWriteDao().insertViewing(
                WatchLogEntity("l2", "s", episodeId = "e1", watchedAt = t("2025-03-02T20:00:00Z"), watchThroughId = "w1"),
            )
            assertEquals(
                listOf(9 to t("2025-03-02T20:00:00Z")),
                rated(MediaType.TV_SHOW, march.first, march.second).map { it.rating to it.at },
            )
            assertEquals(setOf(9, 3), rated(MediaType.TV_SHOW, null, null).map { it.rating }.toSet())
        }

    @Test
    fun eachTab_seesOnlyItsOwnType_andEpisodeViewingsAreNotFilmRatings() =
        runTest {
            book("b")
            db.readThroughDao().insert(
                ReadThroughEntity("r1", "b", 1, ReadingStatus.FINISHED, finishedAt = t("2025-03-10T12:00:00Z"), rating = 8),
            )
            db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "f", title = "Arrival", type = MediaType.MOVIE))
            db.movieWriteDao().insertViewing(WatchLogEntity("v1", "f", watchedAt = t("2025-03-10T12:00:00Z"), rating = 6))
            assertEquals(listOf("b"), rated(MediaType.BOOK, null, null).map { it.mediaId })
            assertEquals(listOf("f"), rated(MediaType.MOVIE, null, null).map { it.mediaId })
            assertEquals(emptyList(), rated(MediaType.TV_SHOW, null, null))
        }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :shared:jvmTest --tests "*StatsDaoRatedItemsTest*"`
Expected: FAIL to compile: `Unresolved reference: observeRatedItems`.

- [ ] **Step 3: Implement the query**

In `StatsDao.kt`, after `observeViewings`:

```kotlin
    /**
     * Every rated read, film viewing and watch-through of [type] (#232), each with the date it takes from
     * what it is on: a read's finish, else its latest session's end, else its start (a Did-not-finish read
     * has no finish); a film viewing's day; a watch-through's latest episode viewing. Both bounds null is
     * All time and returns dateless items too; with bounds, only items dated in `[from, to)`.
     */
    @Query(
        """
        SELECT mediaId, title, rating, at FROM (
            SELECT r.mediaId AS mediaId, m.title AS title, r.rating AS rating, m.type AS type,
                   COALESCE(r.finishedAt,
                            (SELECT MAX(s.timestampEnd) FROM reading_sessions s WHERE s.readThroughId = r.id),
                            r.startedAt) AS at
              FROM read_throughs r JOIN media_items m ON m.id = r.mediaId
             WHERE r.rating IS NOT NULL
            UNION ALL
            SELECT w.mediaId, m.title, w.rating, m.type, w.watchedAt
              FROM watch_logs w JOIN media_items m ON m.id = w.mediaId
             WHERE w.episodeId IS NULL AND w.rating IS NOT NULL
            UNION ALL
            SELECT t.mediaId, m.title, t.rating, m.type,
                   (SELECT MAX(l.watchedAt) FROM watch_logs l WHERE l.watchThroughId = t.id)
              FROM watch_throughs t JOIN media_items m ON m.id = t.mediaId
             WHERE t.rating IS NOT NULL
        )
         WHERE type = :type
           AND ((:from IS NULL AND :to IS NULL) OR (at >= :from AND at < :to))
        """,
    )
    fun observeRatedItems(
        from: Instant?,
        to: Instant?,
        type: MediaType,
    ): Flow<List<RatedItemRow>>
```

In `StatsRepository.kt`, after `observeViewings`:

```kotlin
    /** The rated items of [period] for one domain (#232), bounded as [observePeriodStats] is. */
    public fun observeRatedItems(
        period: StatsPeriod,
        timeZone: TimeZone,
        clock: Clock,
        weekStartDay: WeekStartDay,
        type: MediaType,
    ): Flow<List<RatedItemRow>> {
        val bounds = period.bounds(timeZone, clock, weekStartDay)
        return db.statsDao().observeRatedItems(bounds?.first, bounds?.second, type)
    }
```

and in the year-in-review block, after `observeViewingsIn`:

```kotlin
    /** [observeRatedItems] over [bounds]. */
    public fun observeRatedItemsIn(
        bounds: Pair<Instant, Instant>,
        type: MediaType,
    ): Flow<List<RatedItemRow>> = db.statsDao().observeRatedItems(bounds.first, bounds.second, type)
```

Add `import com.hub.media.core.database.dao.RatedItemRow` to both files that need it.

- [ ] **Step 4: Run to see it pass, then falsify**

Run: `./gradlew :shared:jvmTest --tests "*StatsDaoRatedItemsTest*"`
Expected: PASS, 5 tests.
Sabotage: remove the `(SELECT MAX(s.timestampEnd) …)` line from the `COALESCE`, then rerun.
Expected: `aDnfRead_isDatedByItsLatestSession_elseByItsStart` fails. Restore it.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/hub/media/core/database/dao/StatsDao.kt shared/src/commonMain/kotlin/com/hub/media/features/stats/data/StatsRepository.kt shared/src/jvmTest/kotlin/com/hub/media/core/database/StatsDaoRatedItemsTest.kt
git commit -m "Rating stats: one query for every rated item, dated by what it is on (#232)"
```

---

### Task 3: `StatsViewModel` carries the period's ratings

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/StatsUiState.kt` (add `ratings`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/StatsViewModel.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/StatsViewModelTest.kt`

**Interfaces:**
- Consumes: `StatsRepository.observeRatedItems` (Task 2), `ratingStats` (Task 1).
- Produces: `StatsUiState.ratings: RatingStats?` (default `null`), set for whichever domain is
  showing.

- [ ] **Step 1: Write the failing tests** (append to `StatsViewModelTest`, in the #217 section)

```kotlin
    // --- #232: rating stats -----------------------------------------------------------------------

    @Test
    fun theFilmsTab_carriesThePeriodsRatings_andFollowsThePicker() =
        runTest {
            filmWatchedAt(june(19))
            db.movieWriteDao().insertViewing(
                WatchLogEntity(id = "fl-old", mediaId = "film-1", watchedAt = june(19).minus(400.days), rating = 2),
            )
            db.movieWriteDao().setFilmViewingRating("fl-1", 10)
            settingsRepository.setStatsDomain(MediaType.MOVIE)
            settingsRepository.setStatsPeriod(StatsPeriod.MONTH)
            val viewModel = newViewModel()
            holdUiStateSubscribed(viewModel)

            val month = viewModel.uiState.first { it.ratings != null && it.period == StatsPeriod.MONTH }
            assertEquals(5.0, month.ratings!!.top.single().stars, "only June's viewing counts")

            viewModel.selectPeriod(StatsPeriod.ALL_TIME)
            val all = viewModel.uiState.first { it.period == StatsPeriod.ALL_TIME && it.ratings != null }
            assertEquals(3.0, all.ratings!!.top.single().stars)
        }

    @Test
    fun theBooksTab_withNothingRated_hasNoRatings() =
        runTest {
            insertBook()
            val state = newViewModel().uiState.first { !it.isLoading }
            assertNull(state.ratings)
        }
```

`MovieWriteDao.setFilmViewingRating(logId, rating)` exists since #231. Add any missing imports (`kotlin.time.Duration.Companion.days`,
`kotlin.test.assertNull`).

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :shared:jvmTest --tests "*StatsViewModelTest*"`
Expected: FAIL to compile: `Unresolved reference: ratings`.

- [ ] **Step 3: Implement**

`StatsUiState`: add the parameter after `viewing`, and a `@property` line to the KDoc:

```kotlin
    val viewing: ViewingStats? = null,
    val ratings: RatingStats? = null,
```

```kotlin
 * @property ratings The domain's ratings in [period] (#232), or `null` when nothing in it is rated.
```

`StatsViewModel`, Books path: replace the closing three-flow `combine(periodState, …)` with four flows:

```kotlin
            combine(
                periodState,
                statsRepository.observeTimedSessions(period, timeZone, at, weekStart),
                if (period == StatsPeriod.ALL_TIME) statsRepository.observeFinishOutcomes() else flowOf(null),
                statsRepository.observeRatedItems(period, timeZone, at, weekStart, MediaType.BOOK),
            ) { state, sessions, outcomes, rated ->
                state.copy(
                    breakdown = readingBreakdown(sessions, timeZone, weekStart),
                    finishOutcomes = outcomes,
                    ratings = ratingStats(rated),
                )
            }
```

`viewingState`: read the domain's ratings and add them to the final `combine`:

```kotlin
        val ratings =
            statsRepository
                .observeRatedItems(period, timeZone, at, weekStart, domain)
                .map { ratingStats(it) }
        return combine(breakdown, finished, ratings) { numbers, showsFinished, rated ->
            StatsUiState(
                isLoading = false,
                period = period,
                domain = domain,
                goalYear = year,
                viewing = ViewingStats(numbers, showsFinished),
                ratings = rated,
            )
        }
```

- [ ] **Step 4: Run to see it pass**

Run: `./gradlew :shared:jvmTest --tests "*StatsViewModelTest*"`
Expected: PASS, all tests including the two new ones.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/hub/media/ui/StatsUiState.kt shared/src/commonMain/kotlin/com/hub/media/ui/StatsViewModel.kt shared/src/jvmTest/kotlin/com/hub/media/ui/StatsViewModelTest.kt
git commit -m "Rating stats: each Stats tab carries its period's ratings (#232)"
```

---

### Task 4: Favourites in the year in review

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/RatingStats.kt` (add `Favourites`, `favourites`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/YearReview.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/YearReviewViewModel.kt`
- Test: `shared/src/commonTest/kotlin/com/hub/media/features/stats/data/RatingStatsTest.kt`, `shared/src/jvmTest/kotlin/com/hub/media/ui/YearReviewViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1, `StatsRepository.observeRatedItemsIn` (Task 2).
- Produces:
  - `public data class Favourites(val book: RatedTitle?, val film: RatedTitle?, val show: RatedTitle?)`
  - `public fun favourites(books: List<RatedItemRow>, films: List<RatedItemRow>, shows: List<RatedItemRow>): Favourites?`
  - `YearReview.favourites: Favourites? = null`; `yearReview(..., favourites: Favourites? = null)`.

- [ ] **Step 1: Write the failing tests**

Append to `RatingStatsTest`:

```kotlin
    @Test
    fun favourites_takeEachMediumsTop_andAreNullWhenNothingIsRated() {
        val f = favourites(listOf(row("b", 8, at(1))), emptyList(), listOf(row("s", 6, at(2)), row("t", 9, at(3))))!!
        assertEquals("b", f.book?.mediaId)
        assertNull(f.film)
        assertEquals("t", f.show?.mediaId)
        assertNull(favourites(emptyList(), emptyList(), emptyList()))
    }
```

Append to `YearReviewViewModelTest`:

```kotlin
    @Test
    fun favourites_useOnlyThatYearsRatings() =
        runTest {
            db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "a", title = "Arrival", type = MediaType.MOVIE))
            db.movieWriteDao().insertMediaItem(sampleMediaItem(id = "h", title = "Heat", type = MediaType.MOVIE))
            db.movieWriteDao().insertViewing(WatchLogEntity("a1", "a", watchedAt = on(2024), rating = 10))
            db.movieWriteDao().insertViewing(WatchLogEntity("a2", "a", watchedAt = on(2025), rating = 4))
            db.movieWriteDao().insertViewing(WatchLogEntity("h1", "h", watchedAt = on(2025), rating = 8))

            val review = newViewModel(initialYear = 2025).uiState.first { it.review?.year == 2025 }.review!!

            assertEquals("Heat", review.favourites?.film?.title, "Arrival's 2024 five stars do not count in 2025")
            assertNull(review.favourites?.book)
        }

    @Test
    fun aYearWithNothingRated_hasNoFavourites() =
        runTest {
            filmWatchedOn(on(2025))
            assertNull(newViewModel(initialYear = 2025).uiState.first { it.review?.year == 2025 }.review!!.favourites)
        }
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :shared:jvmTest --tests "*RatingStatsTest*" --tests "*YearReviewViewModelTest*"`
Expected: FAIL to compile: `Unresolved reference: favourites`.

- [ ] **Step 3: Implement**

Append to `RatingStats.kt`:

```kotlin
/** A year's top-rated book, film and show (#232); a medium with nothing rated that year is `null`. */
public data class Favourites(
    val book: RatedTitle?,
    val film: RatedTitle?,
    val show: RatedTitle?,
)

/** Each medium's best of [books], [films] and [shows] by [ratingStats]'s order; `null` when none is rated. */
public fun favourites(
    books: List<RatedItemRow>,
    films: List<RatedItemRow>,
    shows: List<RatedItemRow>,
): Favourites? {
    val f =
        Favourites(
            book = ratingStats(books, top = 1)?.top?.firstOrNull(),
            film = ratingStats(films, top = 1)?.top?.firstOrNull(),
            show = ratingStats(shows, top = 1)?.top?.firstOrNull(),
        )
    return f.takeIf { it.book != null || it.film != null || it.show != null }
}
```

`YearReview`: add `val favourites: Favourites? = null,` after `quiet`, with the KDoc line
`@property favourites The year's top-rated book, film and show (#232); null in a quiet year or one with nothing rated.`
`yearReview(...)`: add the parameter `favourites: Favourites? = null,` after `goal`. Compute `quiet`
once, before the `return`, as a `val`, pass it as `quiet = quiet`, and pass
`favourites = favourites.takeUnless { quiet }`.

`YearReviewViewModel`: inside `flatMapLatest`, after `reads`:

```kotlin
            val favouritesFlow =
                combine(
                    statsRepository.observeRatedItemsIn(bounds, MediaType.BOOK),
                    statsRepository.observeRatedItemsIn(bounds, MediaType.MOVIE),
                    statsRepository.observeRatedItemsIn(bounds, MediaType.TV_SHOW),
                    ::favourites,
                )
```

and change the outer combine to
`combine(reads, settingsRepository.observeReadingGoal(year), favouritesFlow) { r, goal, favs -> … }`,
passing `favourites = favs` to `yearReview(...)`.

- [ ] **Step 4: Run to see it pass, then falsify**

Run: `./gradlew :shared:jvmTest --tests "*RatingStatsTest*" --tests "*YearReviewViewModelTest*" --tests "*YearReviewTest*"`
Expected: PASS.
Sabotage: pass `null to null` bounds (`observeRatedItems(null, null, …)`) in `observeRatedItemsIn`.
Expected: `favourites_useOnlyThatYearsRatings` fails. Restore.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/hub/media/features/stats/data/RatingStats.kt shared/src/commonMain/kotlin/com/hub/media/features/stats/data/YearReview.kt shared/src/commonMain/kotlin/com/hub/media/ui/YearReviewViewModel.kt shared/src/commonTest/kotlin/com/hub/media/features/stats/data/RatingStatsTest.kt shared/src/jvmTest/kotlin/com/hub/media/ui/YearReviewViewModelTest.kt
git commit -m "Rating stats: the year in review's favourites, by that year's ratings (#232)"
```

---

### Task 5: The "Your ratings" card on each Stats tab

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/StatsRatingsCard.kt`
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/StatsScreen.kt`
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/RatingRows.kt` (`formatAverage` → `internal`)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/TestTags.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/StatsScreenTest.kt`

**Interfaces:**
- Consumes: `StatsUiState.ratings` (Task 3), `RatingStars`, `formatStars`, `ChartTile`, `ChartDialog`, `BarChart`.
- Produces: `@Composable internal fun RatingsCard(stats: RatingStats, @PluralsRes summary: Int)`;
  tags `TestTags.Stats.RATINGS_CARD = "stats:ratings"`, `TestTags.Stats.RATING_SPREAD_CHART = "stats:ratingSpread"`.

- [ ] **Step 1: Write the failing tests** (append to `StatsScreenTest`, which already drives `StatsScreen`
  with fabricated state; reuse its existing `setContent`/state helper, matching its names)

```kotlin
    // --- #232: Your ratings ---

    private fun ratings(vararg titles: Pair<String, Double>) =
        RatingStats(
            averageStars = titles.map { it.second }.average(),
            titles = titles.size,
            top = titles.map { (t, s) -> RatedTitle(t, t, s, latestAt = null, rated = 1) },
            spread = List(10) { i -> titles.count { (it.second * 2).roundToInt() == i + 1 } },
        )

    @Test
    fun theRatingsCard_isAbsent_whenNothingIsRated() {
        setFilms(ratings = null)
        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.CHARTS_CARD))
        composeRule.onNodeWithText("Your ratings").assertDoesNotExist()
    }

    @Test
    fun aTopRow_printsTheExactScore_onlyWhenTheStarsCannotShowIt() {
        setFilms(ratings = ratings("Arrival" to 4.25, "Heat" to 4.5))
        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.RATINGS_CARD))
        composeRule.onNodeWithText("Arrival").assertIsDisplayed()
        composeRule.onNodeWithText("4.3").assertIsDisplayed()
        composeRule.onNodeWithText("4.5").assertDoesNotExist()
    }

    @Test
    fun theSpreadTile_opensLarger() {
        setFilms(ratings = ratings("Arrival" to 4.5))
        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.RATING_SPREAD_CHART))
        composeRule.onNodeWithTag(TestTags.Stats.RATING_SPREAD_CHART).performClick()
        composeRule.onNodeWithText("Rating spread").assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aLongTitle_keepsItsStarsAndScoreOnScreen_atTheLargestFont() {
        setFilms(ratings = ratings("The Lord of the Rings: The Return of the King, Extended Edition" to 4.25), fontScale = 2f)
        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.RATINGS_CARD))
        composeRule.onNodeWithText("4.3").assertIsDisplayed()
    }
```

`setFilms(ratings, fontScale = 1f)` is a new private helper beside the class's existing ones: it
sets `StatsScreen` with
`StatsUiState(isLoading = false, period = StatsPeriod.MONTH, domain = MediaType.MOVIE, goalYear = 2026, viewing = ViewingStats(ViewingBreakdown(viewings = 1, titles = 1)), ratings = ratings)`,
wrapped in the font-scale `CompositionLocalProvider` the class (or `MarkWatchedDialogTest`) already
uses. In the 4.5 test, "4.5" must not appear anywhere, which also guards the caption: the caption
reads "Mostly 4.5★", not "4.5", so `onNodeWithText("4.5")` (exact match) does not find it.

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*StatsScreenTest*"`
Expected: FAIL to compile: `Unresolved reference: RATINGS_CARD`.

- [ ] **Step 3: Implement**

`strings.xml` (beside the other `stats_` strings):

```xml
    <string name="stats_ratings_title">Your ratings</string>
    <plurals name="stats_ratings_summary_books">
        <item quantity="one">Average %1$s · %2$d book rated</item>
        <item quantity="other">Average %1$s · %2$d books rated</item>
    </plurals>
    <plurals name="stats_ratings_summary_films">
        <item quantity="one">Average %1$s · %2$d film rated</item>
        <item quantity="other">Average %1$s · %2$d films rated</item>
    </plurals>
    <plurals name="stats_ratings_summary_shows">
        <item quantity="one">Average %1$s · %2$d show rated</item>
        <item quantity="other">Average %1$s · %2$d shows rated</item>
    </plurals>
    <string name="stats_ratings_mostly">Mostly %s★</string>
    <string name="stats_chart_rating_spread">Rating spread</string>
    <plurals name="stats_rating_spread_titles">
        <item quantity="one">%d title</item>
        <item quantity="other">%d titles</item>
    </plurals>
```

`TestTags.Stats`: add `RATINGS_CARD` and `RATING_SPREAD_CHART`.
`RatingRows.kt`: `private fun formatAverage` → `internal fun formatAverage`.

`StatsRatingsCard.kt`:

```kotlin
package com.github.maskedkunisquat.mediatracker.ui.screens

// imports as the compiler asks: Card, Column, Row, Text, Modifier.weight, testTag, ChartTile, ChartDialog,
// BarChart, BreakdownRow, RatingStars, formatStars, formatAverage, pluralStringResource, stringResource

/**
 * "Your ratings" (#232): the period's average and how many titles are rated, the top five, and the spread
 * as a chart tile that opens larger. Each title counts once, at its score for the period.
 *
 * @param summary The plural for "Average 4.1 · 12 films rated", naming the tab's medium.
 */
@Composable
internal fun RatingsCard(
    stats: RatingStats,
    @PluralsRes summary: Int,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val mostly = stringResource(R.string.stats_ratings_mostly, formatStars(stats.mostCommonHalves))
    val starLabels = List(10) { if (it % 2 == 1) ((it + 1) / 2).toString() else "" }
    Card(modifier = Modifier.fillMaxWidth().testTag(TestTags.Stats.RATINGS_CARD)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.stats_ratings_title), style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(summary, stats.titles, formatAverage(stats.averageStars), stats.titles),
                style = MaterialTheme.typography.bodyMedium,
            )
            stats.top.forEachIndexed { i, title ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}", style = MaterialTheme.typography.bodyMedium)
                    // Ellipsized, so a long title never pushes its stars off the card (#163's rule).
                    Text(
                        title.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    RatingStars(halves = title.roundedHalves, starSize = 16.dp)
                    if (title.showsExactScore) {
                        Text(formatAverage(title.stars), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChartTile(
                    caption = mostly,
                    description =
                        stringResource(
                            R.string.stats_tile_description,
                            stringResource(R.string.stats_chart_rating_spread),
                            mostly,
                            spreadDescription(stats.spread),
                        ),
                    openLabel = stringResource(R.string.stats_show_larger),
                    onOpen = { open = true },
                    modifier = Modifier.weight(1f),
                    tileModifier = Modifier.testTag(TestTags.Stats.RATING_SPREAD_CHART),
                ) {
                    BarChart(stats.spread.map { it.toLong() }, starLabels, description = "", barHeight = 92.dp)
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
    if (open) {
        ChartDialog(
            title = stringResource(R.string.stats_chart_rating_spread),
            closeLabel = stringResource(R.string.stats_close),
            onClose = { open = false },
        ) {
            BarChart(stats.spread.map { it.toLong() }, starLabels, description = "")
            stats.spread.withIndex().filter { it.value > 0 }.reversed().forEach { (i, count) ->
                BreakdownRow(
                    label = formatStars(i + 1) + "★",
                    values = listOf(pluralStringResource(R.plurals.stats_rating_spread_titles, count, count)),
                )
            }
        }
    }
}

/** "5★: 3 titles, 4.5★: 1 title": the fuller bars, read aloud. */
@Composable
private fun spreadDescription(spread: List<Int>): String =
    spread
        .withIndex()
        .filter { it.value > 0 }
        .reversed()
        .map { (i, count) ->
            stringResource(
                R.string.stats_chart_entry,
                formatStars(i + 1) + "★",
                pluralStringResource(R.plurals.stats_rating_spread_titles, count, count),
            )
        }.joinToString()
```

`BreakdownRow` is already `internal` in `StatsBreakdownCards.kt`.

`StatsScreen.kt`: show the card last on each tab.
- Films/Shows branch: before `return@LazyColumn`, add
  `uiState.ratings?.let { r -> item { RatingsCard(r, if (uiState.domain == MediaType.MOVIE) R.plurals.stats_ratings_summary_films else R.plurals.stats_ratings_summary_shows) } }`.
- Books: after the `FormatsCard` item, add
  `uiState.ratings?.let { r -> item { RatingsCard(r, R.plurals.stats_ratings_summary_books) } }`.

Ruling recorded here: the spec placed the card "after Charts" for Books. Books has Top authors and
Formats after Charts, so last on the tab keeps all three tabs alike. Spec §3 says "after each tab's
last card", which this satisfies.

- [ ] **Step 4: Run to see it pass, then falsify**

Run: `./gradlew :app:testDebugUnitTest --tests "*StatsScreenTest*"`
Expected: PASS.
Sabotage: remove `if (title.showsExactScore)` so the score always prints.
Expected: `aTopRow_printsTheExactScore_onlyWhenTheStarsCannotShowIt` fails on "4.5". Restore.
Sabotage: remove `Modifier.weight(1f)` from the title `Text`.
Expected: `aLongTitle_keepsItsStarsAndScoreOnScreen_atTheLargestFont` fails. Restore. If it still passes, record that as a ledger finding and strengthen the test with a bounds check against the card's right edge, as `TVShowDetailScreenOcclusionTest` does.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/StatsRatingsCard.kt app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/StatsScreen.kt app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/RatingRows.kt app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/TestTags.kt app/src/main/res/values/strings.xml app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/StatsScreenTest.kt
git commit -m "Rating stats: the Your ratings card on Books, Films and Shows (#232)"
```

---

### Task 6: The "Your favourites" year-in-review page

**Files:**
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/YearReviewPages.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/YearReviewScreenTest.kt`

**Interfaces:**
- Consumes: `YearReview.favourites` (Task 4), `RatingStars`.
- Produces: `YearPage.FAVOURITES`, between `RHYTHM` and `SUMMARY`.

- [ ] **Step 1: Write the failing test** (append to `YearReviewScreenTest`, which already builds a
  `YearReview` and pages through it; reuse its fixture and paging helper)

```kotlin
    @Test
    fun theFavouritesPage_showsEachRatedMedium_andLeavesOutTheRest() {
        val favs =
            Favourites(
                book = RatedTitle("b", "Project Hail Mary", 5.0, null, 1),
                film = null,
                show = RatedTitle("s", "Chernobyl", 4.5, null, 1),
            )
        // Set the screen with the class's fixture review copied with favourites = favs, then page to the
        // last-but-one page (the favourites page sits just before the summary).
        assertEquals(YearPage.FAVOURITES, pagesOf(review.copy(favourites = favs)).dropLast(1).last())
        assertFalse(YearPage.FAVOURITES in pagesOf(review))
        // On the page: "Your favourites of 2026", "Project Hail Mary", "Chernobyl" displayed; "Film" absent.
    }
```

Fill in the paging and `onNodeWithText(...).assertIsDisplayed()` lines with the class's existing
helper (the golden test's `forward(times)` is the pattern), so the test proves the page renders, not
only that it is listed.

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*YearReviewScreenTest*"`
Expected: FAIL to compile: `Unresolved reference: FAVOURITES`.

- [ ] **Step 3: Implement**

`strings.xml`:

```xml
    <string name="year_review_favourites">Your favourites of %d</string>
    <string name="year_review_favourite_book">Book</string>
    <string name="year_review_favourite_film">Film</string>
    <string name="year_review_favourite_show">Show</string>
```

`YearReviewPages.kt`:
- add `FAVOURITES,` before `SUMMARY` in `YearPage`;
- in `pagesOf`, add `review.favourites?.let { YearPage.FAVOURITES },` before `YearPage.SUMMARY`;
- in `YearPageContent`, add `YearPage.FAVOURITES -> review.favourites?.let { FavouritesPage(review.year, it) }`;
- add:

```kotlin
/** "Your favourites of 2026" (#232): each medium's top-rated title that year, with its stars. */
@Composable
private fun FavouritesPage(
    year: Int,
    favourites: Favourites,
) {
    Text(text = stringResource(R.string.year_review_favourites, year), style = MaterialTheme.typography.titleLarge)
    listOfNotNull(
        favourites.book?.let { R.string.year_review_favourite_book to it },
        favourites.film?.let { R.string.year_review_favourite_film to it },
        favourites.show?.let { R.string.year_review_favourite_show to it },
    ).forEach { (label, title) ->
        Spacer(Modifier.height(24.dp))
        Text(text = stringResource(label), style = MaterialTheme.typography.labelLarge)
        Text(text = title.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        RatingStars(halves = title.roundedHalves, starSize = 28.dp)
    }
}
```

Any other exhaustive `when` over `YearPage` (the compiler lists them) gets a `FAVOURITES` branch
matching its neighbours.

- [ ] **Step 4: Run to see it pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*YearReviewScreenTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/YearReviewPages.kt app/src/main/res/values/strings.xml app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/YearReviewScreenTest.kt
git commit -m "Rating stats: Your favourites of the year in the year in review (#232)"
```

---

### Task 7: Goldens

**Files:**
- Modify: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/goldens/StatsScreenGoldenTest.kt`
- Modify: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/goldens/YearReviewGoldenTest.kt`
- Create (recorded): `app/src/test/screenshots/stats-films-ratings.png`, `app/src/test/screenshots/year-review-favourites.png`

- [ ] **Step 1: Add the two golden tests**

`StatsScreenGoldenTest.statsFilmsRatings`: the `statsFilms` state plus
`ratings = RatingStats(averageStars = 4.1, titles = 12, top = <Arrival 5.0, Heat 5.0, Dune: Part Two 4.5, Past Lives 4.25, The Batman 4.0>, spread = listOf(0, 0, 0, 1, 1, 2, 1, 3, 2, 2))`,
scrolled to `RATINGS_CARD`, with
`alsoAssert = { …scroll…; assertTextIsShown("Your ratings", "Average 4.1 · 12 films rated", "Past Lives", "4.3", "Mostly 4★") }`.
That spread sums to 12 and its fullest bar is index 7 (3 titles) = 8 halves, so the caption is "Mostly 4★".

`YearReviewGoldenTest.favourites`: the class's `review` copied with
`favourites = Favourites(RatedTitle(…"Project Hail Mary", 5.0…), RatedTitle(…"Arrival", 5.0…), RatedTitle(…"Chernobyl", 4.5…))`,
paged forward to the favourites page, asserting `"Your favourites of 2026", "Project Hail Mary", "Arrival", "Chernobyl"`.

- [ ] **Step 2: Commit the fixtures**

```bash
git add app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/goldens/StatsScreenGoldenTest.kt app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/goldens/YearReviewGoldenTest.kt
git commit -m "Goldens: fixtures for rating stats (#232)"
```

- [ ] **Step 3: Record and look**

Run: `./gradlew :app:recordRoborazziDebug`
Expected: two new PNGs. Open both and check: the card is fully on screen, the stars and "4.3" line up,
the tile shows 10 bars, and the favourites page shows three labelled titles. Then
`git status app/src/test/screenshots`: **only** the two new files. Any other changed golden means an
existing screen moved, which must be explained before continuing.

- [ ] **Step 4: Verify and commit the images**

Run: `./gradlew :app:verifyRoborazziDebug`
Expected: PASS.

```bash
git add app/src/test/screenshots/stats-films-ratings.png app/src/test/screenshots/year-review-favourites.png
git commit -m "Goldens: rating stats card and the year's favourites (#232)"
```

---

### Task 8: Docs, full gate, device

**Files:**
- Modify: `CHANGELOG.md`, `ROADMAP.md`

- [ ] **Step 1: CHANGELOG** — under `[Unreleased]` → `### Added`, after the #231 lines:

```markdown
- Your ratings in Stats: each tab shows your average rating, your top five and how your ratings spread, for the period you pick. Only ratings on what you read or watched in that period count. (#232)
- The year in review ends with your favourites: your top-rated book, film and show of the year. (#232)
```

- [ ] **Step 2: ROADMAP** — line 110's Task 10 row: change "rating stats in #232" to "rating stats
  (#232)" in the same done list; and in the Task 10 section's status note near line 674, say the
  rating stats shipped in #232.

- [ ] **Step 3: Full gate**

Run: `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Lint's UnusedResources must not fire, since every new string is used by Tasks 5 and 6.

- [ ] **Step 4: Commit the docs**

```bash
git add CHANGELOG.md ROADMAP.md
git commit -m "Docs: rating stats in CHANGELOG, ROADMAP (#232)"
```

- [ ] **Step 5: Device**

`adb devices`; with a device attached, run `./gradlew :app:connectedDebugAndroidTest` (no backup
prompt: the test phone holds dummy data), then `./gradlew :app:seedDebugDevice` and hand-check:
- rate two films in different months;
- on Films → Month, the card shows only this month's;
- on All time, both;
- the spread tile opens;
- Year → year in review ends with the favourites page.
