# Stats foundation: one consistent snapshot per period, and a period picker (#91)

**Status:** approved in brainstorming 2026-10-16; this file is the written spec.
**Branch:** `feat/91-stats-foundation`, from `main` at `fb69525` (v0.27.0).

## Why

This is step 1 of the **stats theme** the owner set on 2026-10-16:
1. #91, this spec;
2. #219 Books upgrade and #217 Films and Shows;
3. #221 Year in review;
4. #220 your own rating.

The rule the whole theme rests on (#126): **one stats engine**, where a domain view is a filter on it and global is the unfiltered view, with no fork per domain.

The engine as it stands can't carry that. `StatsViewModel.periodFlow` combines **four independent Room flows** per period, so a single write publishes every halfway permutation (#91 observed `sessionCount = 1, pagesRead = null`) before it settles. Adding domain and global views to that shape would multiply the problem. This step makes each period one consistent snapshot, and gives Stats the period picker that every later view will share. **No new numbers** are added in this step.

## Decisions (the owner's, from brainstorming)

| Question | Decision |
| --- | --- |
| Periods | Week, Month, 30 days, Year, All time |
| Layout | One period at a time: a picker at the top, one card for the chosen period, and the streak card below |
| Remember the choice | Yes, persisted. The first use opens on Week |
| My calls, approved | The picker is a scrollable chip row. The lifetime-books card folds into All time. A finished book with no finish date counts only in All time |

## Design

### Periods (`shared`, `features/stats`)

`enum class StatsPeriod { WEEK, MONTH, LAST_30_DAYS, YEAR, ALL_TIME }`, with a pure function that returns the period's bounds: `bounds(period, timeZone, clock, weekStartDay): Pair<Instant, Instant>?`.

- **WEEK:** the existing `StatsRepository.thisWeekBounds` (the week-start setting still applies).
- **MONTH:** the existing `thisMonthBounds`.
- **LAST_30_DAYS:** from the start of the local day 29 days ago to the start of tomorrow, so today counts as one of the 30 days.
- **YEAR:** 1 January of this year to 1 January of next year, in the local time zone.
- **ALL_TIME:** `null`, meaning unbounded.

Every bound is half-open, `[from, to)`, as the existing queries are.

### One query per period (`StatsDao`)

`observePeriodStats(from: Instant?, to: Instant?, finished: ReadingStatus): Flow<PeriodStatsRow>`. It's one `SELECT` of four correlated scalar subqueries, so Room observes both tables and every write produces **one** emission with all four numbers already consistent:

- `timeReadSeconds`: `SUM(durationSeconds)` over sessions with a known duration in range. It is **`NULL` when no such session exists**, never 0 (`StatsRepository`'s rule, preserved exactly).
- `sessionCount`: `COUNT(*)` of sessions in range, including those with no duration.
- `pagesRead`: `SUM(deltaPages)` over sessions with known pages in range, again `NULL` rather than 0 when there are none.
- `booksFinished`: `COUNT(*)` of `book_details` with `status = FINISHED` and `finishedAt` in range.

Each range test is `(:from IS NULL OR x >= :from) AND (:to IS NULL OR x < :to)`. With both bounds null (All time), the count matches today's lifetime query, so **a finished book with no `finishedAt` counts in All time** and in no dated period.

`PeriodStatsRow` is a small projection class. `StatsRepository.observePeriodStats(period, …)` maps it to `StatsUiState.Period`. The old four per-field DAO and repository methods are removed once nothing reads them; there is no deprecation period inside one module.

The domain and global filters (#219, #217) attach to this query later, as parameters or sibling queries over the same projection. They are not designed here; this step only keeps that path open.

### Settings (`features/settings/data/StatsPeriodSetting.kt`)

The setting is `observeStatsPeriod()` / `setStatsPeriod()`, stored under the `app_settings` key `"stats_period"`. An absent or unknown value means WEEK.

It is **not** added to `EXPORTABLE_SETTING_KEYS`. It's a view preference (which tab was open), not configuration someone would want restored from a backup.

### View model (`StatsViewModel`)

- **`StatsUiState`** becomes `isLoading`, `period: StatsPeriod`, `stats: Period` (the four numbers) and `currentStreakDays`. `week`, `month` and `lifetimeBooksFinished` go away.
- **The flow:** `observeStatsPeriod()` and `observeWeekStartDay()` feed `flatMapLatest` into `observePeriodStats(bounds)`, combined with the streak flow.
- **`selectPeriod(period)`** writes the setting, and the screen follows it reactively.
- **The streak** stays its own flow. It's a different shape (distinct days, not a sum), and a sub-frame lag between the streak card and the period card is not the internal inconsistency #91 is about.

### Screen (`StatsScreen`)

- **The top bar** is unchanged.
- **Under it, a horizontally scrollable `FilterChip` row:** Week, Month, 30 days, Year, All time. The selected chip is checked, in the same style as the library's status filter.
- **One card titled for the period:** "This week", "This month", "Last 30 days", "This year", "All time". It shows time read, sessions, pages and books finished, with the existing formatting and "unknown" handling.
- **The streak card** stays below. The lifetime card is removed.

## Testing

TDD; each test red first.

- **`StatsDaoTest` / `StatsRepositoryTest` (`jvmTest`)** for the new query:
  - each period's bounds, including the 30-day edge (day 29 is in, day 30 is out) and the turn of the year;
  - unknown versus zero for time and pages;
  - a finished book with no `finishedAt`: in All time, not in Year;
  - a single insert produces **one** new emission whose four fields all moved together. This is the #91 regression, asserted on the whole row and never on a single field.
- **`StatsViewModelTest`:**
  - the persisted period drives the snapshot;
  - `selectPeriod` switches it;
  - an absent or unknown setting means Week;
  - the week-start setting still moves the Week bounds.
- **`StatsPeriodBoundsTest` (`commonTest`):** the pure bounds function, for every period, with a fixed clock.
- **Robolectric (`app/src/test`):**
  - the chip row selects and calls back;
  - the card title follows the period;
  - an unknown value shows "unknown";
  - the lifetime card is gone, checked by its string, with the streak card's presence as the positive control.
- **Goldens:** `stats` is re-recorded and looked at, and committed alone.
- **Device:** the full `connectedDebugAndroidTest`, then `seedDebugDevice`, then a hand check: each period against the seeded sessions, and the choice surviving a restart.

## Docs

ROADMAP Task 11 gets a short entry recording the stats theme's order and that #91 is its foundation.

## Risks

- **`StatsScreenGoldenTest` and any test reading `uiState.week` or `uiState.month`** change shape; they're updated in the same task.
- **Year bounds and the 30-day window** must use the local time zone, as Week and Month do, or a session at 23:30 lands in the wrong period.
