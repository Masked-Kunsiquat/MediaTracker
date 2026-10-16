# Books Stats Part 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Stats shows time as "22h 48m".
- Each period gains reading speed, average session and longest session.
- A yearly reading goal card (set by tapping), and a books-per-month bar chart on the Year view.

**Architecture:**
- The new per-period numbers are extra columns on #91's single `observePeriodStats` query (one engine, one consistent emission).
- The goal is an `app_settings` value, `"<year>:<books>"`, included in archive exports; its pace is a pure function.
- Months come from a finish-date query bucketed in Kotlin with the injected time zone, resolved in the same `flatMapLatest` as the period and the streak.
- The goal dialog lives in its own component file. It has a text field, so the test that types into it runs on the device.

**Tech Stack:** Kotlin, Room KMP, kotlinx-datetime, Jetpack Compose (Material 3, `Canvas`), Robolectric and Roborazzi, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-16-books-stats-part1-design.md`

## Global Constraints

- One engine: no new independent per-period flow, and never a fork of `StatsRepository`.
- **Null versus zero:** unknown values are `null` and render as "—"; only real counts are 0.
- Room tests go in `jvmTest`, and pure functions in `commonTest`. View model tests await `uiState.first { … }`.
- **A text field inside a dialog cannot be typed into in Robolectric** (Graphban `m_6729e5145c`). The typing test goes to `app/src/androidTest`, with a comment saying why.
- **The IME check** (`m_487dfa9bad`): the dialog lives in `ui/components/ReadingGoalDialog.kt`, so no file has both a `Scaffold` and a text field. Confirm the "Compose IME insets" grep is satisfied.
- **Goldens** are recorded by hand, viewed, and committed alone. The chart must also be checked **on the phone** (`m_c0c23ebc7c`).
- **The CHANGELOG** gets the user-visible change.
- **The gate:**
  - `./gradlew ktlintFormat`
  - `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`

## Review Focus

1. **Speed is never inflated by a session missing a value.** Only sessions with both a duration > 0 and pages count. This is pinned in Task 2.
2. **A finish at 23:30 local on 31 May is May,** not June. This is pinned in Task 4, with a non-UTC zone.
3. **Last year's goal does not show in January.** This is pinned in Task 3.
4. **Pace on the first day of the year, and in a leap year.** This is pinned in Task 3.
5. **The halfway-state guard still holds** with the new columns. This is pinned in Task 2 by re-running `aWrite_neverPublishesAHalfwayState`.

---

### Task 1: The time format

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/text/DurationFormat.kt`, containing `fun formatDuration(seconds: Long): String`, pure, returning "0m", "45m" or "22h 48m".
- Modify: `StatsScreen.kt`. `formatTimeReadOrUnknown` uses it, keeping "—" for `null`.
- Test: `app/src/test/…/ui/text/DurationFormatTest.kt`

- [ ] **Failing tests:** 0 → "0m"; 59 → "0m"; 2700 → "45m"; 3600 → "1h 0m"; 82080 → "22h 48m".
- [ ] **Red:** a compile error. **Implement**, then **green.** **Commit** as `Stats: time read as 22h 48m (#219)`.

### Task 2: Speed and session lengths on the single query

**Files:**
- `PeriodStatsRow.kt`: add `timedSessionCount: Int`, `longestSessionSeconds: Long?`, `speedSeconds: Long?` and `speedPages: Int?`.
- `StatsDao.observePeriodStats`: four more scalar subqueries with the same range clause.
  - `COUNT(*) … durationSeconds IS NOT NULL`
  - `MAX(durationSeconds)`
  - `SUM(durationSeconds)` with `durationSeconds > 0 AND deltaPages > 0` (corrected after review: was `deltaPages IS NOT NULL`, which let a backwards session make speed negative)
  - `SUM(deltaPages)` with the same condition
- `StatsUiState.Period`: add `averageSessionSeconds: Long?`, `longestSessionSeconds: Long?` and `pagesPerHour: Int?`.
- `StatsViewModel.toPeriod()`:
  - **average** = `timeReadSeconds / timedSessionCount` when the count > 0;
  - **pages per hour** = `speedPages * 3600 / speedSeconds` when both are non-null and seconds > 0, rounded to the nearest integer.
- Tests: `PeriodStatsDaoTest`, `StatsViewModelTest`

- [ ] **Failing DAO tests:**
  - speed counts only sessions with both values and a duration > 0, and is `null` when there are none;
  - longest is `null` with no known duration;
  - the timed count leaves out sessions with no duration;
  - the empty-range row's new fields are `0` / `null`.
- [ ] **Failing view model tests:** the derived average, pages per hour and longest, for a fixed set of sessions. Existing `Period(…)` constructions in tests use named arguments or defaults, so they keep compiling.
- [ ] **Red, implement, green.** Run `aWrite_neverPublishesAHalfwayState` 3 times.
- [ ] **Sabotage:** drop the `deltaPages IS NOT NULL` condition from the speed sums. Expect the speed test to fail, then restore.
- [ ] **Commit** as `Stats: speed, average and longest session, on the same query (#219)`.

### Task 3: The yearly goal (setting, pace, export)

**Files:**
- Create: `shared/.../features/settings/data/ReadingGoal.kt`:
  - `data class ReadingGoal(val year: Int, val books: Int)`;
  - `observeReadingGoal(): Flow<ReadingGoal?>` (the raw stored value, `null` when unparsable or when books is outside 1..999);
  - `setReadingGoal(year, books)` and `clearReadingGoal()`.
- Create: `shared/.../features/stats/data/GoalPace.kt`, with `fun goalProgress(goal: Int, finished: Int, today: LocalDate): GoalProgress`:
  - `GoalProgress(goal, finished, expected: Int, status: OnPace | Behind(n) | Reached)`;
  - expected = `floor(goal * dayOfYear / daysInYear)`; Behind n = `expected - finished`, so n is the books still needed to be on pace (corrected after review: was the ceiling, which overstated n by one).
- Modify: `ExportManifest.EXPORTABLE_SETTING_KEYS` (add `"reading_goal"`), and `ImportArchiveUseCase.restoreSetting` (validate `^\d{4}:\d{1,3}$` with books 1..999).
- Tests:
  - `shared/src/jvmTest/.../settings/ReadingGoalTest.kt`;
  - `shared/src/commonTest/.../stats/data/GoalPaceTest.kt`;
  - `ExportArchiveUseCaseTest.settings_onlyTheAllowlist_neverCredentials`: add the goal to the expected map;
  - `ZipImportExportTest`: `restoreSettings_true_appliesAllFour`, plus an invalid goal that is skipped and noted.

- [ ] **Failing tests:**
  - a round trip;
  - a malformed value and books = 0 both read as no goal;
  - clearing works;
  - **pace:** on pace, behind by N, reached, day 1 (expected 0, so on pace), 31 December, and a leap year (366 days);
  - **export:** the goal is exported;
  - **import:** a valid goal is restored, and an invalid one (`2026:0`, `abc`) is skipped with a note.
- [ ] **Red, implement, green.** **Commit** as `Reading goal: setting, pace and export (#219)`.

### Task 4: Books finished per month

**Files:**
- `StatsDao`: add `observeFinishedAtInRange(from: Instant, to: Instant, finished: ReadingStatus): Flow<List<Instant>>`, i.e. `SELECT finishedAt FROM book_details WHERE status = :finished AND finishedAt >= :from AND finishedAt < :to`.
- `StatsRepository`: add `observeFinishesByMonth(year bounds, timeZone): Flow<List<Int>>`, 12 counts with January at index 0, bucketed in Kotlin.
- Tests: `PeriodStatsDaoTest` (or `StatsDaoTest`), and `StatsRepositoryTest`

- [ ] **Failing tests:**
  - only FINISHED rows in range are returned;
  - the bucketing produces 12 entries;
  - **in New York time, a finish at 2024-05-31T23:30 local counts in May (index 4)** even though it is June in UTC.
- [ ] **Red, implement, green.**
- [ ] **Sabotage:** bucket in UTC. Expect the May test to fail, then restore.
- [ ] **Commit** as `Stats: books finished per month, bucketed in the local zone (#219)`.

### Task 5: The view model brings it together

**Files:**
- `StatsUiState` gains:
  - `goal: GoalProgress?`, which is `null` when there is no goal for the current year;
  - `goalYear: Int`, so the card can say "Set a 2026 goal";
  - `finishesByMonth: List<Int>?`, set only when the period is `YEAR`.
- `StatsViewModel`:
  - inside the existing `flatMapLatest`, combine the period snapshot, the streak, the goal (`observeReadingGoal()`, kept only when `year == today.year`) and, for YEAR, the months;
  - the goal's `finished` is the YEAR snapshot's `booksFinished`. That means one extra `observePeriodStats(YEAR)` when the period isn't YEAR, which is still a single query, scoped to the year;
  - add `setGoal(books: Int)` (this year) and `clearGoal()`.
- Test: `StatsViewModelTest`

- [ ] **Failing tests:**
  - with no goal, `goal == null` and `goalYear == 2024`;
  - `setGoal(12)` with 5 finished this year gives `GoalProgress(12, 5, …)`;
  - a stored goal for 2023 reads as `null`;
  - `clearGoal` works;
  - `finishesByMonth` is set on YEAR and is `null` on WEEK;
  - the halfway-state guard still holds.
- [ ] **Red, implement, green**, with the full `:shared:jvmTest`. **Commit** as `StatsViewModel: the goal and the months (#219)`.

### Task 6: The screen (card lines, goal card and dialog, chart)

**Files:**
- `StatsScreen.kt`:
  - the period card gets three lines;
  - a `GoalCard` above the streak card;
  - a "Books finished by month" card with `MonthBarChart` when `finishesByMonth != null`;
  - the dialog is shown from the screen's state;
  - new callbacks `onSetGoal(Int)` and `onClearGoal()`, wired in the route.
- Create: `ui/components/MonthBarChart.kt`, a `Canvas` with 12 bars, single-letter month labels, a thin baseline for zero months, and one semantics node whose content description lists the months with finishes or says "No books finished yet this year".
- Create: `ui/components/ReadingGoalDialog.kt`, an `AlertDialog` with a numeric `OutlinedTextField` (digits only, 1..999), Save (disabled when invalid), Cancel, and Remove when a goal exists.
- `strings.xml`: the new labels and plurals.
- `TestTags`: `Stats.GOAL_CARD`, `Stats.MONTH_CHART`.
- Tests:
  - `StatsScreenTest` (Robolectric): the lines and their unknowns, both goal-card states, tapping opens the dialog, the dialog's Remove and Cancel (no typing), and the chart only on YEAR with its description;
  - `app/src/androidTest/…/ReadingGoalDialogTest.kt`: types "12" and saves → `onSave(12)`; types "0" → Save disabled. **Keep the comment** saying why this lives on the device.

- [ ] **Failing tests, red, implement, green.** Confirm by grep that no file has both `Scaffold` and a text field without `contentWindowInsets`.
- [ ] **Sabotage:** show the chart on every period. Expect the WEEK test to fail, then restore.
- [ ] **Commit** as `Stats screen: speed lines, goal card and dialog, month chart (#219)`.

### Task 7: Goldens, docs, the gate and the device

- [ ] **Goldens:** `stats` is re-recorded (the new lines and the goal card). The new `stats-year` shows the chart and a goal on pace. Record and view them, then commit the PNGs alone.
- [ ] **CHANGELOG** `[Unreleased]`, under `### Added`: "Stats shows your reading speed, average and longest session, a yearly reading goal you can set by tapping, and a chart of books finished per month in the Year view. Time read now reads like '22h 48m'. (#219)"
- [ ] **The full gate.**
- [ ] **Device:** run the full `connectedDebugAndroidTest`, including the new dialog test, then `seedDebugDevice`. Watch for the Play Protect prompt; it is the owner's decision. Then a hand check:
  - set, edit and remove a goal;
  - the chart on Year, **looked at on the phone**;
  - the speed lines against the seeded sessions;
  - "22h 48m".
