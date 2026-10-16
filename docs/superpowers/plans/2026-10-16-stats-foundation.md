# Stats Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each stats period is one consistent snapshot from a single query, and the Stats screen shows one chosen period at a time (Week / Month / 30 days / Year / All time), remembered across visits.

**Architecture:**
- `StatsPeriod` and a pure bounds function (`features/stats`).
- One `StatsDao.observePeriodStats(from?, to?)` of four correlated scalar subqueries, so a write produces one consistent emission. It replaces the four per-field queries, which are removed.
- The `stats_period` setting.
- `StatsViewModel` reshaped to `period + stats + streak`, with `selectPeriod`.
- `StatsScreen` gets a chip row and one card; the lifetime card goes.

**Tech Stack:** Kotlin, Room KMP, kotlinx-datetime, Jetpack Compose (Material 3), Robolectric and Roborazzi, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-16-stats-foundation-design.md`

## Global Constraints

- **Null versus zero:** time read and pages read are `null` when no session in range has a known value, never 0. Session and book counts are real counts, so 0 is genuine.
- **Bounds:** periods are half-open, `[from, to)`. Local-day boundaries use the injected `TimeZone`, and "now" comes from the injected `Clock`.
- **Test placement:** Room-touching tests go in `shared/src/jvmTest`, and the pure bounds tests in `commonTest`. View model tests await `uiState.first { … }` and never read `.value` after an action, except the initial value.
- **No stats-repository fork,** and no new numbers beyond today's four plus the streak.
- **Not exported:** `stats_period` is not added to `EXPORTABLE_SETTING_KEYS`.
- **Goldens** are recorded by hand, looked at, and committed alone.
- **The CHANGELOG** gets the user-visible change, and ROADMAP Task 11 gets the theme order.
- **The gate:**
  - `./gradlew ktlintFormat`
  - `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`

## Review Focus

1. **The 30-day edge.** A session at 00:00 on the first day of the window is in; one at 23:59 the day before is out. This is pinned in Task 1.
2. **The year boundary in the local time zone.** At 23:30 on 31 December, local time, a session counts in that year even when UTC has already rolled over. This is pinned in Task 1, with a non-UTC zone.
3. **All time and undated finishes.** A finished book with a null `finishedAt` is in All time, and in neither Year nor any dated period. This is pinned in Task 2.
4. **One write, one consistent state.** Every emission after an insert is either the "before" or the "after" snapshot, never a mix. This is pinned in Task 3. It is #91 itself.
5. **The week-start setting** still moves the Week bounds, and only those. This is pinned in Task 3.

---

### Task 1: `StatsPeriod` and its bounds

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/StatsPeriod.kt`
- Test: `shared/src/commonTest/kotlin/com/hub/media/features/stats/data/StatsPeriodBoundsTest.kt`

**Interfaces:**
- Produces:
  - `public enum class StatsPeriod { WEEK, MONTH, LAST_30_DAYS, YEAR, ALL_TIME }`
  - `public fun StatsPeriod.bounds(timeZone: TimeZone, clock: Clock, weekStartDay: WeekStartDay): Pair<Instant, Instant>?`, which returns `null` for `ALL_TIME`.

- [ ] **Step 1: Failing tests.** Use a fixed clock at `2024-06-19T15:00` and a zone such as `TimeZone.of("America/New_York")`.
  - WEEK and MONTH equal `StatsRepository.thisWeekBounds` / `thisMonthBounds` for the same inputs.
  - LAST_30_DAYS = `[2024-05-21T00:00 local, 2024-06-20T00:00 local)`.
  - YEAR = `[2024-01-01T00:00 local, 2025-01-01T00:00 local)`.
  - ALL_TIME = `null`.
  - **Year edge:** with the clock at `2024-12-31T23:30` local in New York, YEAR's `to` is `2025-01-01T00:00` local, which is `05:00Z`. A `2024-12-31T23:30` local instant is inside.
  - **30-day edge:** the window's first local midnight is in, and the minute before it is out.
- [ ] **Step 2:** run `./gradlew :shared:jvmTest --tests "*StatsPeriodBoundsTest"`. Expected: a compile error.
- [ ] **Step 3: Implement.** WEEK and MONTH delegate to the existing companion functions. LAST_30_DAYS: `today.minus(29, DAY).atStartOfDayIn(tz) to today.plus(1, DAY).atStartOfDayIn(tz)`. YEAR: `LocalDate(today.year, 1, 1)` to `LocalDate(today.year + 1, 1, 1)`. KDoc each, including why 30 days includes today.
- [ ] **Step 4:** run the same command. Expected: PASS.
- [ ] **Step 5: Commit** as `StatsPeriod and its bounds (#91)`.

---

### Task 2: One query per period

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/StatsDao.kt`: add `observePeriodStats`; later remove the four per-field queries and both books-finished queries.
- Create: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/PeriodStatsRow.kt` (the projection)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/StatsRepository.kt`: add `observePeriodStats(period, timeZone, clock, weekStartDay): Flow<StatsUiState.Period>`; remove the per-field methods.
- Test: `shared/src/jvmTest/kotlin/com/hub/media/core/database/StatsDaoTest.kt`, and `…/features/stats/data/StatsRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `data class PeriodStatsRow(val timeReadSeconds: Long?, val sessionCount: Int, val pagesRead: Int?, val booksFinished: Int)`
  - `fun observePeriodStats(from: Instant?, to: Instant?, finished: ReadingStatus): Flow<PeriodStatsRow>`

- [ ] **Step 1: Failing DAO tests** for `observePeriodStats`, written by porting every existing per-field case in `StatsDaoTest` and `StatsRepositoryTest` onto the new query, so no covered behaviour is lost. Plus these new cases:
  - an empty range: `PeriodStatsRow(null, 0, null, 0)`;
  - sessions with a null duration are counted but not timed, and the time is `null` when every session in range has one;
  - a finished book with a null `finishedAt`: counted with `(null, null)` bounds, not counted with Year's bounds;
  - the bounds are half-open at both ends.
- [ ] **Step 2:** run `./gradlew :shared:jvmTest --tests "*StatsDaoTest" --tests "*StatsRepositoryTest"`. Expected: a compile error.
- [ ] **Step 3: Implement the query.**

```kotlin
    @Query(
        """
        SELECT
          (SELECT SUM(durationSeconds) FROM reading_sessions
             WHERE durationSeconds IS NOT NULL
               AND (:from IS NULL OR timestampStart >= :from) AND (:to IS NULL OR timestampStart < :to)) AS timeReadSeconds,
          (SELECT COUNT(*) FROM reading_sessions
             WHERE (:from IS NULL OR timestampStart >= :from) AND (:to IS NULL OR timestampStart < :to)) AS sessionCount,
          (SELECT SUM(deltaPages) FROM reading_sessions
             WHERE deltaPages IS NOT NULL
               AND (:from IS NULL OR timestampStart >= :from) AND (:to IS NULL OR timestampStart < :to)) AS pagesRead,
          (SELECT COUNT(*) FROM book_details
             WHERE status = :finished
               AND (:from IS NULL OR finishedAt >= :from) AND (:to IS NULL OR finishedAt < :to)) AS booksFinished
        """,
    )
    fun observePeriodStats(from: Instant?, to: Instant?, finished: ReadingStatus): Flow<PeriodStatsRow>
```

  `SUM` over zero rows is `NULL` in SQLite, which is the null-versus-zero rule for free.
  - A null bound with the type converter has to bind as `NULL`. Confirm the `Instant` converter accepts nulls, and if it doesn't, pass epoch-millis `Long?` parameters.
  - The repository maps the row to `StatsUiState.Period` and computes the bounds from `period.bounds(…)`.
- [ ] **Step 4:** run the same command. Expected: PASS.
- [ ] **Step 5: Remove the old per-field queries** and repository methods, and their now-ported tests. Then grep for any remaining caller; `StatsViewModel` is migrated in Task 3, so keep the old methods until then. Done here means "ported tests pass", and the removal happens in Task 3's final step.
- [ ] **Step 6: Commit** as `One query per stats period (#91)`.

---

### Task 3: The view model, with the setting

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/settings/data/StatsPeriodSetting.kt` (`observeStatsPeriod` / `setStatsPeriod`, key `"stats_period"`, default WEEK, modelled on `WeekStartDay.kt`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/StatsUiState.kt`, which becomes `isLoading`, `period: StatsPeriod = WEEK`, `stats: Period`, `currentStreakDays`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/StatsViewModel.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/StatsViewModelTest.kt` (rewritten to the new shape)

- [ ] **Step 1: Failing tests:**
  - the initial value is loading;
  - existing sessions appear under the default WEEK;
  - **`selectPeriod(MONTH)`** persists it: a new view model opens on MONTH;
  - an unknown stored value falls back to WEEK;
  - a week-start change moves WEEK's numbers. Port `uiState_weekStartDayChange_reBucketsWeekPeriodButNotMonth` as "re-buckets WEEK, and MONTH is unchanged after selecting it";
  - **the #91 regression, `aWrite_neverPublishesAHalfwayState`:** hold `uiState` subscribed, record every `stats` value in `backgroundScope`, insert one session with duration and pages, await the settled state, then assert every recorded `stats` equals either the before-snapshot or the after-snapshot.
- [ ] **Step 2: See it red on behaviour.** First implement the new `StatsUiState` and `selectPeriod` on top of the *old* four-flow `combine` (one `periodFlow(bounds)`), and run the tests. Expected: the selection tests pass, and `aWrite_neverPublishesAHalfwayState` fails with a mixed emission. #91 observed 4 failures in 6 local runs, so run it up to 5 times and record what was seen. If it never shows red in 5 runs, record that, and rely on Task 2's single-query structure as the guarantee.
- [ ] **Step 3: Implement.** The flow is `observeStatsPeriod()` combined with `observeWeekStartDay()`, then `flatMapLatest { (p, w) -> repository.observePeriodStats(p, tz, clock, w).map { p to it } }`, combined with the streak, into `StatsUiState(isLoading = false, period, stats, streak)`. `selectPeriod(p) = viewModelScope.launch { settingsRepository.setStatsPeriod(p) }`.
- [ ] **Step 4:** run `./gradlew :shared:jvmTest --tests "*StatsViewModelTest"` (run the regression test 3 times). Expected: PASS.
- [ ] **Step 5: Remove the old DAO and repository methods** (Task 2, Step 5). Run `./gradlew :shared:jvmTest`. Expected: PASS.
- [ ] **Step 6: Commit** as `StatsViewModel: one period at a time, remembered; old per-field queries removed (#91)`.

---

### Task 4: The screen

**Files:**
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/StatsScreen.kt`:
  - add a `StatsPeriodRow` of `FilterChip`s in a `LazyRow`;
  - one `PeriodCard` titled for the period;
  - remove `LifetimeBooksFinishedCard`;
  - `StatsScreen(uiState, onSelectPeriod, onNavigateBack)`, with the route passing `viewModel::selectPeriod`.
- Modify: `strings.xml`:
  - chip labels `stats_period_week` "Week", `_month` "Month", `_30_days` "30 days", `_year` "Year", `_all` "All time";
  - card titles `stats_last_30_days_title` "Last 30 days", `stats_this_year_title` "This year", `stats_all_time_title` "All time";
  - remove `stats_books_finished_lifetime_title`.
- Modify: `ui/TestTags.kt` (add `Stats.LIST`, `Stats.PERIODS`)
- Modify: `app/src/test/…/goldens/StatsScreenGoldenTest.kt` (the fixture's new shape: MONTH with an unknown time)
- Test: `app/src/test/…/ui/screens/StatsScreenTest.kt`

- [ ] **Step 1: Failing tests:**
  - each chip calls `onSelectPeriod` with its period;
  - the selected chip reports selected;
  - the card title follows `uiState.period` for all five;
  - a `null` time shows `stats_unknown_value`;
  - **the lifetime title is gone:** `stats_books_finished_lifetime_title` is removed, so assert on the literal "Books finished (lifetime)" being absent, beside the streak card being present as the positive control;
  - a loading state shows the spinner and no chips.
- [ ] **Step 2:** run `./gradlew :app:testDebugUnitTest --tests "*StatsScreen*"`. Expected: a compile error, then behaviour failures once the parameter is stubbed.
- [ ] **Step 3: Implement.** The chip row is `LazyRow(horizontalArrangement = spacedBy(8.dp))` over `StatsPeriod.entries`, using `FilterChip(selected = period == it, onClick = { onSelectPeriod(it) })`. Tag the `LazyColumn` `Stats.LIST` and the chip row `Stats.PERIODS`.
- [ ] **Step 4:** run the same command. Expected: PASS.
- [ ] **Step 5: Commit** as `Stats screen: a period picker and one card (#91)`.

---

### Task 5: Goldens, docs, the gate and the device

- [ ] **Goldens:** run `./gradlew :app:verifyRoborazziDebug --tests "*StatsScreenGoldenTest"` and expect a FAIL, because the image changed. Then record, view, verify, and commit the PNG alone as `Golden: stats with the period picker (#91)`.
- [ ] **ROADMAP Task 11:** add a short "Stats theme (2026-10-16)" entry listing #91, #219 + #217, #221 and #220 in order, with the one-engine rule.
- [ ] **CHANGELOG** `[Unreleased]`:
  - under `### Changed`: "Stats has a period picker: this week, this month, the last 30 days, this year, or all time. It remembers your choice. The separate lifetime card is now the All time view. (#91)"
  - under `### Fixed`: "Stats numbers update together after you log a session, instead of briefly disagreeing with each other. (#91)"
- [ ] **The full gate.** Expected: pass.
- [ ] **Device:** the full `connectedDebugAndroidTest`, then `seedDebugDevice`, then a hand check:
  - each period against the seeded sessions;
  - the choice survives killing the app;
  - after logging a session, every number updates together.
