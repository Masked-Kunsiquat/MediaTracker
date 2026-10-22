# Rating stats: top rated, average and spread, best of the year (#232)

Part 3 of #220, after Part 1 (#230, book read-throughs, PR #233) and Part 2 (#231, watch-throughs and
film viewing ratings, PR #235). Read-only: it adds no columns and no schema version. Every rating it
reads is already stored.

## 0. Decisions (owner, 2026-10-22)

- **Period rule: only that period's ratings.** A rating counts in a period when the thing it is on
  is dated inside the period. A title's **score** for a period is the average of its rated reads,
  film viewings or watch-throughs dated in that period. All time uses every rating, dated or not.
  The same rule answers "best of the year", so a 2023 rating never decides 2025's best book.
- **Ties: most recent first.** Equal scores rank by the title's latest rated item in the period,
  newest first, then by title A to Z.
- **Stats tabs: one "Your ratings" card**, below the charts, on Books, Films and Shows:
  - the average and how many titles are rated;
  - the top 5;
  - the spread as a chart tile in the existing style, 10 bars (one per half star), enlarged on tap.
  The card is hidden when nothing in the period is rated.
- **Year in review: one page, "Your favourites of <year>"**, placed before the summary:
  - the top-rated book, film and show of that year, each with its stars;
  - a medium with nothing rated that year is left off;
  - the page is skipped when nothing was rated that year, as other year pages are.

## 1. When a rating is dated

A rating has no date of its own. It takes the date of the thing it is on:

| Rated thing | Its date |
| :--- | :--- |
| Book read (`read_throughs`) | `finishedAt`; else the end of its latest session (`MAX(reading_sessions.timestampEnd)` for that `readThroughId`); else `startedAt` |
| Film viewing (`watch_logs`, `episodeId IS NULL`) | `watchedAt` |
| Watch-through (`watch_throughs`) | its latest episode viewing (`MAX(watch_logs.watchedAt)` for that `watchThroughId`) |

- **Why a read falls back.** `resolveFinishedAt` clears `finishedAt` for every status but Finished,
  so a **Did not finish** read has none, and it is one of the two endings that ask for a rating. Its
  last session is when you stopped. `startedAt` covers a DNF read with no sessions.
- **No date at all** (a read with no finish, sessions or start; a watch-through abandoned before any
  episode): the rating counts under **All time only**.
- **Period bounds** are `StatsPeriod.bounds` as the rest of Stats uses them: `[from, to)`, in the
  device time zone, with the week start setting. The year in review uses `yearBounds`.

## 2. Scores, ranking, average and spread

All ratings are stored in halves (`1..10`, 2 = one star), as Parts 1 and 2 store them.

- **Score** = the mean of the title's rated items in the period, in halves. Arrival rated 10 and 6 in
  one year scores 8, which displays as 4.0.
- **Top rated** = titles by score, highest first. Ties: the latest dated item in the period, newest
  first; a dateless one (All time only) ranks after any dated one; then title A to Z, case-insensitive.
  The card shows the first 5; the year page takes the first of each medium.
- **Average** = the mean of the period's title scores, each title once, so a film watched five times
  counts once. Shown to one decimal ("4.1"), in the viewer's locale, as `YourRatingRow` formats it.
- **Rated count** = how many titles have a score in the period.
- **Spread** = 10 counts, index 0 = ½ star … index 9 = 5 stars, counting titles: each in the bucket
  of its score rounded to the nearest half (`roundToInt(score)` in halves, clamped `1..10`).
  Kotlin's `roundToInt` rounds .5 up, so 4.25 stars (8.5 halves) goes in the 4½ bucket, matching
  the stars its row shows.
- **Row display.** Each top-5 row shows the title, its stars (the score rounded to a half, as the
  spread rounds it) and, **only when the score is not a whole or half star**, the exact score: "4.3".
  So the stars never silently disagree with the number. This settles #230's deferred "3.25 shows
  as 3½ beside 3.3" for this card only. The detail header is unchanged.
- **Spread caption:** "Mostly 4½", the fullest bar; a tie picks the higher score.

## 3. Architecture

**One engine (#126).** Books, films and shows are filters on the same code, never a fork of it.

### Data layer (`shared`)

- **`StatsDao.observeRatedItems(from: Instant?, to: Instant?, type: MediaType)`** returns
  `List<RatedItemRow(mediaId, title, rating: Int, at: Instant?)>`. It is one `UNION ALL` over the
  three sources in §1, filtered to the type, each with its date expression:
  - with `from`/`to` both null (All time) it returns every rated item, dated or not;
  - with bounds it returns only items whose date lies in `[from, to)`.
  `type` picks the branch rather than three DAO methods, so a domain is a filter.
- **`StatsRepository.observeRatedItems(period, timeZone, clock, weekStartDay, type)`** and
  **`observeRatedItemsIn(bounds, type)`**, shaped like `observeViewings` / `observeViewingsIn`.
- **`features/stats/data/RatingStats.kt`** (pure, `commonMain`):
  - `RatedTitle(mediaId, title, halves: Double, latestAt: Instant?, rated: Int)`;
  - `RatingStats(average: Double, titles: Int, top: List<RatedTitle>, spread: List<Int>)`;
  - `ratingStats(rows: List<RatedItemRow>, top: Int = 5): RatingStats?`, which is `null` when
    `rows` is empty. Every rule in §2 lives here, testable without a database.

### View models

- **`StatsViewModel`** adds `ratings: RatingStats?` to each domain's Ready state, from
  `observeRatedItems` for the domain and period on screen, combined with the existing flows so the
  card arrives in the same snapshot (#91).
- **`YearReviewViewModel`** adds `favourites: Favourites?` to `YearReview`:
  `Favourites(book: RatedTitle?, film: RatedTitle?, show: RatedTitle?)`, each from
  `ratingStats(...)?.top?.firstOrNull()` over `observeRatedItemsIn(yearBounds, type)`, and `null`
  when all three are. A quiet year (`quiet = true`) shows no favourites page, as it shows no other
  highlight.

### Screens (`app`)

- **`StatsRatingsCard.kt`** (new): `RatingsCard(stats: RatingStats, ratedNoun: @PluralsRes Int)`,
  one composable for all three tabs. The nouns are "books rated", "films rated" and "shows rated".
  The spread tile reuses `ChartTile` and `BarChart` from `StatsChartsCard.kt` and the same enlarge
  dialog, with its own `TestTags.Stats.RATING_SPREAD_CHART`. It is placed after each tab's last card:
  - Books and Films: after Charts;
  - Shows: after Top shows.
- **`YearReviewPages.kt`**: `YearPage.FAVOURITES`, before `SUMMARY`, rendered as three labelled
  rows (BOOK / FILM / SHOW): the title, and `RatingStars` at the rounded score.
- **Strings:** "Your ratings", "Average %1$s · %2$d books/films/shows rated" (plurals), "Mostly
  %s", "Your favourites of %d", "Book" / "Film" / "Show", "Rating spread" (dialog title), and the
  tile's description for TalkBack.

## 4. Testing

TDD throughout: each test is seen failing first, or is falsified by sabotage when it guards existing
behaviour.

- **`RatingStatsTest` (`commonTest`)** covers §2's rules:
  - averaging per title, then across titles;
  - tie order, including dateless after dated;
  - the 8.5-halves bucket;
  - the exact-score display rule's predicate;
  - the empty input.
- **`StatsDaoRatedItemsTest` (`jvmTest`)** covers the dating rules on a real database, per §1's
  table:
  - a DNF read dated by its last session;
  - a read with no sessions falling back to `startedAt`;
  - a watch-through dated by its latest viewing;
  - a dateless watch-through, in All time and in no period;
  - a rating exactly at `from` (in) and exactly at `to` (out);
  - an unrated item never returned;
  - type filtering.
- **View model tests (`jvmTest`):** Films' `ratings` follows the period picker; the year review's
  favourites pick the year's best, not the all-time best; and there are no favourites in a year with
  nothing rated.
- **Screen tests (Robolectric):**
  - the card is absent when `ratings` is null;
  - the exact score is shown for 4.25 and not for 4.5;
  - the spread tile opens its dialog.
- **Goldens:**
  - `stats-films-ratings.png`, the Films tab with the card;
  - `year-review-favourites.png`.
  Both are recorded by hand and looked at, as AGENTS.md §7 requires.
- **Device:** the instrumented suite before the PR. Sample data then needs ratings to show anything;
  add a few to `docs/sample-data` only if the hand check finds the card empty.

## 5. Review focus

The cases the tests above might still miss, most likely first:

1. **A title rated in two domains' tables.** Cannot happen: a media item has one type. The query
   still filters by type on every branch, so a mis-typed row would not leak across tabs.
2. **A watch-through's date moving.** Deleting its latest viewing re-dates it to the one before;
   un-ticking every episode makes it dateless. Both follow from computing the date in the query
   rather than storing it.
3. **Time zone at a period edge.** A finish at 23:30 local on the 31st belongs to that month. Bounds
   come from `StatsPeriod.bounds` in the device zone, as for every other stat.
4. **Large libraries.** The `UNION ALL` reads only rated rows. Both subqueries (latest session,
   latest viewing) are correlated on indexed columns: `reading_sessions.readThroughId` and
   `watch_logs.watchThroughId` are already indexed.
5. **RTL and font scale.** The top-5 rows keep the title from pushing the stars off screen: the
   title ellipsizes, as the season header's does (#163).

## 6. Out of scope

- Opening a title from the top-5 list (the owner chose the plain list).
- Ratings in the global, cross-domain view.
- Changing the detail header's star rounding.
