# Books stats, part 1: time format, reading speed, a yearly goal, books per month (#219)

**Status:** approved in brainstorming 2026-10-16; this file is the written spec.
**Branch:** `feat/219-books-stats`, from `main` at `9fbd2a5` (#91 merged).

## Why

#219 is step 2 of the stats theme (#91 first, then #219 and #217, then #221 and #220). The owner
split it into two PRs. **Part 1 (this spec)** covers:
- the time format;
- reading speed and session lengths;
- a yearly reading goal;
- books finished per month.

**Part 2 (later)** covers when you read, top authors, formats, and the did-not-finish rate under All
time.

The constraint carries over from #126 and #91: **one stats engine.** New per-period numbers are
columns on the existing single query, never a second, independent flow, so a write still produces
one consistent state.

## Decisions (the owner's, from brainstorming)

| Question | Decision |
| --- | --- |
| Split | Two PRs; this is part 1 |
| Visuals | Simple bar charts drawn by the app (no new library); everything else as text |
| Goal | Set by tapping the goal card, which opens a number dialog |
| DNF rate (part 2) | All time only |
| My call, approved | The goal is included in archive exports |

## Design

### 1. Time format

`formatTimeReadOrUnknown` shows **"22h 48m"**, with **"45m"** under an hour and **"0m"** for a known
zero. Unknown stays "—". The same formatter is used for the new session-length lines.

### 2. Speed and session lengths, on the same query (`StatsDao.observePeriodStats`)

`PeriodStatsRow` gains four columns, each a scalar subquery over the same range:

- `timedSessionCount`: sessions with a known duration. It's used for the average session length,
  which is `timeReadSeconds / timedSessionCount`.
- `longestSessionSeconds`: `MAX(durationSeconds)`, or `NULL` when none is known.
- `speedSeconds` and `speedPages`: `SUM(durationSeconds)` and `SUM(deltaPages)` over sessions where
  **both** are known and **both** are > 0 (a session that moved backwards or nowhere says nothing
  about speed; added after review). Pages per hour = `speedPages * 3600 / speedSeconds`.
  It's `null` when there is no such session, so a session with no duration can never inflate the
  speed.

`StatsUiState.Period` gains `averageSessionSeconds: Long?`, `longestSessionSeconds: Long?` and
`pagesPerHour: Int?`, all derived in the view model's mapping. The period card adds three lines:
"Speed: 38 pages/hour", "Average session: 42m" and "Longest session: 2h 5m", each "—" when unknown.

### 3. Yearly reading goal

- **Setting** (`features/settings/data/ReadingGoal.kt`): the key is `"reading_goal"`, with the value
  `"<year>:<books>"` (e.g. `2026:12`). It's read as a goal only when the stored year is the current
  local year. A goal from last year is simply not shown, so January starts fresh with nothing to
  clear. Its API is `observeReadingGoal(): Flow<ReadingGoal?>` / `setReadingGoal(year, books)` /
  `clearReadingGoal()`.
- **Export:** `"reading_goal"` joins `EXPORTABLE_SETTING_KEYS`, and `ImportArchiveUseCase.restoreSetting`
  validates the `year:books` shape (books 1..999) before restoring it.
- **Progress:**
  - **Finished this year** comes from `observePeriodStats(YEAR bounds)`, the same query and the same
    number as the Year view.
  - **Expected so far** = goal × (day of the year / days in the year).
  - **The status** is "on pace" when finished ≥ expected (rounded down), otherwise "N behind", where
    N = expected (rounded down) − finished, so "N behind" always means "N more books and you're on
    pace". It's "goal reached" when finished ≥ goal. (Corrected after review: the first draft counted
    N against the rounded-up share, which said "2 behind" one book short of on pace.)
- **UI:** a goal card above the streak card, always visible whatever the period:
  - with no goal, it shows "Set a 2026 reading goal";
  - with a goal, it shows "5 of 12 books · on pace", with a `LinearProgressIndicator`.

  Tapping it opens `ReadingGoalDialog`: a number field, Save, Cancel, and "Remove goal" when one
  exists.
- **The view model** gains a `goal: GoalProgress?` in `StatsUiState`, plus `setGoal(books)` and
  `clearGoal()`.

### 4. Books finished per month

- **Query:** `StatsDao.observeFinishedAtInRange(from, to, finished): Flow<List<Instant>>`, which
  returns the finish dates of FINISHED books within the current year's bounds. They're bucketed into
  12 local months in Kotlin with the injected `TimeZone`, the same pattern the streak uses. SQLite's
  `'localtime'` would use the device zone and ignore the injected one.
- **Where it shows:** only when the period is **Year**, as a card titled "Books finished by month"
  with 12 bars and single-letter month labels.
- **The chart** (`ui/components/MonthBarChart.kt`) is drawn with `Canvas`. Bars are scaled to the
  maximum, and zero months show a thin baseline.
  - **Accessibility:** the whole chart is one node, with a content description listing the months
    that have finishes ("June 2, August 1, …"), or "No books finished yet this year".
- **Consistency:** this is a separate flow from the period snapshot, a different shape like the
  streak. It's combined inside the same `flatMapLatest`, so it's re-derived with the same "today".

## Testing

TDD; each test red first, or falsified by a deliberate break.

- **`PeriodStatsDaoTest`** for the new columns:
  - speed ignores sessions missing either value or with a duration of 0;
  - longest is `NULL` when no duration is known;
  - the timed count leaves out sessions with no duration.
- **`observeFinishedAtInRange`:** in range only, FINISHED only.
- **`ReadingGoalTest` (jvmTest):**
  - a round trip;
  - a goal from another year reads as no goal;
  - a malformed value reads as no goal.
- **Import and export:**
  - `reading_goal` is exported;
  - a valid one is restored;
  - an invalid one is rejected and counted as not restored.
- **The pace function** (`commonTest`, pure):
  - on pace, behind, reached, and the first day of the year;
  - a leap year;
  - a goal set late in the year.
- **`StatsViewModelTest`:**
  - the derived speed, average and longest;
  - the goal flows through;
  - `setGoal` / `clearGoal`;
  - the months are bucketed in the local zone (a finish at 23:30 local on 31 May counts in May);
  - the halfway-state guard still holds with the new columns.
- **Robolectric:**
  - the period card's new lines, with unknowns shown as "—";
  - the goal card: both states and the tap opens the dialog;
  - the dialog's Remove and Cancel, which don't type;
  - the chart appears only on Year, with the content description listing months;
  - the time format.
- **Device (androidTest), per the Graphban lesson that Robolectric can't type into a dialog's text
  field:** a `ReadingGoalDialog` test types a number and saves, with a comment saying why it lives
  there.
- **Goldens:** `stats` is re-recorded, and a new `stats-year` shows the chart and the goal. All are
  viewed. The chart also has to be seen **on the phone**: a golden draws through Skia, not the
  device's renderer.
- **The keyboard check (CI "Compose IME insets"):** `StatsScreen.kt` gains a text field through the
  dialog, so the Scaffold gets `contentWindowInsets = WindowInsets.safeDrawing` (or the dialog lives in
  its own file). Either way the grep passes, and the choice is recorded in the plan.
- **The full gate; `connectedDebugAndroidTest`; a hand check on the phone.**

## Risks

- **The halfway-state guard compares whole periods.** It must include the new fields, which it does
  automatically because `Period` equality covers them.
- **A goal dialog that types** can't be tested in Robolectric. That one test lives on the device.
- **The chart's appearance on the device** has to be checked by hand.
