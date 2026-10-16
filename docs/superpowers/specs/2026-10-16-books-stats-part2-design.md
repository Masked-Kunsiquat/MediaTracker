# Books stats, part 2: when you read, top authors, formats, did-not-finish rate (#219)

Part 2 of #219. Part 1 (PR #223) added the time format, speed, session lengths, the reading goal and
books per month. This spec adds the rest of what the owner picked on the issue. All of it comes
from data the app already records, with **no schema change**.

## Decisions (owner, 2026-10-16)

| Question | Decision |
|---|---|
| What top authors and formats count | **Time read** in the chosen period |
| When you read | **Two bar charts**: four parts of the day and seven weekdays, by time read |
| Author spellings | **Loose match**: ignore case, spaces and punctuation; show the most common spelling |
| Co-authored books | Each author gets the book's **full** time (MT-A4's recommendation, accepted with the design) |
| DNF rate | **All time only** (part 1 decision; a DNF has no date) |
| Charts | Canvas, no new library (part 1 decision) |

## Constraints carried from #91 and part 1

- **One pipeline.** This is a scoped read alongside `StatsDao.observePeriodStats`, never a fork of
  `StatsRepository`.
- **Unknown is never zero.** A session with no duration is left out of every breakdown here; it is
  never counted as 0 minutes.
- **One read per breakdown family.** The three breakdowns come from **one query**, so they always
  agree with each other after a write. #91 fixed exactly that disagreement for the period card.
- **Local time.** Parts of the day and weekdays are the phone's local zone, never UTC.

## 1. The query (`StatsDao.observeTimedSessions`)

One new DAO query returns, for the period's bounds (the same `from`/`to` as `observePeriodStats`;
both null for All time):

```sql
SELECT s.timestampStart, s.durationSeconds, b.format, b.authors
  FROM reading_sessions s
  JOIN book_details b ON b.mediaId = s.mediaId
 WHERE s.durationSeconds > 0
   AND (:from IS NULL OR s.timestampStart >= :from)
   AND (:to IS NULL OR s.timestampStart < :to)
```

It returns `Flow<List<TimedSessionRow>>` with `TimedSessionRow(timestampStart: Instant,
durationSeconds: Long, format: BookFormat, authors: String?)`. A zero duration adds nothing to any
total, so `> 0` excludes it along with null.

## 2. The breakdowns (pure Kotlin, `features/stats/data/ReadingBreakdown.kt`)

`fun readingBreakdown(rows: List<TimedSessionRow>, timeZone: TimeZone, weekStart: WeekStartDay):
ReadingBreakdown` computes all three from the same rows:

- **Parts of the day:** four totals in seconds, in this order: Morning 05:00–11:59, Afternoon
  12:00–16:59, Evening 17:00–20:59, Night 21:00–04:59 (the bucket wraps midnight). A session counts
  toward the local hour its **start** falls in, even if it runs past a boundary.
- **Weekdays:** seven totals in seconds, starting on `weekStart` (Monday or Sunday), by the start's
  local day.
- **Top authors:** the five authors with the most time read, as `AuthorTime(name, seconds)`, most
  first. Ties break by name, so the order is stable.
  - Split `authors` on `BookDetailsEntity.AUTHOR_SEPARATOR` (a new `splitAuthors` next to
    `joinAuthors`, its inverse). Blank names are dropped.
  - **Grouping key:** the name lowercased, with every character that is not a letter or a digit
    removed. "J.R.R. Tolkien", "J. R. R. Tolkien" and "j r r tolkien" share the key `jrrtolkien`.
    Letters include non-Latin ones, so "Brontë" keeps its ë.
  - **Shown name:** the spelling with the most time among the group's members; ties break by the
    first in alphabetical order.
  - A co-authored session adds its **full** duration to each of its authors.
  - A book with no authors is left out of top authors (its time still counts in parts of the day,
    weekdays and formats).
- **Formats:** one `FormatTime(format, seconds)` per `BookFormat` that has time, most first, ties by
  the enum's order. The percentage is derived on screen from `seconds / total`, rounded to a whole
  number. Formats are shown as stored: Physical, Paperback and Hardcover stay separate.

The total time across the breakdowns equals the sum of `durationSeconds` over the rows. When there
are no rows, every list is empty and every total is zero, and the screen shows its empty state.

## 3. The did-not-finish rate (All time only)

`StatsDao.observeFinishOutcomes()` returns `FinishOutcomes(finished: Int, dnf: Int)`: the counts of
books with status FINISHED and DNF, whatever their dates. The rate is `dnf / (finished + dnf)`,
rounded to a whole percent. It is only read while All time is showing, the same way part 1 reads
the month chart only on Year.

## 4. The view model

`StatsUiState` gains:
- `breakdown: ReadingBreakdown`, for the chosen period;
- `finishOutcomes: FinishOutcomes?`, on All time only, `null` otherwise.

Both join the existing `flatMapLatest` restart (period, week start, local date) and read the same
single instant of "now" for their bounds. That gives two new flows on top of part 1's five, more
than the typed `combine` takes, so it nests; either way it publishes one state per change.

## 5. The screen

New cards go below the streak card, in this order:

1. **When you read:** two `BarChart`s, one for the parts of the day (4 bars) and one for the
   weekdays (7 bars, starting on the week start).
   - Part 1's `MonthBarChart` becomes a general `BarChart(values, labels, description)`, and the
     month chart is one use of it. Its behaviour, accessibility and golden stay the same.
   - TalkBack reads each chart as one line, for example "Morning: 2h 10m, Evening: 5h 30m"; slots
     with no time are left out.
2. **Top authors:** up to five rows, for example "Brandon Sanderson · 8h 20m".
3. **Formats:** one row per format with time, for example "Paperback · 12h 30m · 45%".
4. **Did not finish** (All time only): a line in the period card, "Did not finish: 2 of 14 books
   (14%)", or "Did not finish: —" when there are no finished or DNF books.

When the period has no timed reading, each of the three cards shows one line, "No timed reading in
this period", instead of empty charts or lists.

## 6. Testing

- **DAO (jvmTest):**
  - `observeTimedSessions` respects the bounds, excludes null and zero durations, and carries the
    format and authors;
  - `observeFinishOutcomes` counts FINISHED and DNF and ignores other statuses.
- **Pure (commonTest)** `ReadingBreakdownTest`:
  - the hour boundaries (04:59 is Night, 05:00 is Morning, 21:00 is Night);
  - a non-UTC zone moves a session to a different part of the day and weekday;
  - the weekday order follows `weekStart`;
  - the loose author match, with the most-time spelling shown;
  - a co-author gets the full time;
  - a book with no author is left out of authors but kept in formats;
  - the top five cutoff and the tie order;
  - the totals agree across all three breakdowns.
- **`splitAuthors`:** round-trips with `joinAuthors` and drops blanks.
- **View model (jvmTest):** the breakdown follows the period; `finishOutcomes` appears only on All
  time; the #91 guard still holds.
- **Screen (Robolectric):** each card's contents and empty state; the DNF line only on All time; the
  charts' descriptions.
- **Golden:** a new `stats-breakdown` golden showing the three new cards, populated. Existing
  goldens are re-recorded only if their frame changes, and each one is looked at before committing.
- **Device:** the full suite, then a hand check against the sample data, recomputed by hand as in
  part 1.

## Not in scope

- An authors table, or merging "Tolkien, J.R.R." with "J.R.R. Tolkien" (the owner chose the loose
  match only).
- A DNF rate per period, which needs a DNF date the schema does not have.
- Films and shows (#217), the year in review (#221) and your own rating (#220).
