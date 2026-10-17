# Films and Shows Stats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Stats gets Books, Films and Shows tabs. Films and Shows show viewings, time watched, per-month counts on Year, a 24-hour "when you watch" dial and weekdays.
- Shows also gets top shows and, on All time, shows finished.
- The dial replaces Books' time-of-day bars.

**Architecture:**
- A remembered domain joins part 1's `flatMapLatest` restart. The Books pipeline is untouched; Films and Shows each read one viewings query, and a pure `viewingBreakdown` derives every number from it.
- "Shows finished" reuses the library's own `LibraryStatusFilter.ofShow` over the same progress inputs.
- The Stats route takes an optional domain.

**Tech Stack:** Kotlin, Room KMP, kotlinx-datetime, Jetpack Compose (Material 3, `Canvas`), Robolectric and Roborazzi, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-17-films-shows-stats-design.md`

## Global Constraints

- **One engine:** never fork `StatsRepository`. The Books tab's existing tests must pass untouched.
- **Unknown is never zero:** a viewing without a runtime is counted as "without a runtime" and never adds 0 minutes. Its time is `null` when no viewing has one.
- **Exact-noon marker:** a viewing at exactly 12:00:00.000 local counts by weekday and month, but not in the dial or the parts of the day.
- **Local time:** use the injected `TimeZone` everywhere.
- **`:app` sees no kotlinx-datetime types** (Graphban `m_2fbf676302`): weekdays are ISO day numbers, and hours are `0..23` indexes.
- **No schema change.** `git diff shared/schemas` stays empty.
- **Test location:** Room tests go in `jvmTest`, pure functions in `commonTest`. View model tests await `uiState.first { … }`.
- **Goldens** are viewed, then committed alone. A golden fixture must agree with itself (`m_ab00cbec8a`). Cards below the fold are scrolled into view inside `alsoAssert`.
- **Graphban text** names the agent ("MT-A4 (Claude Code) …"). **After a push**, summarise and stop; do not poll CI.
- **The gate:**
  - `./gradlew ktlintFormat`
  - `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`

## Review Focus

1. **A rewatch whose first viewing is before the period** still counts as a rewatch. Pinned in Task 3.
2. **The same film watched twice at the same instant** gives exactly one rewatch, not zero or two. Pinned in Task 3.
3. **12:00:00.001 is a real time; 12:00:00.000 is not,** in a non-UTC zone. Pinned in Task 4.
4. **A fully watched show that is still airing is not "finished",** and a show whose only unwatched episodes are specials flips with the setting. Pinned in Task 5.
5. **Opening Stats from the Films library opens Films,** even when Books was last used. Pinned in Task 7 (route test).

---

### Task 1: Hours for Books, and the clock dial

The dial (`ui/components/ClockDial.kt`) was built as an owner-approved visual spike and is on disk,
uncommitted. This task gives it its data, a translatable string, tests, and the Books tab.

**Files:**
- Modify: `shared/.../features/stats/data/ReadingBreakdown.kt`. Add `hours: List<Long>` (24 slots, seconds; default `List(24) { 0L }`), filled by `local.hour` in `readingBreakdown`.
- Modify: `app/.../ui/components/ClockDial.kt`. "busiest" becomes `R.string.stats_busiest`.
- Modify: `app/.../ui/screens/StatsBreakdownCards.kt`. `WhenYouReadCard` replaces the day-part `BarChart` with:
  - a `ClockDial(hours = breakdown.hours, busiest = <busiest part's plural label>, description = <the day-part description>)`, tagged `Stats.DAYPART_CHART`;
  - a legend: one `BreakdownRow` per part with time, e.g. "Evening · 5h 30m".
  - The weekday `BarChart` stays.
- Tests:
  - `ReadingBreakdownTest`: `hours` sums to `totalSeconds`, and a session at 21:30 local lands in `hours[21]`.
  - `StatsScreenTest`: the existing part-of-day description test still passes, and a new test checks that the legend shows "Evening" with its time.

- [ ] **Red first** for `hours` and the legend, then green.
- [ ] **Dial sabotage:** drop `require(hours.size == 24)`, pass 23 values from a test, and expect the test `clockDial_needsTwentyFourHours` (with `assertFailsWith`) to fail. Restore.
- [ ] Re-record and view `stats-breakdown`. Commit the code, then the PNG alone.
- [ ] **Commit** as `Stats: a 24-hour clock dial for when you read (#217)`.

### Task 2: The domain setting

**Files:** create `shared/.../features/settings/data/StatsDomain.kt`, a mirror of `StatsPeriod`'s setting file.
- `observeStatsDomain(): Flow<MediaType>` reads `stats_domain`; an unknown or missing value is `BOOK`.
- `setStatsDomain(MediaType)`.
- It is not exported, because it is a view preference.
- Test: `StatsDomainSettingTest` (jvmTest) covers the round trip, the default `BOOK`, garbage reading as `BOOK`, and the key's absence from `EXPORTABLE_SETTING_KEYS`.

- [ ] Red, green, commit as `Stats: remember the last tab (#217)`.

### Task 3: The viewings query

**Files:** create `core/database/dao/ViewingRow.kt`; modify `StatsDao` and `StatsRepository`.

**Interfaces:**
- `data class ViewingRow(val watchedAt: Instant, val mediaId: String, val episodeId: String?, val title: String, val runtimeMinutes: Int?, val isRewatch: Boolean)`
- `StatsDao.observeViewings(from: Instant?, to: Instant?, type: MediaType): Flow<List<ViewingRow>>`, with the spec's SQL (§2).
- `StatsRepository.observeViewings(period, timeZone, clock, weekStartDay, type)`, bounded like `observePeriodStats`.

- [ ] **Tests first (jvmTest):**
  - type filtering: a film's log is not on Shows;
  - bounds: inclusive start, exclusive end, and null bounds for All time;
  - runtime comes from the film for a film and from the episode for an episode, and is null when unknown;
  - `isRewatch` is false for a first viewing and true for a second;
  - a viewing is still a rewatch when its first viewing is before `from`;
  - for two same-instant viewings, exactly one is a rewatch;
  - an episode rewatch is per episode: watching episode 2 after episode 1 is not a rewatch.
- [ ] Stub the query as a valid but wrong one (Room validates SQL), see the tests fail, then implement and see them pass. Check `git diff shared/schemas` is empty.
- [ ] **Sabotage:** drop the `p.id < w.id` tie-break. Expect the same-instant test to fail, then restore.
- [ ] **Commit** as `Stats: viewings with runtime and rewatch, for films and shows (#217)`.

### Task 4: The viewing breakdown (pure)

**Files:** create `shared/.../features/stats/data/ViewingBreakdown.kt` and `ViewingBreakdownTest` (commonTest).

**Interfaces:**
```kotlin
public data class TitleTime(val title: String, val seconds: Long)
public data class ViewingBreakdown(
    val viewings: Int = 0, val rewatches: Int = 0, val titles: Int = 0,
    val knownSeconds: Long? = null, val unknownRuntime: Int = 0,
    val hours: List<Int> = List(24) { 0 }, val dayParts: List<Int> = List(4) { 0 }, val untimed: Int = 0,
    val weekdays: List<Int> = emptyList(), val weekdayCounts: List<Int> = List(7) { 0 },
    val months: List<Int>? = null, val topTitles: List<TitleTime> = emptyList(),
)
public fun isPickedDate(at: Instant, timeZone: TimeZone): Boolean  // exactly 12:00:00.000 local
public fun viewingBreakdown(rows: List<ViewingRow>, timeZone: TimeZone, weekStart: WeekStartDay, withMonths: Boolean): ViewingBreakdown
```
- `topTitles` covers shows only in use, but is computed generally: titles with known time, at most 5, ties by title.
- The KDoc carries §7's date-only import rule.

- [ ] **Tests first:**
  - **Noon marker:** 12:00:00.000 in Kolkata is skipped from `hours` and `dayParts`, counted in `untimed`, and kept in `weekdayCounts` and `months`. 12:00:00.001 is kept everywhere.
  - **Runtimes:** two viewings with runtimes 120 and null give `knownSeconds = 7200` and `unknownRuntime = 1`. All-null runtimes give `knownSeconds == null`.
  - **Counts:** `rewatches` and `titles` (distinct `mediaId`).
  - **Weekdays** follow the week start.
  - **Months** are filled only when `withMonths`, bucketed in the local zone.
  - **Top titles:** the five most first, ties by title; a title with no known time is left out.
  - **Empty input** gives zero viewings, with the weekdays still laid out.
- [ ] Red, then green.
- [ ] **Sabotage:** treat 12:00 to the second as picked, ignoring the millis. Expect the 12:00:00.001 test to fail, then restore.
- [ ] **Commit** as `Stats: viewing numbers, with picked dates kept out of the clock (#217)`.

### Task 5: Shows finished

**Files:**
- Modify: `StatsDao`, adding `observeShowStates(): Flow<List<ShowStateRow>>` (`SELECT mediaId, status, airingStatus FROM tv_details`).
- Create: `shared/.../ui/ShowsFinished.kt`, containing `fun countFinishedShows(states: List<ShowStateRow>, progress: Map<String, TVProgressCounts>, includeSpecials: Boolean): Int`. It maps each show through `LibraryStatusFilter.ofShow(status, progress[id]?.counted(includeSpecials), airingStatus)` and counts `FINISHED`.
- Test: `ShowsFinishedTest` (commonTest), plus a DAO test for `observeShowStates`.

- [ ] **Tests first:** an ended fully-watched show counts; a continuing fully-watched show does not; an abandoned show does not; a show with no episodes does not; a show whose only unwatched episode is a special counts with specials off and not with them on.
- [ ] Red, then green.
- [ ] **Sabotage:** ignore `airingStatus`. Expect the continuing-show test to fail, then restore.
- [ ] **Commit** as `Stats: shows finished, by the library's own rule (#217)`.

### Task 6: The view model

**Files:** `StatsUiState` and `StatsViewModel`, with tests in `StatsViewModelTest`.

**Interfaces:**
- `StatsUiState` gains `domain: MediaType = MediaType.BOOK` and `viewing: ViewingStats? = null`.
- `data class ViewingStats(val breakdown: ViewingBreakdown, val showsFinished: Int?)`.
- `selectDomain(MediaType)`.

**Behaviour:**
- The outer `combine` adds `observeStatsDomain()`.
- When `domain == BOOK`, the inner pipeline is exactly today's.
- Otherwise, it is `observeViewings(period, …, domain)` mapped through `viewingBreakdown(…, withMonths = period == YEAR)`.
- For `TV_SHOW` on `ALL_TIME`, it also combines `observeShowStates()`, `episodeDao.observeProgress()` and `observeIncludeSpecials()` into `countFinishedShows`; otherwise `showsFinished = null`.
- Everything is read against the same snapshot clock `at`.
- Build `viewingBreakdown` in a `.map {}` on the viewings flow, so it runs only when viewings change.

- [ ] **Tests first:**
  - `selectDomain(MOVIE)` gives a state with `viewing` set and the domain remembered;
  - films only on Films, episodes only on Shows;
  - `showsFinished` is non-null only for Shows on All time;
  - every existing Books test passes untouched.
- [ ] Red, then green, running the whole `:shared:jvmTest`.
- [ ] **Sabotage:** give `showsFinished` on every period. Expect the test to fail, then restore.
- [ ] **Commit** as `Stats: films and shows in the view model (#217)`.

### Task 7: Navigation

**Files:**
- `NavRoutes.Stats`: `route = "stats?domain={domain}"`, plus `createRoute(domain: MediaType?)` and `parseDomain`, as `Library` does.
- `AppNavigation`:
  - the hub passes `null`;
  - the library passes its `domain`;
  - `StatsScreenRoute(domain = parsed)` calls `viewModel.selectDomain(it)` once, in a `LaunchedEffect(domain)`, when the domain is non-null.
- `LibraryScreen`: the stats icon shows for every domain, not only Books and All media.
- Tests:
  - `LibraryScreenTest`: the stats icon exists on the Films and Shows libraries.
  - In `androidTest` (a route test, because only the real `MainActivity` proves the wiring, AGENTS.md §7): from the hub, open Films and tap the stats icon. The Films tab is selected.

- [ ] Red, then green (the route test runs in Task 9's device run).
- [ ] **Commit** as `Stats: each library opens its own tab (#217)`.

### Task 8: The screen

**Files:**
- `StatsScreen`:
  - a `DomainRow` of three `FilterChip`s (Books, Films, Shows) in a `selectableGroup`, tagged `Stats.DOMAINS`, above the period chips;
  - the body switches on `uiState.domain`. Books is unchanged; Films and Shows render `ViewingTab(uiState)`.
- Create `ui/screens/StatsViewingCards.kt` (split by region) with:
  - `ViewingPeriodCard`:
    - Films: "Films watched: 9 (2 rewatches)";
    - Shows: "Episodes watched: 34 across 4 shows";
    - both: "Time watched: 14h 20m · 1 without a runtime", or "—";
    - Shows on All time: "Shows finished: N".
  - On Year, a `MonthChart` with the title "Films watched by month" or "Episodes watched by month".
  - `WhenYouWatchCard`: `ClockDial` (counts as `Long`) when at least one viewing is timed, plus its legend ("Evening · 12 viewings"); "N logged without a time" when `untimed > 0`; and the weekday `BarChart` of counts.
  - `TopShowsCard` (Shows only).
  - The empty state: "Nothing watched in this period".
- Strings, plurals and TestTags (`Stats.DOMAINS`, `Stats.WHEN_WATCH_CARD`, `Stats.TOP_SHOWS_CARD`).
- Tests (`StatsScreenTest`):
  - the domain chips select, and the selected chip reports selected;
  - the Films period card with and without rewatches;
  - time watched with unknowns, and "—";
  - shows finished only on All time;
  - the dial hidden when every viewing is untimed, with the untimed line shown;
  - the top shows rows;
  - the empty state;
  - the goal and streak cards absent on Films.

- [ ] Red, then green.
- [ ] **Sabotage:** show the goal card on every tab. Expect the Films test to fail, then restore.
- [ ] Check the IME grep.
- [ ] **Commit** as `Stats screen: Books, Films and Shows tabs (#217)`.

### Task 9: Goldens, docs, the gate and the device

- [ ] **Goldens:**
  - `stats-films` (Month, with rewatches and an unknown runtime);
  - `stats-shows` (All time, with shows finished, scrolled to the dial and top shows in `alsoAssert`);
  - `clock-dial`.

  View all three, and any re-recorded existing golden. Commit the PNGs alone.
- [ ] **CHANGELOG:**
  - `### Added`: "Stats has Films and Shows tabs: what you watched, time watched (where runtimes are known), a by-month chart on Year, top shows, shows finished under All time, and a 24-hour clock of when you watch. Each library's stats button opens its own tab. (#217)"
  - `### Changed`: "When you read is now a 24-hour clock instead of bars. (#217)"
- [ ] **The full gate.**
- [ ] **Device:** the full suite, including the new route test, then `seedDebugDevice` (Play Protect is the owner's call). Then a hand check against `docs/sample-data/watch_logs_sample.csv` and `episodes_sample.csv`, recomputed by script in the phone's zone: viewings, rewatches, time and unknowns, weekdays, the untimed count, top shows and shows finished.
- [ ] **Then:**
  - the Opus whole-branch review, re-graded by effect, with fixes test-first;
  - local CodeRabbit;
  - push and open the PR, then stop rather than poll CI;
  - Graphban: evidence on MT-49, in review, lessons, and an outcomes file naming the agent.
