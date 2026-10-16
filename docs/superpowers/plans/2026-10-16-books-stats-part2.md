# Books Stats Part 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Stats shows when you read (parts of the day and weekdays), your top authors and your format split, all by time read in the chosen period.
- Under All time, Stats also shows the did-not-finish rate.

**Architecture:**
- One new DAO query returns the period's timed sessions with each book's format and authors.
- A pure function (`readingBreakdown`) turns those rows into all three breakdowns, in the injected time zone, so they always agree with each other.
- The DNF counts are a second small query, read only on All time.
- Both join part 1's `flatMapLatest` restart (period, week start, local date) and read its single instant of "now".
- Part 1's `MonthBarChart` becomes a general `BarChart`, reused by the month chart and the two new charts.

**Tech Stack:** Kotlin, Room KMP, kotlinx-datetime, Jetpack Compose (Material 3, `Canvas`), Robolectric and Roborazzi, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-16-books-stats-part2-design.md`

## Global Constraints

- **One pipeline:** a scoped read beside `observePeriodStats`, never a fork of `StatsRepository`.
- **Unknown is never zero:** sessions with a null or zero duration are left out of every breakdown.
- **One read for the three breakdowns:** all three come from `observeTimedSessions`, never from three queries.
- **Local time:** parts of the day and weekdays use the injected `TimeZone`, never UTC.
- **Test location:** Room tests go in `jvmTest`, pure functions in `commonTest`. View model tests await `uiState.first { … }`.
- **No text field in this part.** No new dialog is needed.
- **Goldens** are recorded by hand, viewed, and committed alone (`m_c0c23ebc7c`). The new cards are also checked on the phone.
- **The CHANGELOG** gets the user-visible change under `### Added`.
- **Identity in Graphban text:** evidence notes and outcome reasons name the agent ("MT-A4 (Claude Code) …"), never a bare "I".
- **After a push:** summarise and stop; do not poll CI (`m_cd6a760075`).
- **The gate:**
  - `./gradlew ktlintFormat`
  - `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`

## Review Focus

1. **An author name made only of punctuation** ("—", "?") must not become an author with an empty name. Pinned in Task 1 (`authorKey` returns `""`) and Task 3 (empty keys are dropped).
2. **A long author name** must not push the time off the row. Pinned in Task 6: the name gets `maxLines = 1` with an ellipsis, and the golden uses a long name.
3. **A format with a sliver of time** must not read "0%". Pinned in Task 6: under 1% shows "<1%".
4. **A session at 04:59 versus 05:00, and 20:59 versus 21:00, in a non-UTC zone.** Pinned in Task 3.
5. **The DNF line with no finished or DNF books** reads "—", never "0 of 0 (NaN%)". Pinned in Task 6, with the division guarded in Task 4.

---

### Task 1: Splitting authors and the loose match key

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/entities/BookDetailsEntity.kt`: add `splitAuthors` beside `joinAuthors`.
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/AuthorKey.kt`
- Tests:
  - `shared/src/commonTest/kotlin/com/hub/media/core/database/entities/SplitAuthorsTest.kt`
  - `shared/src/commonTest/kotlin/com/hub/media/features/stats/data/AuthorKeyTest.kt`

**Interfaces:**
- Produces:
  - `public fun splitAuthors(stored: String?): List<String>`: split on `BookDetailsEntity.AUTHOR_SEPARATOR`, trimmed, blanks dropped; `null` gives an empty list.
  - `internal fun authorKey(name: String): String`: `name.lowercase()` with every character that is not `Char.isLetterOrDigit()` removed.

- [ ] **Tests first:**
  - `splitAuthors(joinAuthors(listOf("A", "B")))` gives `["A", "B"]`;
  - `splitAuthors("A; ; B")` gives `["A", "B"]`;
  - `splitAuthors(null)` gives `[]`;
  - `authorKey("J.R.R. Tolkien")`, `authorKey("J. R. R. Tolkien")` and `authorKey("j r r tolkien")` all give `"jrrtolkien"`;
  - `authorKey("Charlotte Brontë")` gives `"charlottebrontë"`;
  - `authorKey("—")` gives `""`.
- [ ] Stub both functions so the tests compile, then run `./gradlew :shared:jvmTest --tests "*SplitAuthorsTest" --tests "*AuthorKeyTest"`. Expected: FAIL on the values.
- [ ] **Implement, then run again.** Expected: PASS.
- [ ] **Commit** as `Stats: split stored authors, and a loose key to group spellings (#219)`.

### Task 2: The two queries

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/TimedSessionRow.kt`
- Create: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/FinishOutcomes.kt`
- Modify: `StatsDao.kt` (two queries) and `StatsRepository.kt` (two methods).
- Test: `shared/src/jvmTest/kotlin/com/hub/media/core/database/TimedSessionsDaoTest.kt`

**Interfaces:**
- Produces:
  - `data class TimedSessionRow(val timestampStart: Instant, val durationSeconds: Long, val format: BookFormat, val authors: String?)`
  - `data class FinishOutcomes(val finished: Int, val dnf: Int)`
  - `StatsDao.observeTimedSessions(from: Instant?, to: Instant?): Flow<List<TimedSessionRow>>`, with this SQL:
    ```sql
    SELECT s.timestampStart, s.durationSeconds, b.format, b.authors
      FROM reading_sessions s JOIN book_details b ON b.mediaId = s.mediaId
     WHERE s.durationSeconds > 0
       AND (:from IS NULL OR s.timestampStart >= :from)
       AND (:to IS NULL OR s.timestampStart < :to)
    ```
  - `StatsDao.observeFinishOutcomes(finished: ReadingStatus, dnf: ReadingStatus): Flow<FinishOutcomes>`, with this SQL:
    ```sql
    SELECT (SELECT COUNT(*) FROM book_details WHERE status = :finished) AS finished,
           (SELECT COUNT(*) FROM book_details WHERE status = :dnf) AS dnf
    ```
  - `StatsRepository.observeTimedSessions(period: StatsPeriod, timeZone: TimeZone, clock: Clock, weekStartDay: WeekStartDay): Flow<List<TimedSessionRow>>`, using `period.bounds(...)`, as `observePeriodStats` does.
  - `StatsRepository.observeFinishOutcomes(): Flow<FinishOutcomes>`

- [ ] **Tests first (jvmTest, real Room):**
  - `observeTimedSessions`:
    - keeps sessions inside `[from, to)`;
    - drops a null duration and a zero duration;
    - carries the book's `format` and `authors`;
    - returns every timed session when both bounds are null.
  - `observeFinishOutcomes`:
    - counts 2 FINISHED and 1 DNF;
    - ignores READING and TO_READ;
    - is `(0, 0)` on an empty database.
- [ ] Stub both queries so the tests compile, run them, and confirm they fail on behaviour.
- [ ] **Implement, then run `./gradlew :shared:jvmTest --tests "*TimedSessionsDaoTest"`.** Expected: PASS. Room's schema is unchanged: `git diff shared/schemas` must be empty.
- [ ] **Sabotage:** change `> 0` to `IS NOT NULL`. Expect the zero-duration test to fail, then restore.
- [ ] **Commit** as `Stats: timed sessions with format and authors, and finish outcomes (#219)`.

### Task 3: The breakdowns (pure)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/features/stats/data/ReadingBreakdown.kt`
- Test: `shared/src/commonTest/kotlin/com/hub/media/features/stats/data/ReadingBreakdownTest.kt`

**Interfaces:**
- Consumes: `TimedSessionRow`, `splitAuthors`, `authorKey`, `WeekStartDay`.
- Produces:
  ```kotlin
  public enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }
  public data class AuthorTime(val name: String, val seconds: Long)
  public data class FormatTime(val format: BookFormat, val seconds: Long)
  public data class ReadingBreakdown(
      val dayParts: List<Long> = List(4) { 0L },   // DayPart order
      val weekdays: List<DayOfWeek> = emptyList(),  // the 7 days, starting on the week start
      val weekdaySeconds: List<Long> = List(7) { 0L },
      val topAuthors: List<AuthorTime> = emptyList(),
      val formats: List<FormatTime> = emptyList(),
      val totalSeconds: Long = 0,
  )
  public fun dayPartOf(hour: Int): DayPart   // 5..11 MORNING, 12..16 AFTERNOON, 17..20 EVENING, else NIGHT
  public fun readingBreakdown(rows: List<TimedSessionRow>, timeZone: TimeZone, weekStart: WeekStartDay): ReadingBreakdown
  ```
  Top authors are capped at `TOP_AUTHORS = 5`.

- [ ] **Tests first:**
  - **Hour boundaries:** `dayPartOf(4)` is NIGHT, `5` is MORNING, `11` is MORNING, `12` is AFTERNOON, `16` is AFTERNOON, `17` is EVENING, `20` is EVENING, `21` is NIGHT and `0` is NIGHT.
  - **Time zone:** a session at 03:30 UTC on a Monday, read in `America/New_York`, counts as Sunday 23:30, so it goes to NIGHT and to **Sunday**.
  - **Weekday order:** with `WeekStartDay.SUNDAY`, `weekdays.first()` is `SUNDAY`; with `MONDAY`, it is `MONDAY`. The seconds follow the same order.
  - **Loose match:** "J.R.R. Tolkien" with 600 s and "J. R. R. Tolkien" with 1 200 s become one author named "J. R. R. Tolkien" with 1 800 s.
  - **Co-authors:** a session of `"A; B"` with 900 s gives A 900 s and B 900 s.
  - **No author:** a session with `authors = null` is not among the authors, but its time is in `formats` and `dayParts`.
  - **Punctuation-only name:** an author of `"—"` is dropped.
  - **Top five:** with six authors, five remain; ties break by name ascending.
  - **Formats:** sorted by seconds descending, ties by enum order. Only formats with time appear.
  - **Totals agree:** `dayParts.sum() == weekdaySeconds.sum() == formats.sumOf { it.seconds } == totalSeconds`.
  - **Empty:** an empty input gives `totalSeconds == 0`, all-zero `dayParts` and `weekdaySeconds`, empty `topAuthors` and `formats`, and `weekdays` still filled for the week start. Only the data class's own default, used while loading, has empty `weekdays`; the screen never reads them then, because `totalSeconds == 0` shows the empty state.
- [ ] Stub, run (`./gradlew :shared:jvmTest --tests "*ReadingBreakdownTest"`), and confirm FAIL. Implement, then confirm PASS.
- [ ] **Sabotage:** read the hour in UTC instead of the zone. Expect the time-zone test to fail, then restore.
- [ ] **Commit** as `Stats: when, who and which format, from one set of sessions (#219)`.

### Task 4: The view model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/StatsUiState.kt`, adding `breakdown: ReadingBreakdown = ReadingBreakdown()` and `finishOutcomes: FinishOutcomes? = null`, each with KDoc.
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/StatsViewModel.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/StatsViewModelTest.kt`

**Interfaces:**
- Consumes: `StatsRepository.observeTimedSessions` and `observeFinishOutcomes`, and `readingBreakdown`.
- Produces: `StatsUiState.breakdown` and `StatsUiState.finishOutcomes`, both set inside the existing `flatMapLatest` with the snapshot clock `at`.
  - `finishOutcomes` comes from `observeFinishOutcomes()` only when `period == ALL_TIME`, and is `flowOf(null)` otherwise.
  - The combine has 7 inputs, so it nests: the part 1 combine of 5, then a `combine(thatFlow, breakdownFlow, outcomesFlow)` that copies the two new fields in.
  - Also add `public fun FinishOutcomes.dnfPercent(): Int?`: `null` when `finished + dnf == 0`, otherwise `(dnf * 100.0 / (finished + dnf)).roundToInt()`. It goes in `FinishOutcomes.kt` with a commonTest.

- [ ] **Tests first:**
  - The week's breakdown follows the period: one timed session this week and one last month give Week a total of the first session only, and Month a total of both.
  - `finishOutcomes` is `null` on Week and `FinishOutcomes(1, 1)` on All time.
  - `aWrite_neverPublishesAHalfwayState` still passes.
  - **commonTest:** `dnfPercent` returns `null` for `(0, 0)`, 33 for `(2, 1)` and 100 for `(0, 3)`.
- [ ] Red, implement, green: `./gradlew :shared:jvmTest`, the whole suite.
- [ ] **Sabotage:** give `finishOutcomes` on every period. Expect the Week test to fail, then restore.
- [ ] **Commit** as `Stats: the breakdown and the finish outcomes, in the view model (#219)`.

### Task 5: A general bar chart

**Files:**
- Rename: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/MonthBarChart.kt` to `BarChart.kt`.
- Modify: `StatsScreen.kt` (the month chart's call site).

**Interfaces:**
- Produces: `fun BarChart(values: List<Long>, labels: List<String>, description: String, modifier: Modifier = Modifier)`. It has the same drawing as before: a baseline for 0, the tallest bar full height, and one semantics node carrying `description`.
  - `values.size == labels.size` is required.
  - `MonthChart(counts: List<Int>, modifier)` stays in `BarChart.kt` as a thin wrapper that builds the month labels and the existing description, then calls `BarChart`.

- [ ] **This is a refactor, and the existing tests are its guard.** Run `./gradlew :app:testDebugUnitTest --tests "*StatsScreenTest" :app:verifyRoborazziDebug` before and after the change. Both must pass unchanged. The `stats-year` golden must not change: if `verifyRoborazziDebug` reports a diff, the refactor changed the drawing, so fix the code, never the golden.
- [ ] **Commit** as `Stats: the month chart becomes a general bar chart (#219)`.

### Task 6: The screen

**Files:**
- Modify: `StatsScreen.kt`, adding:
  - `WhenYouReadCard(breakdown)`, with two `BarChart`s:
    - day-part labels from strings (`stats_daypart_morning` and the others);
    - weekday labels from `DayOfWeek.getDisplayName(TextStyle.SHORT, locale)`, in `breakdown.weekdays` order.
  - `TopAuthorsCard(breakdown.topAuthors)`: each row is the name (`maxLines = 1`, `TextOverflow.Ellipsis`, `weight(1f)`) followed by `formatDuration(seconds)`.
  - `FormatsCard(breakdown.formats, breakdown.totalSeconds)`: each row is the format label (reusing the existing `book_format_*` strings), `formatDuration(seconds)`, then the percent. It shows "<1%" when `seconds > 0` and the rounded value is 0.
  - **Empty state:** when `breakdown.totalSeconds == 0`, each of the three cards shows `stats_no_timed_reading` ("No timed reading in this period") instead of its contents.
  - **The DNF line** in `PeriodCard` when `finishOutcomes != null`: `stats_dnf_label` ("Did not finish: %1$s"), with the value from the `stats_dnf_value` plural ("%1$d of %2$d book(s) (%3$d%%)"), or "—" when `dnfPercent()` is null.
- **Chart descriptions:** each chart's description lists its non-zero slots as "Morning: 2h 10m" or "Tue: 1h 5m", joined by ", ". When everything is zero, the card's empty state already applies.
- Strings and TestTags: `Stats.WHEN_CARD`, `Stats.AUTHORS_CARD`, `Stats.FORMATS_CARD`, `Stats.DAYPART_CHART` and `Stats.WEEKDAY_CHART`.
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/StatsScreenTest.kt`

- [ ] **Robolectric tests first:**
  - the three cards show their contents for a populated breakdown (scroll to each with `performScrollToNode`);
  - the empty state appears on all three when `totalSeconds == 0`, and the charts are absent;
  - a long author name and its time both exist as nodes;
  - "<1%" for a format with 10 s out of 10 000 s;
  - the DNF line appears on All time with "2 of 14 books (14%)", reads "—" for `(0, 0)`, and is absent on Week;
  - the part-of-day chart's description names Morning and not Afternoon when only Morning has time.
- [ ] Red, implement, green: `./gradlew :app:testDebugUnitTest --tests "*StatsScreenTest"`.
- [ ] **Sabotage:** drop the `<1%` branch. Expect that test to fail, then restore.
- [ ] Confirm the IME grep still passes: `StatsScreen.kt` still holds no text field.
- [ ] **Commit** as `Stats screen: when you read, top authors, formats, did not finish (#219)`.

### Task 7: Goldens, docs, the gate and the device

- [ ] **Golden:** add `stats-breakdown` to `StatsScreenGoldenTest`. The three cards sit below the fold of the full screen, so make `WhenYouReadCard`, `TopAuthorsCard` and `FormatsCard` `internal` and render them in a fixture `Column` inside `MediaTrackerTheme(dynamicColor = false)`. Show them populated, with a long author name and a "<1%" format. Pair it with `alsoAssert = { assertTextIsShown("When you read", "Top authors", "Formats") }`. Record it, look at it, and commit the test code first and the PNG alone.
- [ ] **CHANGELOG**, under `[Unreleased]` → `### Added`: "Stats shows when you read (time of day and weekday), your top authors and the formats you read, by time read in the chosen period. Under All time it also shows how many books you didn't finish. (#219)"
- [ ] **The full gate.**
- [ ] **Device:** run the full `connectedDebugAndroidTest`, then `seedDebugDevice` (watch for Play Protect; it is the owner's decision). Then a hand check against `docs/sample-data`, recomputed with a script as in part 1:
  - the part-of-day and weekday totals in the phone's zone;
  - the top authors;
  - the format percentages;
  - the DNF line on All time.
- [ ] **Then:**
  - the Opus whole-branch review, with findings re-graded by effect and fixed test-first;
  - local CodeRabbit;
  - push and open the PR, then **stop rather than poll CI**;
  - Graphban: evidence on MT-50, in review, lessons, and an outcomes file naming the agent.
