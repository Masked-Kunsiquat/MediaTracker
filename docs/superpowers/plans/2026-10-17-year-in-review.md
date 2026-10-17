# Year in Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A swipe-through year in review across books, films and shows, for any year with activity, reached from Stats' Year view and the hub. Reading goals stop being forgotten each year.

**Architecture:**
- A pure `yearReview(...)` builds every page from reads the stats engine already has, bounded by a chosen year's local dates.
- One new read: the books finished that year, with page counts.
- `TimedSessionRow` gains the book's id and title.
- A `YearReviewViewModel(year)` feeds a `HorizontalPager` screen.
- The `reading_goal` setting becomes a per-year list.

**Tech Stack:** Kotlin, Room KMP, kotlinx-datetime, Jetpack Compose (Material 3, foundation pager), Robolectric and Roborazzi, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-17-year-in-review-design.md`

## Global Constraints

- **One engine:** reuse `StatsRepository` reads; never a fork. **No schema change** (`git diff shared/schemas` stays empty).
- **Unknown is never zero:** unknown runtimes are counted, never summed as 0.
- **Picked dates** (`isPickedDate`) count by day and month, never on the clock.
- **Local time:** the injected `TimeZone` everywhere.
- **`:app` sees no kotlinx-datetime types:** months, days and hours are numbers; dates are formatted in `:app` from ISO numbers via `java.time`.
- **Test location:** Room tests in `jvmTest`, pure functions in `commonTest`. View model tests await `uiState.first { … }`. A test about ordering or races feeds the bad sequence rather than relying on timing (`m_b26129d272`).
- **Goldens:** fixtures agree with themselves (`m_ab00cbec8a`); the PNGs are viewed, then committed alone.
- **Every year page sets `LocalContentColor`** to its container's "on" colour, so reused components (`ClockDial`) read on any page colour. The renders showed the dial's centre text going dark otherwise.
- **Graphban text** names the agent ("MT-A4 (Claude Code)"). **After a push**, summarise and stop; don't poll CI.
- **The gate:**
  - `./gradlew ktlintFormat`
  - `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`

## Review Focus

1. **A binge day straddling midnight** counts by local day: 23:30 and 00:30 are two days. Pinned in Task 3.
2. **A stored single goal `"2026:12"`** still reads after the format change, and setting 2027 keeps 2026. Pinned in Task 1.
3. **A year with no activity at all** gives the quiet summary, not a crash or an empty pager. Pinned in Tasks 3 and 5.
4. **The year picker** offers exactly first-activity-year through the current year. Pinned in Task 2 (the DAO) and Task 4.
5. **Tapping the right third versus the left third** pages forward versus back; TalkBack has labelled buttons. Pinned in Task 5.

---

### Task 1: Reading goals for every year

**Files:** `features/settings/data/ReadingGoal.kt`, `features/portability/domain/ImportArchiveUseCase.kt`, and `ui/StatsViewModel.kt`, plus tests in `ReadingGoalTest` (jvmTest), `StatsViewModelTest` and the import tests.

**Interfaces:**
- `fun String.toReadingGoalsOrNull(): Map<Int, Int>?`: all entries are `year:books` with books 1..999 and years unique, or `null`.
- `fun SettingsRepository.observeReadingGoals(): Flow<Map<Int, Int>>` gives an empty map when none or malformed.
- `fun SettingsRepository.observeReadingGoal(year: Int): Flow<ReadingGoal?>`
- `suspend fun SettingsRepository.setReadingGoal(year: Int, books: Int)` reads, puts the entry, and writes the list in year order.
- `suspend fun SettingsRepository.clearReadingGoal(year: Int)` removes that year's entry; an empty list clears the key.
- `suspend fun SettingsRepository.setReadingGoals(goals: Map<Int, Int>)`, used by the import.
- The old no-argument `observeReadingGoal()` and `clearReadingGoal()` are removed. `StatsViewModel` reads `observeReadingGoal(today.year)` and calls `clearGoal()` → `clearReadingGoal(year)`.

- [ ] **Tests first:**
  - an existing `"2026:12"` reads as `{2026: 12}`;
  - setting 2027 keeps 2026 and stores `"2026:12,2027:20"`;
  - clearing 2026 keeps 2027;
  - clearing the last entry clears the key;
  - malformed lists read as empty: `"2026:12,2026:13"` (duplicate year), `"2026:12,x"` and `"2026:0"`;
  - the import restores a two-year list and refuses a malformed one;
  - the Stats goal card still shows this year's goal and not last year's.

  Update the existing tests that use the removed functions.
- [ ] Red, then green, running all of `:shared:jvmTest`.
- [ ] **Sabotage:** `setReadingGoal` overwrites the whole value. Expect the keep-2026 test to fail, then restore.
- [ ] **Commit** as `Reading goal: keep every year's goal (#221)`.

### Task 2: The reads a year needs

**Files:** `TimedSessionRow`, and `StatsDao` (the timed-sessions join, plus `observeFinishedBooks` and `observeFirstActivity`). Create `core/database/dao/FinishedBookRow.kt` and `features/stats/data/YearBounds.kt`.

**Interfaces:**
- `TimedSessionRow` gains `mediaId: String` and `title: String`, from `JOIN media_items mi ON mi.id = s.mediaId`.
- `data class FinishedBookRow(val mediaId: String, val title: String, val totalPages: Int?, val authors: String?, val finishedAt: Instant)`
- `StatsDao.observeFinishedBooks(from: Instant, to: Instant, finished: ReadingStatus): Flow<List<FinishedBookRow>>`
- `StatsDao.observeFirstActivity(): Flow<Instant?>`: `SELECT MIN(t) FROM (SELECT MIN(timestampStart) AS t FROM reading_sessions UNION ALL SELECT MIN(watchedAt) FROM watch_logs UNION ALL SELECT MIN(finishedAt) FROM book_details)`.
- `public fun yearBounds(year: Int, timeZone: TimeZone): Pair<Instant, Instant>`
- `StatsRepository` gets year-bounded wrappers:
  - `observePeriodStatsIn(bounds)`
  - `observeTimedSessionsIn(bounds)`
  - `observeViewingsIn(bounds, type)`
  - `observeFinishedBooks(bounds)`
  - `observeFirstActivity()`

  These are thin pass-throughs that take bounds directly. The period-based functions call them with `period.bounds(...)`, so there is still one path to each query.

- [ ] **Tests first (jvmTest):**
  - `TimedSessionRow` has the book's title;
  - `observeFinishedBooks` is bounded and FINISHED-only, with `totalPages` null when unknown;
  - `observeFirstActivity` is the earliest of the three sources (a watch log earlier than any session wins), and null on an empty database;
  - `yearBounds(2026, America/New_York)` starts at local midnight on 1 Jan.

  The existing `TimedSessionsDaoTest` and `ReadingBreakdownTest` fixtures gain the two fields.
- [ ] Red, then green. Schemas are unchanged.
- [ ] **Sabotage:** drop the `watch_logs` branch from `observeFirstActivity`. Expect its test to fail, then restore.
- [ ] **Commit** as `Stats: the reads a year in review needs (#221)`.

### Task 3: The review (pure)

**Files:** create `features/stats/data/YearReview.kt` and `YearReviewTest` (commonTest).

**Interfaces:**
```kotlin
public data class YearTotals(val booksFinished: Int, val films: Int, val episodes: Int, val readSeconds: Long, val watchSeconds: Long, val unknownRuntime: Int)
public data class LongestBook(val title: String, val pages: Int)
public data class LongestSession(val title: String, val seconds: Long)
public data class GoalResult(val goal: Int, val finished: Int, val reached: Boolean)
public data class RewatchedFilm(val title: String, val viewings: Int)
public data class BingeDay(val year: Int, val month: Int, val day: Int, val dayOfWeekIso: Int, val episodes: Int, val topShow: String)
public data class Rhythm(val busiestMonth: Int /* 1..12 */, val hours: List<Long>, val busiestPart: DayPart, val reading: Boolean)
public data class YearReview(
    val year: Int, val totals: YearTotals,
    val longestBook: LongestBook?, val topAuthor: AuthorTime?, val longestSession: LongestSession?,
    val goal: GoalResult?, val rewatchedFilm: RewatchedFilm?, val bingeDay: BingeDay?, val rhythm: Rhythm?,
)
public fun yearReview(
    year: Int, timeZone: TimeZone, weekStart: WeekStartDay,
    periodStats: PeriodStatsRow, sessions: List<TimedSessionRow>,
    films: List<ViewingRow>, episodes: List<ViewingRow>,
    finishedBooks: List<FinishedBookRow>, goal: ReadingGoal?,
): YearReview
```
The rules are the spec's §3.
- `rhythm` is `null` when nothing in the year has time.
- `busiestMonth` uses combined reading and known watching seconds per local month.
- The clock is reading `hours` when `readSeconds > 0`, else the timed viewings' hour counts.

- [ ] **Tests first**, one per rule:
  - the longest-book tie goes to the earliest finished, and a book without pages is ignored;
  - the top author comes from `readingBreakdown`;
  - the longest session names its book;
  - the goal's `reached`, and `null` without a goal;
  - the rewatched film needs viewings ≥ 2, with ties by title;
  - the binge day needs ≥ 2 episodes, buckets 23:30 and 00:30 local into two days, names the show with the most episodes that day, and ties go to the earliest day;
  - the busiest month by combined time;
  - the rhythm uses reading hours when there is reading time, else viewing hours, and is `null` when nothing is timed;
  - the quiet year: zero totals, every highlight `null`.
- [ ] Red, then green.
- [ ] **Sabotage:** bucket the binge day in UTC. Expect the midnight test to fail, then restore.
- [ ] **Commit** as `Year in review: every page from one year of reads (#221)`.

### Task 4: The view model

**Files:** create `ui/YearReviewViewModel.kt` and `YearReviewUiState`; add a factory in `ViewModelFactories.kt`. Test: `YearReviewViewModelTest` (jvmTest).

**Interfaces:**
- `YearReviewUiState(isLoading, years: List<Int> /* newest first */, year: Int, review: YearReview?)`
- `YearReviewViewModel(statsRepository, settingsRepository, timeZone, clock, initialYear: Int?)`.
- `selectYear(year)`.
- The years list runs from `observeFirstActivity()`'s local year to the current year, or just the current year when there is no activity.
- The review is combined from the Task 2 reads over `yearBounds(year)`, plus `observeReadingGoal(year)` and the week start.

- [ ] **Tests first:**
  - the years list from a 2024 first activity to "now" (2026) is `[2026, 2025, 2024]`;
  - with no activity it is `[2026]`;
  - `selectYear(2025)` rebuilds with 2025's numbers (a film in 2025 and one in 2026; the counts follow the year);
  - the goal follows the year.
- [ ] Red, then green.
- [ ] **Sabotage:** ignore the selected year (always the current year). Expect the 2025 test to fail, then restore.
- [ ] **Commit** as `Year in review: the view model (#221)`.

### Task 5: The screen and its entry points

**Files:**
- Create `ui/screens/YearReviewScreen.kt` (the route plus the stateless screen) and `ui/screens/YearReviewPages.kt` (one composable per page).
- `NavRoutes.YearReview` (`year_review?year=`) and `AppNavigation`.
- Entry points:
  - in `StatsScreen`, on Year (every tab), a card "Your 2026 so far" whose tap calls `onOpenYearReview`;
  - in `HubScreen`, a row "Year in review".
- Strings and TestTags (`YearReview.PAGER`, `YearReview.NEXT`, `YearReview.PREVIOUS`, `YearReview.CLOSE`, `YearReview.YEARS`).

**Behaviour:**
- The pages are built from the review's non-null parts, in the spec's order:
  - the first page is the year picker plus the title ("Your 2026 so far" / "Your 2025");
  - then the totals, longest book, top author, longest session, goal, rewatched film, binge day, rhythm and summary.
- Each page fills the screen with a rotating theme container colour and `LocalContentColor` set.
- The rhythm page uses `ClockDial` at full size.
- A tap on the right or left third pages forward or back. ✕ closes. Progress dots at the top.
- For TalkBack, labelled Next and Previous buttons sit in the page's semantics: each page is one node announcing its text, with custom actions "Next page" and "Previous page".
- The quiet year shows the year page and the summary ("A quiet year: nothing logged").

- **Tests (Robolectric):**
  - absent pages are skipped (a review with only totals has 3 pages: year, totals, summary);
  - a right-third tap advances and a left-third tap goes back (`performTouchInput { click(...) }`);
  - ✕ calls `onClose`;
  - the TalkBack custom actions page through;
  - the year picker lists the years and calls `onSelectYear`;
  - the Stats Year card appears only on Year;
  - the hub row calls its callback;
  - the quiet year.
- [ ] Red, then green.
- [ ] **Sabotage:** keep `null` pages in the list. Expect the skipping test to fail, then restore.
- [ ] Check the IME grep (no text field).
- [ ] **Commit** as `Year in review: the story screen, from Stats and the hub (#221)`.

### Task 6: Goldens, docs, the gate and the device

- [ ] **Goldens:**
  - `year-review-totals`;
  - `year-review-binge` (light);
  - `year-review-rhythm` (dark);
  - re-record and view any golden the new entry card or hub row moves (`stats-year`, the hub golden if any).

  Commit the PNGs alone.
- [ ] **CHANGELOG** `### Added`:
  - "A year in review: swipe through your year in books, films and shows, from your totals to your longest book, biggest binge and busiest month, for this year so far or any earlier year. Open it from Stats' Year view or the hub. (#221)"
  - "Your reading goal is now kept for every year, instead of only the current one. (#221)"
- [ ] **The full gate.**
- [ ] **Device:** the full suite, then `seedDebugDevice`. Then a hand check of 2026 and 2025 against `docs/sample-data`, recomputed by script in the phone's zone: totals, longest book, binge day and busiest month.
- [ ] **Then:**
  - the Opus whole-branch review, with findings re-graded by effect and fixed test-first;
  - local CodeRabbit;
  - push and open the PR, then stop rather than poll CI;
  - Graphban: evidence on MT-52, in review, lessons, and an outcomes file naming the agent.
