# Films and Shows stats (#217)

Stats has been books-only. This adds a Films tab and a Shows tab to the same Stats screen, on the
same engine and the same period picker. Graphban MT-49. **No schema change.**

## Decisions (owner, 2026-10-17)

| Question | Decision |
|---|---|
| Layout | **One Stats screen with Books, Films and Shows tabs**; no "All" tab (the cross-domain view was passed on) |
| Entry | Each library's stats icon opens its own tab; the hub's Stats row and All media open the **last-used** tab |
| Films numbers | Films watched (with rewatches), time watched, films per month on Year, when you watch |
| Shows numbers | Episodes watched (across N shows), time watched, episodes per month on Year, when you watch, top shows, shows finished (All time only) |
| Time of day | **Exact-noon marker**: a viewing at exactly 12:00:00.000 local is a picked date, so it counts by weekday but not by part of the day |

The owner noted that today's data is seed data and there are no users yet. Later, the owner plans
imports from several streaming services; see "Date-only imports".

## Constraints carried from #91, #126 and #219

- **One engine.** A domain is a filter on it, never a fork of `StatsRepository` (#126).
- **Unknown is never zero.** A viewing whose film or episode has no runtime is left out of "time
  watched" and counted as "without a runtime". It never adds 0 minutes.
- **One read per tab.** Each tab's numbers come from one query plus pure Kotlin, so a write can't
  show numbers on one tab that disagree with each other (#91).
- **Local time.** Weekdays, parts of the day and months are bucketed in the injected zone.
- **The Books tab is unchanged.**

## 1. What the data says (read from the code)

- **Every viewing is a `watch_logs` row:** a film's has `episodeId = null`, an episode's has the
  episode. Rewatches are separate rows. Each has a real `watchedAt`.
- **`watch_logs.durationSeconds` is never written**, so time watched comes from the runtime of the
  film (`movie_details.runtimeMinutes`) or episode (`episodes.runtimeMinutes`) of each viewing.
- **A picked date is stored as noon local** (`WatchedDatePickerDialog`, `TVShowDetailViewModel`'s
  date correction): its day is real and its time is not.
- **A show has no "finished on" date.** "Finished" is derived from its episodes by
  `LibraryStatusFilter.ofShow`, so "shows finished" can only be an All time count.

## 2. The queries

**`StatsDao.observeViewings(from, to, type): Flow<List<ViewingRow>>`.** It returns one row per
viewing in `[from, to)` (both null for All time) of media items of `type` (MOVIE or TV_SHOW):

```sql
SELECT w.watchedAt, w.mediaId, w.episodeId, m.title,
       COALESCE(e.runtimeMinutes, md.runtimeMinutes) AS runtimeMinutes,
       EXISTS (SELECT 1 FROM watch_logs p
                WHERE p.mediaId = w.mediaId AND p.episodeId IS w.episodeId
                  AND (p.watchedAt < w.watchedAt OR (p.watchedAt = w.watchedAt AND p.id < w.id)))
         AS isRewatch
  FROM watch_logs w
  JOIN media_items m ON m.id = w.mediaId
  LEFT JOIN movie_details md ON md.mediaId = w.mediaId AND w.episodeId IS NULL
  LEFT JOIN episodes e ON e.id = w.episodeId
 WHERE m.type = :type
   AND (:from IS NULL OR w.watchedAt >= :from)
   AND (:to IS NULL OR w.watchedAt < :to)
```

`isRewatch` means an earlier viewing of the **same film or the same episode** exists at any time,
including before the period. Ties on the same instant break by id, so exactly one of two
same-instant viewings counts as the first.

**Shows finished (All time).** The same inputs the library uses: `tv_details` (`status`,
`airingStatus`), `EpisodeDao.observeProgress()` through `TVProgressCounts.counted(includeSpecials)`,
and `observeIncludeSpecials()`. Each show goes through `LibraryStatusFilter.ofShow`, and the count is
those that come out `FINISHED`. So Stats and the library can never disagree about whether a show is
done, including under the "include specials" setting. It is read only while All time is showing on
the Shows tab.

## 3. The numbers (pure Kotlin, `features/stats/data/ViewingBreakdown.kt`)

`viewingBreakdown(rows, timeZone, weekStart, monthsOf: Int?): ViewingBreakdown` computes:

- `viewings`: the number of rows; `rewatches`: rows with `isRewatch`; `titles`: distinct `mediaId`.
- `knownSeconds`: the sum of `runtimeMinutes * 60` over rows with a runtime, or `null` when no row
  has one. `unknownRuntime`: the number of rows without one.
- `weekdays` (ISO day numbers, from the week start, as in Books) and `weekdayCounts`: viewings per
  day, not seconds. The weekday chart is "how often", because a runtime may be unknown and a viewing still
  happened.
- `dayParts`: counts per part of the day (same hours as Books), **leaving out viewings at exactly
  12:00:00.000 local**. `dayPartsSkipped` is the number of those.
- `months`: twelve counts for the year when `monthsOf` is set (the Year view), else `null`.
- `topTitles`: the five titles with the most known time, most first, ties by title. Shows only; a
  show with no known runtime at all is left out.

The weekday and day-part charts count viewings rather than time, for the reason above.

## 4. The view model

- A new remembered setting, `stats_domain` (`BOOK`, `MOVIE` or `TV_SHOW`, default `BOOK`). It is a
  view preference like `stats_period`, so it is not exported.
- `StatsUiState` gains `domain: MediaType` and `viewing: ViewingStats?`, where `ViewingStats`
  carries the breakdown and `showsFinished: Int?` (Shows on All time only). It is `null` on Books.
- The domain joins the existing `flatMapLatest` restart (period, week start, local date). On Books
  the pipeline is exactly today's; on Films or Shows it is the one viewings query, and on Shows and
  All time also the finished count, all against the same snapshot of "now".
- `selectDomain(MediaType)` remembers the tab.

## 5. Navigation

- `Route.Stats` takes an optional domain argument (`stats?domain=MOVIE`).
- A library with a domain passes it; the library's stats icon now shows for Films and Shows as well
  as Books. On arrival, the domain argument is selected (and so remembered).
- The hub's Stats row and the All media library pass none, so the remembered tab opens.

## 6. The screen

- **Tabs:** a Books | Films | Shows row above the period chips, one choice (`selectableGroup`).
- **Films tab:**
  - the period card: "Films watched: 9 (2 rewatches)" (just "Films watched: 9" with no rewatches);
  - "Time watched: 14h 20m", with " · 1 without a runtime" appended when some are unknown, or "—"
    when none is known;
  - on Year, "Films watched by month";
  - "When you watch": the weekday chart, and the parts-of-the-day chart when at least one viewing
    has a real time; a line "N logged without a time" when some were skipped;
  - empty state: "Nothing watched in this period".
- **Shows tab:**
  - the period card: "Episodes watched: 34 across 4 shows", and "Time watched" as for Films;
  - on All time, "Shows finished: N";
  - on Year, "Episodes watched by month";
  - "When you watch" as for Films;
  - "Top shows", up to five rows, "Title · 6h 10m";
  - the same empty state.
- The goal, streak, authors and formats cards stay on the Books tab.

## 6a. The time-of-day clock (owner, 2026-10-17)

The parts-of-the-day **bars are replaced by a 24-hour heat dial**, a shared `ClockDial` component
drawn on `Canvas` with the theme's colours and no library. It is used on all three tabs, so Books'
"When you read" changes too; Books' numbers do not.

- **The ring:** 24 hour wedges, midnight at the top, clockwise. Each wedge is filled with
  `colorScheme.primary` at an alpha scaled to that hour's value over the busiest hour's (a floor so
  the lightest real hour is still visible). An empty hour is drawn as a faint outline in
  `outlineVariant`, so the ring is always a whole clock.
- **Quarter labels** follow the phone's clock setting: "12a 6a 12p 6p" in 12-hour mode, "00 06 12 18"
  in 24-hour mode.
- **The centre** names the busiest part of the day ("Evenings"), from the same four parts as before.
- **The legend** below the dial has one line per part with its value: time for Books ("Evening ·
  5h 30m"), viewings for Films and Shows ("Evening · 12 viewings"). It gives the numbers that
  shading alone cannot, and it is what TalkBack reads; the dial itself is one node, described the way
  the bar charts are.
- **Data:** `ReadingBreakdown` gains `hours: List<Long>` (24 slots, seconds) beside `dayParts`, and
  `ViewingBreakdown` gains `hours: List<Int>` (24 slots, viewings, exact-noon viewings left out).
  `dayParts` stays, as the sum of its hours, for the centre and the legend.
- **Golden:** `clock-dial`, with a busy evening and an empty early morning; the existing
  `stats-breakdown` golden is re-recorded.

## 7. Date-only imports (for later)

A future import whose source records only a date (likely for some streaming services) must store
**noon local** for it, the same as "Pick a date". That way it counts by weekday and month but never
pretends to know a time of day. This is recorded here and in `ViewingBreakdown.kt`'s KDoc, so the
import work finds it.

## 8. Testing

- **DAO (jvmTest):**
  - `observeViewings` filters by type and bounds;
  - it takes the runtime from the film for a film and from the episode for an episode, and leaves
    it null when unknown;
  - `isRewatch`: true for a second viewing, including when the first viewing is before the period,
    and exactly one of two same-instant viewings.
- **Pure (commonTest)** `ViewingBreakdownTest`:
  - the noon exclusion: 12:00:00.000 skipped, 12:00:00.001 kept, in a non-UTC zone;
  - unknown runtimes counted and not summed; `null` when none known;
  - rewatch and distinct-title counts;
  - weekdays follow the week start;
  - the top five and their tie order.
- **Shows finished:** a test that it agrees with `LibraryStatusFilter.ofShow`, covering a continuing
  fully-watched show (not finished), an ended one (finished), and a show whose only unwatched
  episodes are specials, with the setting on and off.
- **View model:** the tab follows `selectDomain` and is remembered; Books is unchanged (the existing
  tests pass untouched); `showsFinished` appears only for Shows on All time.
- **Navigation and screen:**
  - a route test that the Films library's stats icon opens the Films tab;
  - Robolectric tests for each tab's cards and empty state.
- **Goldens:** `stats-films` and `stats-shows`.
- **Device:** the full suite, then a hand check against `docs/sample-data`'s watch logs and episodes,
  recomputed by script in the phone's zone.

## Not in scope

- An "All" tab and cross-domain totals.
- Picking a time when logging a viewing.
- A goal or streak for films and shows.
- A shows-finished count per period (no date exists).
