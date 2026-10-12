# Tick with a date, untick with Undo — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every episode tick asks *when* (Now / On air date / Pick a date… / Cancel, plus an optional
catch-up), and every untick is instant with an exact-restore Undo. This applies on the show screen
and in Up next.

**Architecture:**
- **Pure date rules** live in a small `WatchTimes.kt` in `features/tv/domain`: resolving the time,
  "aired", ordering, and catch-up candidates. They are unit-tested in `commonTest`.
- **Room writes** gain three methods: an atomic multi-episode mark, a read-then-clear transaction,
  and a conditional restore.
- **`MarkEpisodesWatchedUseCase`** is the only tick path.
- **The view models** hold `pendingTick` and an untick-undo event.
- **One stateless `MarkWatchedDialog`.** The route composables render it, so the stateless screens'
  signatures stay the same and their existing tests keep compiling.

**Tech Stack:** Kotlin Multiplatform, Room KMP, kotlinx-datetime 0.8 (with `kotlin.time.Instant`),
Jetpack Compose Material 3, kotlin.test, Robolectric and Roborazzi (`:app` tests).

**Spec:** `docs/superpowers/specs/2026-10-12-tick-with-date-design.md`. Read it first.

## Global Constraints

- **No schema change.** `APP_DATABASE_VERSION` stays 7, and no file in `shared/schemas/` changes.
- **No new dependencies** (AGENTS.md §5).
- **Room-touching tests go in `shared/src/jvmTest`.** Pure logic goes in `shared/src/commonTest`.
- **ViewModel tests await state with `uiState.first { … }`, never `.value`** after an action
  (AGENTS.md §7).
- **Strings live in `app/src/main/res/values/strings.xml`.** The untitled fallback reuses the
  existing `up_next_untitled_format` ("Episode %1$d").
- **A picked or air date** becomes **12:00 in the device's local time zone** on that calendar day,
  then is clamped to `now`.
- **TMDB air dates are stored as midnight UTC** (`TmdbDates.kt:22`). Read their calendar date in
  `TimeZone.UTC`.
- **The verification gate:**
  `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug`,
  plus `ktlintCheck :app:lintDebug :app:assembleDebug`.
- **Ordering key** (identical to `EpisodeDao.observeUpNext`): `(seasonNumber == 0), seasonNumber, episodeNumber`.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A user in a time zone west of UTC** marks an old episode "On air date". It must show the
   calendar air date, not the day before. Pinned in Task 1 (an `America/Los_Angeles` test).
2. **A user picks today, before noon.** `watchedAt` must not be in the future. Pinned in Task 1
   (the clamp test).
3. **A user unticks, re-ticks, then taps the old Undo.** The new date must survive. Pinned in Task 2
   (the conditional restore test) and Task 4 (the view model test).
4. **A user double-taps "Now".** Exactly one write. Pinned in Task 4 (a `StandardTestDispatcher`
   test).
5. **A user marks a season that has future episodes.** Unaired episodes stay unwatched, and the
   prompt's count matches what is written. Pinned in Task 3 and Task 4.

---

## File map

| File | Responsibility |
|---|---|
| Create `shared/src/commonMain/kotlin/com/hub/media/features/tv/domain/WatchTimes.kt` | `WatchedWhen`, `resolveWatchedAt`, `isAired`, `upNextOrder`, `earlierUnwatched` |
| Create `shared/src/commonMain/kotlin/com/hub/media/features/tv/domain/MarkEpisodesWatchedUseCase.kt` | The single tick path |
| Modify `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/TVWriteDao.kt` | `markEpisodesWatchedAt`, `clearEpisodeReturningPrevious`, `clearSeasonReturningPrevious`, `restoreWatchedAt`, `restoreAll`; remove `markSeasonWatched` |
| Modify `shared/src/commonMain/kotlin/com/hub/media/features/tv/data/TVShowRepository.kt` | `untickEpisode`, `clearSeason`, `restoreWatched`; remove `setSeasonWatched` |
| Modify `shared/src/commonMain/kotlin/com/hub/media/ui/TVShowDetailViewModel.kt` | `PendingTick`, `UntickEvent`, request/confirm/dismiss/untick/undo, `SeasonGroup.markableCount` |
| Modify `shared/src/commonMain/kotlin/com/hub/media/ui/UpNextViewModel.kt` | Replace instant tick + `MarkedWatchedEvent` with `pendingTick` |
| Modify `shared/src/commonMain/kotlin/com/hub/media/ui/AppContainer.kt` | Expose `markEpisodesWatchedUseCase` |
| Create `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/MarkWatchedDialog.kt` | Stateless dialog + date picker |
| Modify `app/.../ui/screens/TVShowDetailScreen.kt`, `UpNextScreen.kt`, `ui/ViewModelFactories.kt`, `res/values/strings.xml` | Route wiring, snackbars, season button visibility |
| Tests | `commonTest/.../tv/domain/WatchTimesTest.kt`; `jvmTest/.../tv/domain/MarkEpisodesWatchedUseCaseTest.kt`; `jvmTest/.../tv/data/UntickRestoreTest.kt`; updates to `TVShowDetailViewModelTest`, `UpNextViewModelTest`; `app/src/test/.../ui/components/MarkWatchedDialogTest.kt`; `app/src/test/.../ui/goldens/MarkWatchedDialogGoldenTest.kt`; `app/src/androidTest/.../ui/components/MarkWatchedDialogPickerTest.kt` |

---

### Task 1: Pure watch-time rules

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/tv/domain/WatchTimes.kt`
- Test: `shared/src/commonTest/kotlin/com/hub/media/features/tv/domain/WatchTimesTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface WatchedWhen { data object Now; data object OnAirDate; data class On(val date: LocalDate) }`
  - `fun resolveWatchedAt(watchedWhen: WatchedWhen, airDate: Instant?, now: Instant, zone: TimeZone): Instant?`.
    It returns `null` only for `OnAirDate` when `airDate` is null or after `now`.
  - `fun isAired(airDate: Instant?, now: Instant): Boolean`
  - `val upNextOrder: Comparator<EpisodeEntity>`
  - `fun earlierUnwatched(episodes: List<EpisodeEntity>, target: EpisodeEntity, includeSpecials: Boolean, now: Instant): List<EpisodeEntity>`

- [ ] **Step 1: Write the failing tests**

```kotlin
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

class WatchTimesTest {
    private val la = TimeZone.of("America/Los_Angeles")
    private val now = Instant.parse("2026-10-12T20:00:00Z") // 13:00 in LA

    private fun ep(s: Int, e: Int, watched: Boolean = false, air: String? = null) =
        EpisodeEntity(
            id = "$s-$e", mediaId = "m", seasonNumber = s, episodeNumber = e,
            watchedAt = if (watched) Instant.parse("2026-01-01T00:00:00Z") else null,
            airDate = air?.let { Instant.parse("${it}T00:00:00Z") },
        )

    @Test fun now_isNow() = assertEquals(now, resolveWatchedAt(WatchedWhen.Now, null, now, la))

    @Test fun pickedDate_isNoonLocal() {
        val at = resolveWatchedAt(WatchedWhen.On(LocalDate(2025, 3, 3)), null, now, la)!!
        val local = at.toLocalDateTime(la)
        assertEquals(LocalDate(2025, 3, 3), local.date)
        assertEquals(12, local.hour)
    }

    @Test fun pickingTodayBeforeNoon_isClampedToNow() {
        val morning = Instant.parse("2026-10-12T16:00:00Z") // 09:00 in LA
        assertEquals(morning, resolveWatchedAt(WatchedWhen.On(LocalDate(2026, 10, 12)), null, morning, la))
    }

    @Test fun onAirDate_keepsTheCalendarDate_westOfUtc() {
        // Stored as 2024-02-16T00:00Z; read raw in LA it would be the 15th.
        val air = Instant.parse("2024-02-16T00:00:00Z")
        val at = resolveWatchedAt(WatchedWhen.OnAirDate, air, now, la)!!
        assertEquals(LocalDate(2024, 2, 16), at.toLocalDateTime(la).date)
    }

    @Test fun onAirDate_unknownOrFuture_isNull() {
        assertNull(resolveWatchedAt(WatchedWhen.OnAirDate, null, now, la))
        assertNull(resolveWatchedAt(WatchedWhen.OnAirDate, now + 48.hours, now, la))
    }

    @Test fun earlierUnwatched_usesUpNextOrder_andSkipsWatchedUnairedAndExcludedSpecials() {
        val all = listOf(
            ep(0, 1), ep(1, 1, watched = true), ep(1, 2), ep(1, 3, air = "2027-01-01"), ep(2, 1),
        )
        val target = all.first { it.id == "2-1" }
        assertEquals(listOf("1-2"), earlierUnwatched(all, target, includeSpecials = true, now = now).map { it.id })
        // A special as target: every regular episode sorts before it.
        val special = all.first { it.id == "0-1" }
        assertEquals(listOf("1-2", "2-1"), earlierUnwatched(all, special, includeSpecials = true, now = now).map { it.id })
        // Specials excluded: an excluded special is never a candidate.
        val withEarlierSpecial = all + ep(0, 2)
        assertEquals(listOf("0-1"), earlierUnwatched(withEarlierSpecial, withEarlierSpecial.last(), true, now).filter { it.seasonNumber == 0 }.map { it.id })
        assertEquals(emptyList(), earlierUnwatched(withEarlierSpecial, withEarlierSpecial.last(), false, now).filter { it.seasonNumber == 0 })
    }
}
```

- [ ] **Step 2: Run and confirm the tests fail.**
  Run: `./gradlew :shared:jvmTest --tests '*WatchTimesTest' -q`.
  Expected: a compile failure, because `WatchedWhen`, `resolveWatchedAt` and the rest do not
  exist yet.

- [ ] **Step 3: Implement**

```kotlin
package com.hub.media.features.tv.domain

import com.hub.media.core.database.entities.EpisodeEntity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** When the user says they watched something (#131) -- see the spec for each rule. */
public sealed interface WatchedWhen {
    public data object Now : WatchedWhen
    /** Each episode's own air date. Only offered when every affected episode has a past one. */
    public data object OnAirDate : WatchedWhen
    /** A day the user picked; stored as noon local, clamped to now. */
    public data class On(val date: LocalDate) : WatchedWhen
}

/**
 * The `watchedAt` to store, or `null` when [WatchedWhen.OnAirDate] cannot be honoured.
 *
 * Noon local, not midnight: a calendar day stored at midnight shifts to the neighbouring day as
 * soon as it is read in another zone. Clamped to [now] so "today" picked before noon is never
 * a future watch (Up next sorts by `MAX(watchedAt)`). TMDB air dates are midnight UTC
 * (`TmdbDates.toInstantOrNull`), so their calendar day is read in UTC.
 */
public fun resolveWatchedAt(
    watchedWhen: WatchedWhen,
    airDate: Instant?,
    now: Instant,
    zone: TimeZone,
): Instant? {
    val day =
        when (watchedWhen) {
            WatchedWhen.Now -> return now
            WatchedWhen.OnAirDate -> airDate?.takeIf { it <= now }?.toLocalDateTime(TimeZone.UTC)?.date ?: return null
            is WatchedWhen.On -> watchedWhen.date
        }
    return minOf(LocalDateTime(day, LocalTime(12, 0)).toInstant(zone), now)
}

/** Aired = no air date (quick-filled) or one not after [now]. Same rule as `EpisodeDao.observeUpNext`. */
public fun isAired(airDate: Instant?, now: Instant): Boolean = airDate == null || airDate <= now

/** Regular seasons ascending, then specials -- `EpisodeDao.observeUpNext`'s `ORDER BY`. */
public val upNextOrder: Comparator<EpisodeEntity> =
    compareBy<EpisodeEntity>({ it.seasonNumber == 0 }, { it.seasonNumber }, { it.episodeNumber })

/** The catch-up candidates for ticking [target]: unwatched, aired, allowed, sorting before it. */
public fun earlierUnwatched(
    episodes: List<EpisodeEntity>,
    target: EpisodeEntity,
    includeSpecials: Boolean,
    now: Instant,
): List<EpisodeEntity> =
    episodes
        .filter { it.id != target.id && it.watchedAt == null && isAired(it.airDate, now) }
        .filter { includeSpecials || it.seasonNumber > 0 }
        .filter { upNextOrder.compare(it, target) < 0 }
        .sortedWith(upNextOrder)
```

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: 6 tests, 0 failures.
- [ ] **Step 5: Falsify the guards.** Make each change below in turn, re-run, confirm a failure,
  then restore:
  - replace `minOf(…, now)` with its first argument;
  - read the air date in `zone` instead of `TimeZone.UTC`;
  - change `LocalTime(12, 0)` to `LocalTime(0, 0)`.
- [ ] **Step 6: Commit:** `git add` both files and commit with the message
  `Add watch-time rules: noon-local, clamp, UTC air dates, catch-up (#131)`.

---

### Task 2: Room writes — atomic mark, read-then-clear, conditional restore

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/TVWriteDao.kt` (around
  line 418, beside `markEpisodeWatched`)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/tv/data/UntickRestoreTest.kt`

**Interfaces:**
- Produces, on `TVWriteDao`:
  - `@Transaction suspend fun markEpisodesWatchedAt(stamps: Map<String, Instant>): Int`. Uses the
    existing COALESCE write per row and returns the number of rows changed.
  - `@Query suspend fun restoreWatchedAt(episodeId: String, watchedAt: Instant): Int`. Conditional
    on `watchedAt IS NULL`.
  - `@Transaction suspend fun restoreAll(previous: Map<String, Instant>): Int`
  - `@Transaction suspend fun clearEpisodeReturningPrevious(episodeId: String): Map<String, Instant>`
  - `@Transaction suspend fun clearSeasonReturningPrevious(mediaId: String, seasonNumber: Int): Map<String, Instant>`
  - `@Query("SELECT * FROM episodes WHERE id IN (:ids)") suspend fun getByIds(ids: List<String>): List<EpisodeEntity>`.
    This goes on `TVWriteDao` so the transactions can read it.
- Removes `markSeasonWatched`. Its only caller (`TVShowRepository.setSeasonWatched`) is removed in
  Task 3.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class UntickRestoreTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository
    private val t1 = Instant.parse("2024-03-03T12:00:00Z")
    private val t2 = Instant.parse("2026-10-12T12:00:00Z")

    @BeforeTest fun setUp() { db = testAppDatabase(); repo = TVShowRepository(db) }
    @AfterTest fun tearDown() = db.close()

    private suspend fun show(): String {
        val r = repo.addShow(title = "S", seasons = listOf(SeasonQuickFill(1, episodeCount = 2)))
        assertIs<Resource.Success<String>>(r); return r.data
    }

    @Test fun clearThenRestore_putsBackTheExactTimestamp() = runTest {
        val id = db.episodeDao().getByMediaIdAndSeason(show(), 1).first().id
        db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t1))
        val previous = db.tvWriteDao().clearEpisodeReturningPrevious(id)
        assertEquals(mapOf(id to t1), previous)
        assertEquals(null, db.episodeDao().getById(id)!!.watchedAt)
        assertEquals(1, db.tvWriteDao().restoreAll(previous))
        assertEquals(t1, db.episodeDao().getById(id)!!.watchedAt)
    }

    @Test fun restore_neverOverwritesAReTick() = runTest {
        val id = db.episodeDao().getByMediaIdAndSeason(show(), 1).first().id
        db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t1))
        val previous = db.tvWriteDao().clearEpisodeReturningPrevious(id)
        db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t2)) // re-ticked before Undo
        assertEquals(0, db.tvWriteDao().restoreAll(previous))
        assertEquals(t2, db.episodeDao().getById(id)!!.watchedAt)
    }

    @Test fun markEpisodesWatchedAt_neverOverwritesAnExistingDate() = runTest {
        val id = db.episodeDao().getByMediaIdAndSeason(show(), 1).first().id
        db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t1))
        assertEquals(0, db.tvWriteDao().markEpisodesWatchedAt(mapOf(id to t2)))
        assertEquals(t1, db.episodeDao().getById(id)!!.watchedAt)
    }

    @Test fun clearSeason_returnsOnlyThePreviouslyWatched() = runTest {
        val showId = show()
        val (a, _) = db.episodeDao().getByMediaIdAndSeason(showId, 1)
        db.tvWriteDao().markEpisodesWatchedAt(mapOf(a.id to t1))
        assertEquals(mapOf(a.id to t1), db.tvWriteDao().clearSeasonReturningPrevious(showId, 1))
    }
}
```

- [ ] **Step 2: Run and confirm the tests fail.**
  Run: `./gradlew :shared:jvmTest --tests '*UntickRestoreTest' -q`.
  Expected: a compile failure, because the methods are missing.

- [ ] **Step 3: Implement.** Add these to `TVWriteDao` and delete `markSeasonWatched`:

```kotlin
    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<EpisodeEntity>

    /** Marks each episode at its own time, in one transaction; already-watched rows keep their date (#131). */
    @Transaction
    suspend fun markEpisodesWatchedAt(stamps: Map<String, Instant>): Int =
        stamps.entries.sumOf { (id, at) -> markEpisodeWatched(id, at) }

    /**
     * Undo's write (#131). Conditional on `watchedAt IS NULL`: an episode re-ticked before Undo keeps
     * its newer date, and a deleted one matches nothing.
     */
    @Query("UPDATE episodes SET watchedAt = :watchedAt WHERE id = :episodeId AND watchedAt IS NULL")
    suspend fun restoreWatchedAt(episodeId: String, watchedAt: Instant): Int

    @Transaction
    suspend fun restoreAll(previous: Map<String, Instant>): Int =
        previous.entries.sumOf { (id, at) -> restoreWatchedAt(id, at) }

    /** Reads then clears in one transaction, so Undo carries exactly what was removed. */
    @Transaction
    suspend fun clearEpisodeReturningPrevious(episodeId: String): Map<String, Instant> {
        val previous = getByIds(listOf(episodeId)).mapNotNull { e -> e.watchedAt?.let { e.id to it } }.toMap()
        clearEpisodeWatched(episodeId)
        return previous
    }

    @Query("SELECT * FROM episodes WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber")
    suspend fun episodesInSeason(mediaId: String, seasonNumber: Int): List<EpisodeEntity>

    @Transaction
    suspend fun clearSeasonReturningPrevious(mediaId: String, seasonNumber: Int): Map<String, Instant> {
        val previous = episodesInSeason(mediaId, seasonNumber).mapNotNull { e -> e.watchedAt?.let { e.id to it } }.toMap()
        clearSeasonWatched(mediaId, seasonNumber)
        return previous
    }
```

  Room KMP permits default-bodied `@Transaction` functions on an interface DAO; this file already
  has `insertShowAtomically`. If KSP rejects a default body, convert the declaration to
  `abstract class TVWriteDao` and keep the queries `abstract`.

- [ ] **Step 4: Run the tests and confirm they pass.** Same command. Expected: 4 tests, 0 failures.
  `TVShowRepository` still compiles until Task 3? **No:** `setSeasonWatched` calls
  `markSeasonWatched`. So in this task, temporarily change that call to
  `markEpisodesWatchedAt(episodesInSeason(mediaId, seasonNumber).filter { it.watchedAt == null }.associate { it.id to clock.now() })`.
  Task 3 deletes it.
- [ ] **Step 5: Falsify the guard.** Drop `AND watchedAt IS NULL` from `restoreWatchedAt`, confirm
  `restore_neverOverwritesAReTick` fails, then restore it.
- [ ] **Step 6: Commit** with the message `TVWriteDao: atomic dated marks, read-then-clear, conditional restore (#131)`.

---

### Task 3: `MarkEpisodesWatchedUseCase` and repository untick API

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/tv/domain/MarkEpisodesWatchedUseCase.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/tv/data/TVShowRepository.kt`. Delete
  `setSeasonWatched`; add `untickEpisode`, `clearSeason` and `restoreWatched`; and make
  `setEpisodeWatched(watched = true)` delegate to nothing. Remove the `watched = true` branch
  entirely and rename the method to `untickEpisode` (see below).
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/AppContainer.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/tv/domain/MarkEpisodesWatchedUseCaseTest.kt`

**Interfaces:**
- Consumes (Task 1): `WatchedWhen`, `resolveWatchedAt`, `isAired`.
- Consumes (Task 2): `markEpisodesWatchedAt`, `getByIds`, `clearEpisodeReturningPrevious`,
  `clearSeasonReturningPrevious`, `restoreAll`.
- Produces:
  - `class MarkEpisodesWatchedUseCase(db: AppDatabase, clock: Clock = Clock.System, zone: () -> TimeZone = { TimeZone.currentSystemDefault() })`.
    Its method is `suspend fun execute(episodeIds: List<String>, watchedWhen: WatchedWhen): Resource<Int>`
    and returns the number of episodes written.
  - `fun now(): Instant`. View models use it for `isAired`, so the whole feature shares one clock.
  - On `TVShowRepository`:
    - `suspend fun untickEpisode(episodeId: String): Resource<Map<String, Instant>>`
    - `suspend fun clearSeason(mediaId: String, seasonNumber: Int): Resource<Map<String, Instant>>`
    - `suspend fun restoreWatched(previous: Map<String, Instant>): Resource<Int>`
  - `AppContainer.markEpisodesWatchedUseCase: MarkEpisodesWatchedUseCase`.

**What `execute` does:**
1. It loads the episodes with `getByIds`.
2. It **skips episodes that are already watched or unaired** for a multi-episode request (season
   or catch-up). A **single explicitly-ticked** episode is written even if unaired; the spec allows
   that. So the use case writes `ids.first()` unconditionally (apart from the `IS NULL` guard) and
   filters the rest with `isAired`.
3. It resolves each episode's time. If any time resolves to `null` (`OnAirDate` with an unknown or
   future air date), it returns `Resource.Error("No air date for …")` **before writing anything**.
4. It calls `markEpisodesWatchedAt`. If that throws, Room rolls the transaction back; the use case
   catches the exception and returns `Resource.Error`.

- [ ] **Step 1: Write the failing tests.** Use the `SteppingClock` pattern from
  `jvmTest/.../tv/data/UpNextQueryTest.kt`, with `zone = { TimeZone.of("America/Los_Angeles") }`.
  The tests:
  - `now_marksTargetAndCatchUp` checks that a target plus two earlier ids → 3 rows, all equal to
    `clock.now`.
  - `onAirDate_givesEachEpisodeItsOwnCalendarDay`: insert episodes with `airDate` via
    `db.tvWriteDao().insertEpisodes`, and assert `toLocalDateTime(la).date` equals each air date.
  - `onAirDate_missingOnAnyEpisode_writesNothing`: the result is `Resource.Error`, and every
    `watchedAt` is still null.
  - `multiEpisodeRequest_skipsUnaired`: a season of 3 with E3 airing tomorrow, under `Now`, writes 2.
  - `singleUnairedTarget_isStillWritten`.
  - `alreadyWatched_isNeverOverwritten`.

  Write each as concrete `@Test` functions with explicit `Instant.parse` values, following the
  style of the Task 2 test above.
- [ ] **Step 2: Run and confirm the tests fail** (compile error):
  `./gradlew :shared:jvmTest --tests '*MarkEpisodesWatchedUseCaseTest' -q`
- [ ] **Step 3: Implement**

```kotlin
package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The single path every tick takes (#131): one episode, a catch-up, or a season. See the spec
 * `docs/superpowers/specs/2026-10-12-tick-with-date-design.md` for the rules this enforces.
 *
 * The first id is the episode the user explicitly ticked and is written even if unaired; the rest
 * (catch-up or a season) are filtered to aired episodes. Nothing is written unless every time
 * resolves, and the write is one transaction.
 */
public class MarkEpisodesWatchedUseCase(
    private val db: AppDatabase,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val logger: Logger = AppLogger,
) {
    public fun now(): Instant = clock.now()

    public suspend fun execute(
        episodeIds: List<String>,
        watchedWhen: WatchedWhen,
        targetIsExplicit: Boolean = true,
    ): Resource<Int> =
        try {
            val now = clock.now()
            val byId = db.tvWriteDao().getByIds(episodeIds).associateBy { it.id }
            val wanted =
                episodeIds.mapIndexedNotNull { index, id ->
                    byId[id]?.takeIf { it.watchedAt == null && ((targetIsExplicit && index == 0) || isAired(it.airDate, now)) }
                }
            val stamps = wanted.associate { it.id to resolveWatchedAt(watchedWhen, it.airDate, now, zone()) }
            if (stamps.values.any { it == null }) {
                Resource.Error("An episode has no past air date")
            } else {
                @Suppress("UNCHECKED_CAST")
                Resource.Success(db.tvWriteDao().markEpisodesWatchedAt(stamps as Map<String, Instant>))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to mark ${episodeIds.size} episode(s) watched" }
            Resource.Error("Failed to mark watched: ${e.message ?: "Unknown error"}", cause = e)
        }

    private companion object {
        const val TAG = "MarkEpisodesWatched"
    }
}
```

  For a **season**, the view model passes `targetIsExplicit = false`, so every id is aired-filtered.

  In `TVShowRepository`:
  - Replace `setEpisodeWatched` and `setSeasonWatched` with the three methods below. Each wraps its
    DAO call in the existing try/catch shape and returns `Resource`.
  - `untickEpisode` → `db.tvWriteDao().clearEpisodeReturningPrevious(id)`. Return an error if the
    episode doesn't exist; check that first with `episodeDao().getById`.
  - `clearSeason` → `clearSeasonReturningPrevious`.
  - `restoreWatched` → `restoreAll`.
  - **Caller fixes:** grep for `setEpisodeWatched(` and `setSeasonWatched(`. In tests that used them
    to *set up* watched state (`UpNextQueryTest`, `TVShowRepositoryTest`, `LibraryViewModelTest`,
    `TVShowDetailViewModelTest`, `UpNextViewModelTest`), replace each with
    `MarkEpisodesWatchedUseCase(db, clock).execute(listOf(id), WatchedWhen.Now)`.
  - In `AppContainer`, add
    `public val markEpisodesWatchedUseCase: MarkEpisodesWatchedUseCase = MarkEpisodesWatchedUseCase(database)`,
    using whatever the container names its `AppDatabase`; check that file.

- [ ] **Step 4: Run** `./gradlew :shared:jvmTest -q`. Expected: everything passes, including the
  migrated callers.
- [ ] **Step 5: Falsify the guard.** Remove the `stamps.values.any { it == null }` guard and confirm
  `onAirDate_missingOnAnyEpisode_writesNothing` fails, then restore it.
- [ ] **Step 6: Commit** with the message `MarkEpisodesWatchedUseCase: the single dated tick path; repository untick/restore (#131)`.

---

### Task 4: `TVShowDetailViewModel` — pending tick, untick Undo, markable seasons

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/TVShowDetailViewModel.kt`
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/ViewModelFactories.kt`
  (pass `appContainer.markEpisodesWatchedUseCase`)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/TVShowDetailViewModelTest.kt`

**Interfaces:**
- Consumes (Task 3): `MarkEpisodesWatchedUseCase.execute/now`, `TVShowRepository.untickEpisode/clearSeason/restoreWatched`.
- Produces, in `com.hub.media.ui`, shared with Task 5:

```kotlin
/** What the "Mark … watched?" dialog is about to write (#131). Ids are captured here, never re-read. */
public data class PendingTick(
    val title: String?,               // episode title, or null -> "Episode N" fallback in UI
    val episodeNumber: Int,
    val seasonNumber: Int,
    val isSeason: Boolean,
    val targetIds: List<String>,      // one id, or a season's markable ids
    val earlierIds: List<String>,     // catch-up candidates (empty for a season)
    val airDateAvailable: Boolean,    // with catch-up OFF
    val airDateAvailableWithEarlier: Boolean,
    val initialPickMillis: Long?,     // target airDate epoch millis (midnight UTC), for the picker
)

/** A just-cleared watch state, offered back as Undo. Only the latest is live. */
public data class UntickEvent(val id: Long, val previous: Map<String, Instant>, val label: String)
```

  - `TVShowDetailUiState.Ready` gains `pendingTick: PendingTick? = null` and `untickEvent: UntickEvent? = null`.
  - `SeasonGroup` gains `markableCount: Int`: unwatched, aired episodes.
  - The view model's API:
    - Keep `setEpisodeWatched(episodeId, watched)` (the screen still calls it). `true` →
      `requestTick(episodeId)`; `false` → untick with Undo.
    - Keep `setSeasonWatched(seasonNumber, watched)`. `true` → `requestSeasonTick(seasonNumber)`;
      `false` → clear the season with Undo.
    - `confirmTick(watchedWhen: WatchedWhen, includeEarlier: Boolean)`, `dismissTick()`,
      `undo(event: UntickEvent)`, `consumeUntick(id: Long)`.
  - The constructor gains
    `markEpisodesWatched: MarkEpisodesWatchedUseCase` and
    `settingsRepository` (already present since #88). Specials come from `observeIncludeSpecials()`.

- [ ] **Step 1: Write the failing tests** in `TVShowDetailViewModelTest`, following the existing
  `readyViewModel` helper. Construct the use case with a `SteppingClock`. The tests:
  - `tick_opensPrompt_andConfirmNow_marksIt`: after `setEpisodeWatched(id, true)`, the state has a
    `pendingTick` with `targetIds == [id]`, and the episode is still unwatched. After
    `confirmTick(Now, false)`, the episode is watched and `pendingTick == null`.
  - `catchUp_marksEarlierEpisodes`: tick E3 of a fresh season → `earlierIds == [E1, E2]`.
    `confirmTick(Now, true)` → all three watched.
  - `untick_thenUndo_restoresExactTimestamp`: watch E1 at `t1` through the use case;
    `setEpisodeWatched(E1, false)` gives an `untickEvent` whose `previous == {E1: t1}`;
    `undo(event)` puts back `watchedAt == t1`.
  - `undoAfterReTick_keepsTheNewDate`.
  - `seasonTick_countsOnlyAiredUnwatched`: a season of 3 with E3 airing tomorrow →
    `markableCount == 2`; `setSeasonWatched(1, true)` → `pendingTick.targetIds.size == 2`.
  - `doubleConfirm_writesOnce`: install `StandardTestDispatcher` as Main for the test; call
    `confirmTick` twice; `runCurrent()`; the write count is one. Assert through the use case's
    returned count captured by a spy, or through `watchedAt` unchanged across a clock step between
    the calls.
  - `failedWrite_emitsNothing`: a use case over a closed DB (`db.close()` before confirm), or a fake
    returning `Resource.Error` → the state has `errorMessage`, and no episode is watched.

  Each test asserts with `uiState.first { … }`.
- [ ] **Step 2: Run and confirm the tests fail.**
  Run: `./gradlew :shared:jvmTest --tests '*TVShowDetailViewModelTest' -q`.
- [ ] **Step 3: Implement**
  - Add private flows `pendingTick` and `untickEvent`, and a sequence counter. Fold them into the
    outer `combine` (it now has 4 sources; adding 2 makes 6). Nest a `combine(pendingTick, untickEvent, ::Pair)`
    so no call exceeds 5 sources.
  - `requestTick(id)`:
    - read `episodes` from the current Ready state's `seasons`;
    - `val now = markEpisodesWatched.now()`;
    - `includeSpecials = settingsRepository.getIncludeSpecials()`;
    - `earlier = if (isAired(target.airDate, now) && (includeSpecials || target.seasonNumber > 0)) earlierUnwatched(...) else emptyList()`;
    - `airDateAvailable = resolveWatchedAt(OnAirDate, target.airDate, now, UTC) != null`. The zone
      is irrelevant to null-ness.
    - `airDateAvailableWithEarlier = airDateAvailable && earlier.all { resolveWatchedAt(OnAirDate, it.airDate, now, UTC) != null }`.
  - `requestSeasonTick(n)`: `targetIds` = the season's unwatched, aired ids; `isSeason = true`;
    `earlierIds = []`; `airDateAvailableWithEarlier = airDateAvailable` = every target resolves. If
    `targetIds` is empty, do nothing.
  - `confirmTick(when, includeEarlier)`:
    - `val tick = pendingTick.value ?: return`, then `pendingTick.value = null` **before** launching.
      That is the double-confirm guard.
    - `ids = tick.targetIds + if (includeEarlier) tick.earlierIds else emptyList()`.
    - `markEpisodesWatched.execute(ids, when, targetIsExplicit = !tick.isSeason)`.
    - On error, set `errorMessage`.
  - Untick: `tvShowRepository.untickEpisode(id)`. On success with a non-empty map →
    `untickEvent.value = UntickEvent(++seq, map, label = episode title or "Episode N" key)`. The
    label carries the raw title; the UI formats it. The season version is the same, through
    `clearSeason`.
  - `undo(event)`: `consumeUntick(event.id)`, then `restoreWatched(event.previous)`.
  - `markableCount`: compute it in the inner combine where `SeasonGroup`s are built, using
    `markEpisodesWatched.now()`.
- [ ] **Step 4: Run** `./gradlew :shared:jvmTest -q`. Expected: everything passes.
- [ ] **Step 5: Falsify the guard.** Remove `pendingTick.value = null` before the launch and confirm
  `doubleConfirm_writesOnce` fails, then restore it.
- [ ] **Step 6: Commit** with the message `TVShowDetailViewModel: tick prompt state, catch-up, untick Undo (#131)`.

---

### Task 5: `UpNextViewModel` — prompt replaces the instant tick

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/UpNextViewModel.kt`
- Modify: `app/.../ui/ViewModelFactories.kt` (`UpNextViewModelFactory` passes the use case)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/UpNextViewModelTest.kt`

**Interfaces:**
- Consumes: `PendingTick` (Task 4), `MarkEpisodesWatchedUseCase` (Task 3), `earlierUnwatched` and
  `resolveWatchedAt` (Task 1).
- Produces: `UpNextUiState` drops `markedEvent` and gains `pendingTick: PendingTick?`. The methods
  are `requestTick(row: UpNextRow)`, `confirmTick(WatchedWhen, Boolean)` and `dismissTick()`.
  **Delete** `MarkedWatchedEvent`, `markWatched`, `undo` and `consumeMarked`.
- `requestTick` loads the show's episodes with `tvShowRepository.observeEpisodes(row.mediaId).first()`
  and builds `PendingTick` exactly as Task 4 does. **Extract** that builder into a shared internal
  function `buildPendingTick(target, episodes, includeSpecials, now): PendingTick` in
  `com.hub.media.ui`, used by both view models. Task 4's implementation should already put it
  there; if not, move it now.

- [ ] **Step 1: Replace the tests.** Replace `markWatched_advancesTheShow_andUndoPutsItBack` with
  `requestThenConfirm_advancesTheShow`: `requestTick(row)` → `pendingTick != null`;
  `confirmTick(Now, false)` → the queue's single row is E3. Keep
  `includeSpecialsSetting_decidesWhetherASpecialIsOffered`.
- [ ] **Step 2: Run and confirm the test fails:**
  `./gradlew :shared:jvmTest --tests '*UpNextViewModelTest' -q`
- [ ] **Step 3: Implement** as described in Interfaces.
- [ ] **Step 4: Run** `./gradlew :shared:jvmTest -q` and confirm it passes.
- [ ] **Step 5: Commit** with the message `UpNextViewModel: tick goes through the dated prompt (#131)`.

---

### Task 6: `MarkWatchedDialog` (stateless) + Robolectric tests + golden

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/MarkWatchedDialog.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/components/MarkWatchedDialogTest.kt`
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/goldens/MarkWatchedDialogGoldenTest.kt`

**Interfaces:**
- Consumes: `PendingTick` (Task 4) and `WatchedWhen` (Task 1).
- Produces:
  `@Composable fun MarkWatchedDialog(tick: PendingTick, onConfirm: (WatchedWhen, includeEarlier: Boolean) -> Unit, onDismiss: () -> Unit)`.
  It holds the checkbox state and the "showing picker" state with `rememberSaveable`, and hides
  On air date when the relevant `airDateAvailable*` flag is false.

Add these strings:

```xml
<string name="mark_watched_title_episode">Mark \"%1$s\" watched?</string>
<string name="mark_watched_title_season">Mark the %1$d unwatched episodes of Season %2$d watched?</string>
<plurals name="mark_watched_include_earlier">
    <item quantity="one">Also mark the earlier episode</item>
    <item quantity="other">Also mark the %d earlier episodes</item>
</plurals>
<string name="mark_watched_now">Now</string>
<string name="mark_watched_on_air_date">On air date</string>
<string name="mark_watched_pick_date">Pick a date…</string>
<string name="mark_watched_untick_snackbar">Unmarked \"%1$s\"</string>
```

The cancel and undo buttons reuse the existing `cancel_button` and `up_next_undo`.

- [ ] **Step 1: Write the failing Robolectric tests.** Follow the `@RunWith(RobolectricTestRunner::class)`
  shape of `RestoreConfirmationDialogTest`:
  - `now_callsBackWithNow_andCheckboxState`: tick the checkbox, tap Now → `(WatchedWhen.Now, true)`.
  - `onAirDate_hidden_whenUnavailable`: `airDateAvailable = false` → no node with that text.
  - `onAirDate_hidden_whenCatchUpTickedAndAnEarlierEpisodeLacksOne`: `airDateAvailable = true`,
    `airDateAvailableWithEarlier = false`. Visible before the checkbox, gone after.
  - `seasonTick_hasNoCatchUpCheckbox`.
  - `cancel_callsDismiss`.
  - **Large font:** set `LocalDensity` with `fontScale = 2f`, and assert every button has
    `assertIsDisplayed()` with a non-zero-height bounds inside the root.
- [ ] **Step 2: Write the golden test.** Add `MarkWatchedDialogGoldenTest` following
  `MismatchReviewScreenGoldenTest`, with `@GraphicsMode(NATIVE)` and `captureGolden(name = "mark-watched-dialog", alsoAssert = { assertTextIsShown("Now", "On air date", "Pick a date…") })`.
  Use the fullest state: an episode title, 3 earlier episodes, and air date available.
- [ ] **Step 3: Run and confirm the tests fail:**
  `./gradlew :app:testDebugUnitTest --tests '*MarkWatchedDialog*' -q`. Expected: a compile failure.
- [ ] **Step 4: Implement the dialog**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkWatchedDialog(
    tick: PendingTick,
    onConfirm: (WatchedWhen, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var includeEarlier by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val airDateShown = if (includeEarlier) tick.airDateAvailableWithEarlier else tick.airDateAvailable
    if (picking) {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val state = rememberDatePickerState(
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC).date <= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = {
                    val date = Instant.fromEpochMilliseconds(state.selectedDateMillis!!).toLocalDateTime(TimeZone.UTC).date
                    onConfirm(WatchedWhen.On(date), includeEarlier)
                }) { Text(stringResource(R.string.mark_watched_pick_date).trimEnd('…')) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel_button)) } },
        ) { DatePicker(state = state) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (tick.isSeason) {
                    stringResource(R.string.mark_watched_title_season, tick.targetIds.size, tick.seasonNumber)
                } else {
                    stringResource(
                        R.string.mark_watched_title_episode,
                        tick.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.up_next_untitled_format, tick.episodeNumber),
                    )
                },
            )
        },
        text = {
            if (tick.earlierIds.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().toggleable(includeEarlier, role = Role.Checkbox) { includeEarlier = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeEarlier, onCheckedChange = null)
                    Text(pluralStringResource(R.plurals.mark_watched_include_earlier, tick.earlierIds.size, tick.earlierIds.size))
                }
            }
        },
        // Stacked full-width: four choices do not fit in a row at large font scales.
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onConfirm(WatchedWhen.Now, includeEarlier) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mark_watched_now))
                }
                if (airDateShown) {
                    OutlinedButton(onClick = { onConfirm(WatchedWhen.OnAirDate, includeEarlier) }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.mark_watched_on_air_date))
                    }
                }
                OutlinedButton(onClick = { picking = true }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mark_watched_pick_date))
                }
                TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel_button)) }
            }
        },
    )
}
```

  - **The picker's initial selection is the air date.** `PendingTick` therefore also needs
    `initialPickMillis: Long?`, set in Task 4's builder to the target's `airDate` epoch millis
    (midnight UTC, which is exactly what `DatePicker` expects). Add that field to Task 4's
    `PendingTick` before you start. Pass it as
    `rememberDatePickerState(initialSelectedDateMillis = tick.initialPickMillis, …)`.
  - The date picker's own **confirm** label must be a real string: add
    `<string name="mark_watched_pick_confirm">Mark watched</string>` and use it in place of the
    trimmed one above.
- [ ] **Step 5: Run the tests, record the golden, and look at it:**
  - `./gradlew :app:testDebugUnitTest --tests '*MarkWatchedDialog*' -q` (passes)
  - `./gradlew :app:recordRoborazziDebug -q`
  - Open `app/src/test/screenshots/mark-watched-dialog.png` and check it shows four stacked buttons
    and the checkbox.
- [ ] **Step 6: Commit in two parts.** Commit the code and tests with the message
  `MarkWatchedDialog: stateless dated tick prompt (#131)`. Commit the PNG separately with the
  message `Record the mark-watched dialog golden (#131)`.

---

### Task 7: Wire the routes, season control, instrumented picker test, changelog

**Files:**
- Modify: `app/.../ui/screens/TVShowDetailScreen.kt`
  - The route at around line 94: render `uiState.pendingTick?.let { MarkWatchedDialog(it, viewModel::confirmTick, viewModel::dismissTick) }`
    and an untick-Undo `LaunchedEffect`, the same shape as `UpNextScreenRoute`'s old one.
  - The season header at around line 759: show "Mark season watched" only when
    `season.markableCount > 0`; "Clear season" when `allWatched` is unchanged.
- Modify: `app/.../ui/screens/UpNextScreen.kt`. In the route, replace the marked-event effect with
  the dialog, and make `onMarkWatched = viewModel::requestTick`.
- Modify: `app/src/androidTest/.../ui/screens/TVShowDetailScreenTest.kt`, anywhere it asserts a
  season header button shown for a season with no markable episodes. Run it to find out.
- Create: `app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/ui/components/MarkWatchedDialogPickerTest.kt`.
  "Pick a date…" → the picker shows → choose a day → `onConfirm(WatchedWhen.On(thatDay), false)`.
  Also: the 1st of next month is not selectable.
- Modify: `CHANGELOG.md`, under `[Unreleased]` → `### Changed`:
  > - **Ticking an episode now asks when you watched it** (#131): Now, On air date (when the
  >   episode has one), or Pick a date. You can also mark the earlier unwatched episodes at the same
  >   time. Marking a season asks the same and skips episodes that haven't aired. Unticking is
  >   instant and offers Undo, which restores the exact date you had. Up next's check uses the same
  >   prompt.

- [ ] **Step 1: Wire the routes** as above. `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin -q`
  must be clean. Compiling androidTest is mandatory: #89 shipped a broken one once.
- [ ] **Step 2: Write `MarkWatchedDialogPickerTest`**, stateless with fake callbacks, like
  `UpNextScreenTest`.
- [ ] **Step 3: Run the full gate:**
  `./gradlew ktlintFormat` then
  `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug :app:assembleDebug`.
  If a TV show golden changes because the season button is hidden, re-record it, look at it, and
  commit it separately.
- [ ] **Step 4: Run the device suite.** Check `adb devices`; take a backup if the device has real
  data; run `./gradlew :app:connectedDebugAndroidTest` (the run **without**
  `leaveApksInstalledAfterRun`). Fix every failure. Then run `./gradlew :app:seedDebugDevice`.
- [ ] **Step 5: Test by hand over adb:**
  - tick Fleabag S1E4 → the prompt appears → On air date is absent (quick-filled episodes have no
    air date) → Now → it is ticked;
  - untick it → the snackbar → Undo → it is ticked again with the old date;
  - season mark → the count shown matches;
  - Up next's check → the same prompt.
- [ ] **Step 6: Commit** with the message
  `Wire the dated tick prompt into the show screen and Up next (#131)`.
- [ ] **Step 7:** Run `coderabbit review --agent --committed --base main -c AGENTS.md`, address any
  valid findings, push, and open a PR closing #131 (put `Closes #131` in the **body**; a title is
  not enough).

---

## Self-review notes

- **Spec coverage:**
  - Interaction rules → Tasks 1, 4 and 6.
  - Season rules → Tasks 3, 4 and 7.
  - Untick and Undo → Tasks 2 and 4.
  - Up next → Tasks 5 and 7.
  - Failure and double-confirm → Tasks 3 and 4.
  - Test lanes → Tasks 6 and 7.
  - Changelog → Task 7.
- **One deliberate deviation from the spec's wording:** catch-up candidates come from a pure
  Kotlin function over the show's episodes (`earlierUnwatched`), not a separate SQL query. The
  ordering key and predicate are copied from `observeUpNext`, and Task 1 pins them. The view models
  already hold the episode list, so a query would be a second read for no gain.
- **Type consistency:** `PendingTick` (Task 4) carries `initialPickMillis`, which Task 6 reads.
  `WatchedWhen`, `MarkEpisodesWatchedUseCase.execute(ids, watchedWhen, targetIsExplicit)` and
  `UntickEvent` are named the same in every task.
