# Year in review (#221)

A "Wrapped"-style story of a year across books, films and shows. Graphban MT-52. It reuses the stats
engine over a chosen year: the queries #91, #219 and #217 built, plus one new read. **No schema
change.**

## Decisions (owner, 2026-10-17)

| Question | Decision |
|---|---|
| Format | **Story pages**: full-screen, swipe or tap through, one number or highlight per page |
| Years | **Any year, any time**: opens on this year so far; a picker reaches every year back to the first logged activity |
| Pages | Totals and a summary always; the issue's highlights; your rhythm; reading goal; longest session |
| Entry | A card on Stats' Year view (every tab) and a row on the hub |

The owner will see renders of two or three pages before the plan is written, as with the clock and
the tiles.

## Constraints carried from #91, #219 and #217

- **One engine:** a year is a period on the existing queries, never a fork of `StatsRepository`.
- **Unknown is never zero:** time watched counts only known runtimes, and says so when some are
  missing.
- **Local time:** the year's bounds, days, months and hours are all in the injected zone.
- **Picked dates** (exactly 12:00:00.000 local) count by day and month, never on the clock
  (`isPickedDate`).
- **`:app` sees no kotlinx-datetime types:** months, days and hours cross as numbers.

## 0. Reading goals for every year (owner, 2026-10-17)

The app stored one goal, `"2026:12"`, so setting next year's goal erased this year's, and the review
could not show a past year's goal. The owner asked for the storage itself to be fixed, inside this
work. No schema change:

- **Format:** the `reading_goal` setting holds every year's goal, comma-separated in year order:
  `"2025:20,2026:12"`. A single entry, which is every value stored today, is a one-year list, so
  nothing stored needs migrating.
- **API:**
  - `observeReadingGoals(): Flow<Map<Int, Int>>` (empty when none, or when the value is malformed);
  - `observeReadingGoal(year): Flow<ReadingGoal?>`;
  - `setReadingGoal(year, books)` adds or replaces that year's entry only;
  - `clearReadingGoal(year)` removes that year's entry only.
- **Parsing:** `String.toReadingGoalsOrNull(): Map<Int, Int>?` is all-or-nothing. Every entry must be
  `year:books` with books in 1..999, and no year may appear twice; anything else reads as no goals,
  as a malformed single value does today.
- **Stats** reads this year's entry, so the goal card is unchanged. A new year still starts with no
  goal, and the earlier year's goal is now kept.
- **Import:** it validates with the same parser and restores the whole list. It stays in
  `EXPORTABLE_SETTING_KEYS`, so every year's goal travels with an archive.
- **The limit:** goals overwritten before this change are gone, so the review's goal page appears
  from this change on.

## 1. Years

- `yearBounds(year, timeZone)`: the local `[1 Jan year, 1 Jan year+1)`.
- `StatsDao.observeFirstActivity(): Flow<Instant?>`: the earliest reading session start, watch-log
  time or finish date. The picker offers every year from that one to the current year, newest first.
  With no activity at all, it offers only this year, whose pages are then all skipped except the
  empty summary.
- The current year is labelled "2026 so far"; past years just "2025".

## 2. Reads for a year (all existing, bounded by `yearBounds`)

| Read | Gives |
|---|---|
| `observePeriodStats` | books finished, time read, longest session (seconds) |
| `observeTimedSessions` | top author (via `readingBreakdown`), the reading hours, and the **longest session's book** |
| `observeViewings(MOVIE)` | films watched, film time, most-rewatched film |
| `observeViewings(TV_SHOW)` | episodes watched, episode time, biggest binge day |
| `observeFinishedBooks` (**new**) | the longest book finished |
| `observeReadingGoal(year)` | that year's goal (§0) |

- `TimedSessionRow` gains `mediaId` and `title` (it already joins `book_details`; this joins
  `media_items` too), so the longest session can name its book. `readingBreakdown` ignores the new
  fields.
- **New:** `StatsDao.observeFinishedBooks(from, to)` returns books with status FINISHED and
  `finishedAt` in the year, with their title, `totalPages` and authors.

## 3. The review (pure, `features/stats/data/YearReview.kt`)

`yearReview(year, timeZone, periodStats, sessions, films, episodes, finishedBooks, goal)` builds a
`YearReview` with each page's data, or `null` where the page has nothing to show:

1. **Totals:** books finished, films watched, episodes watched, and time spent = time read plus known
   time watched. `unknownRuntime` counts viewings left out. It is always present; zeros are shown as
   a quiet year.
2. **Longest book:** the finished book with the most pages; ties go to the earliest finished. `null`
   when none has a page count.
3. **Top author:** the first of `readingBreakdown(...).topAuthors`, with its time.
4. **Longest session:** the timed session with the longest duration, with its book's title; ties go to
   the earliest.
5. **Reading goal:** that year's goal from §0, when one was set. The page shows finished against
   the goal, and whether it was reached.
6. **Most-rewatched film:** the film with the most viewings in the year, **only when that is two or
   more**; ties go to the title.
7. **Biggest binge day:** the local day with the most episode viewings (picked dates count, since
   their day is real), the count, and the show with the most episodes that day; ties go to the
   earliest day. **Only when that is two or more**: one episode is not a binge.
8. **Your rhythm:**
   - the busiest month by combined time (reading time plus known watching time per local month);
   - the 24-hour clock of reading and timed viewings together (reading by seconds and viewings by
     count don't mix, so it is **the reading clock** when there is reading time, else the watching
     clock);
   - a line naming the busiest part of the day ("You're an evening reader" or "an evening watcher").
9. **Summary:** the totals again, and one line per page that appeared.

## 4. The screen (`ui/screens/YearReviewScreen.kt`)

- **Navigation:** `Route.YearReview` (`year_review?year=`), and a `YearReviewViewModel(year)` built
  from the reads above against the chosen year.
- **Paging:**
  - a `HorizontalPager` of the present pages, from Compose foundation (no new library);
  - a tap on the right third goes forward and a tap on the left third goes back;
  - progress dots at the top, and ✕ to close;
  - TalkBack users get next and previous buttons with labels, since tapping halves of the screen is
    not discoverable.
- **The first page** carries the year picker (the years from §1), which rebuilds the story for the
  picked year.
- **Look:** each page is a full-screen colour from the theme (rotating through primary, secondary and
  tertiary containers), with one display-size number or name and a supporting line. The rhythm page
  reuses `ClockDial` at full size.
- **Entry points:**
  - Stats' Year view, on every tab: a card "Your 2026 so far", or "Your 2025 in review" for a past
    year;
  - a hub row "Year in review".

## 5. Testing

- **DAO (jvmTest):**
  - `observeFinishedBooks` is bounded and FINISHED-only;
  - `observeFirstActivity` takes the earliest of the three sources, and is null when empty;
  - `TimedSessionRow` carries the title.
- **Pure (commonTest)** `YearReviewTest`, one test per page rule:
  - longest-book ties;
  - the goal for another year is absent;
  - a most-rewatched film needs two viewings;
  - binge day needs two episodes, in the local day across midnight, and names the show;
  - the busiest month by combined time;
  - the rhythm clock source;
  - the quiet year.
- **View model:** the year rebuilds on pick, and a past year's bounds are used.
- **Screen (Robolectric):**
  - absent pages are skipped;
  - next, previous and ✕ work;
  - the TalkBack buttons exist;
  - the picker lists the years.
- **Goldens:** a totals page, a highlight page and the rhythm page.
- **Device:** the full suite, then a hand check of 2026 against `docs/sample-data`, recomputed by
  script in the phone's zone.

## Not in scope

- Ratings ("best of the year"); those come with #220.
- Sharing or exporting an image.
- Animations beyond the pager's own.
