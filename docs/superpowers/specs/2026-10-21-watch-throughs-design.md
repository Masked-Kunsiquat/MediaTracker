# Show watch-throughs and film viewing ratings (#231)

Part 2 of #220, after Part 1 (#230, book read-throughs, PR #233). Part 3 (#232, rating stats) builds
on both and is out of scope here. Together with #230 this completes ROADMAP Task 10.

## 0. Decisions (owner, 2026-10-21)

- **Ratings stay per viewing.** A film's viewing is one `watch_logs` row. A show's viewing is a
  **watch-through**: one pass through the show, numbered #1, #2…
- **Storage:**
  - films: a `rating` on each film viewing, with no new table, because a viewing is already one row;
  - shows: a new `watch_throughs` table, and each episode viewing records its watch-through.
- **A watch-through's state is derived, not stored.** Watching, Finished and Up to date are worked
  out from the episode ticks, as a show's state already is (`LibraryStatusFilter.ofShow`). Only
  *abandoned* is stored, because giving up is a decision no tick count expresses.
- **The checklist is fresh per watch-through.** Ticks show the latest watch-through only. Earlier
  ones fold below as one-line cards, which cannot be ticked.
- **Finish:**
  - A watch-through is complete when every episode the library holds is ticked in it.
  - It reads Finished, or **Up to date** for a show still airing.
  - On becoming complete it asks "Rate this watch-through?", once, and only if it is unrated.
  - When a later season is added it is Watching again and keeps its rating.
- **Abandon is per watch-through.** Abandoning #2 leaves #1 Finished. Abandoning also asks for a
  rating, as a book's Did not finish does.
- **Watch again** sits beside Abandon/Resume on the show's status chip, offered when the latest
  watch-through is complete or abandoned.
- **Schema:** folded into the unreleased v8 (owner), not a v9. The owner's phone, which has v8
  from #230's hand check, needs a backup and a clean reinstall.
- **Reuse Part 1:** the `RatingStars` component, the "Yours" header row, the rate-on-finish dialog,
  and the numbered-runs shape with the latest open and older ones folded.

## 1. Data model (schema v8, edited in place)

### `watch_throughs` (new)

| Column | Type | Notes |
| :--- | :--- | :--- |
| `id` | TEXT PK | UUID (AGENTS.md §3) |
| `mediaId` | TEXT NOT NULL | FK → `media_items.id`, `ON DELETE CASCADE`, the same as `read_throughs` |
| `number` | INTEGER NOT NULL | 1, 2, 3… per show; unique index on (`mediaId`, `number`) |
| `abandoned` | INTEGER NOT NULL | boolean, default 0 |
| `rating` | INTEGER NULL | half-steps 1..10, validated in code (`isValidRating`); null = unrated |

- **Every show has watch-through #1.** It is created with the show, by the migration for existing
  shows, and by the importer.
- **There are no `startedAt` / `finishedAt` columns.**
  - Started is the run's earliest viewing.
  - Finished is the latest viewing, once the run is complete.
  - Both are derived, so a correction of a viewing's date can never disagree with a stored copy.

### `watch_logs`

- **Gains `watchThroughId TEXT NULL`:** FK → `watch_throughs.id`, `ON DELETE CASCADE`, indexed.
  - Set exactly when `episodeId` is set: episode viewings belong to a run, film viewings do not.
  - Enforced in code, in every write path; a test covers each.
- **Gains `rating INTEGER NULL`:** half-steps 1..10.
  - Set only on film viewings (`episodeId` null).
  - An episode viewing's rating is always null; a show is rated per watch-through.

### `episodes.watchedAt`: its meaning narrows

- **Today:** the episode's latest viewing ever.
- **From now on:** the episode's latest viewing *in the show's latest watch-through*, or null when
  the latest watch-through has none.

This keeps every existing reader working unchanged: the checklist, progress, the tick prompt,
`MarkEpisodesWatchedUseCase` and `LibraryStatusFilter` all read "ticked" off this column. It stays a
cache that the `TVWriteDao` transactions keep equal to the viewings (the rule at `TVWriteDao.kt`'s
"Viewings are the truth" comment), now scoped to the latest run. The exact recompute query is:

`watchedAt = (SELECT MAX(l.watchedAt) FROM watch_logs l WHERE l.episodeId = episodes.id AND l.watchThroughId = <latest run of the show>)`

### `tv_details`

- **Loses `status`.** Abandonment moves to `watch_throughs.abandoned`, so there is one source of
  truth. The `status` column was only ever read for `ABANDONED` (see its KDoc).
- SQLite needs a table rebuild for this.

### `movie_details`

- Unchanged: a film keeps its `status` and `watchedAt`.

## 2. Migration 7 → 8 (extending `MIGRATION_7_8`)

These steps are added after the existing #230 steps, in this order:

1. **Create `watch_throughs`** and its unique index.
2. **Insert watch-through #1 for every `TV_SHOW` media item.**
   - Its id is the `SQL_UUID` expression already used for read #1.
   - `abandoned` = `tv_details.status = 'ABANDONED'`.
   - `rating` is null.
3. **Rebuild `watch_logs`** with `watchThroughId` and `rating`.
   - Episode viewings get their show's watch-through #1.
   - Film viewings get null.
   - Both foreign keys and all indices are recreated.
4. **Rebuild `tv_details`** without `status`.

`episodes.watchedAt` needs no change, because with one run "latest ever" and "latest in the latest
run" are the same.

**Ledger:** v8's AGENTS.md §8 row gains "; `watch_throughs`, episode viewings grouped by
watch-through, film viewings rated; a show's abandoned flag moves onto its watch-through (#231)".

## 3. Behaviour

### Shows

- **Tick and untick act on the latest watch-through.**
  - A tick adds a viewing to the latest run.
  - "Watched again" on an episode adds another viewing to the latest run; the episode is still one
    tick.
  - An untick removes that episode's viewings *in the latest run* only.
  - "Clear season" and its Undo work the same way, within the latest run.
- **The episode sheet's Viewings list** shows every viewing of the episode, newest first. When the
  show has more than one run, each viewing is labelled with its run ("Watch-through #1").
  - Editing or deleting one keeps it in its run.
  - Deleting a viewing in an older run never changes the current ticks.
- **Complete:** every episode the show holds is ticked in the latest run.
  - Specials (season 0) count or not according to the include-specials setting (#88), through
    `TVProgressCounts.counted`, the same pair the library filter and progress already use.
  - A show with no episodes is never complete.
- **A watch-through's label:**
  - Abandoned, if abandoned;
  - otherwise Finished or Up to date, if complete (Up to date when `airingStatus = CONTINUING`, as
    `ofShow` already decides);
  - otherwise Watching, or Not started when it has no viewings.
- **Watch again:**
  - offered when the latest run is complete or abandoned;
  - creates run *n+1* (not abandoned, unrated) and clears every episode's `watchedAt` for the show
    in the same transaction;
  - a double tap fails on the unique index and reports an error, as `readAgain` does.
- **Remove this watch-through:**
  - only for the latest run, only when it is not #1, and only when it has no viewings;
  - deletes it and recomputes `watchedAt` from the run that is now latest, in one transaction;
  - this is the undo for a mistaken Watch again.
- **Abandon / Resume** set or clear the latest run's `abandoned`. Ticking on an abandoned run is
  allowed and leaves it abandoned until Resume, which is today's behaviour.
- **Rate this watch-through?** opens:
  - when an action makes the latest run complete and it is unrated;
  - when the latest run is abandoned and it is unrated.

  Skip leaves it unrated. It is never offered on an episode tick that does not complete the run.
- **Rating:** each run's card has editable stars. Tapping the current value clears it, as in #230.
- **The startup backfill** (`ensureViewings…`, the watched-without-viewings path) writes its
  viewings into the show's latest run.
- **Adding a show** (search or manual) creates run #1 in the same transaction as the show, inside
  `insertShowAtomically`.

### Films

- **The "Watched" dialog** (Now / Pick a date, first and repeat viewings) gains optional stars
  under the choices. The date and the stars are saved together, and leaving the stars empty saves
  an unrated viewing.
- **The History list:** each viewing shows its stars, which can be tapped to set, change or clear.
- **Status changes** do not prompt for a rating separately. The dialog that records the viewing is
  the prompt.
- An abandoned film has no viewing, so it has no rating.

### A title's rating

- `titleRating` (from #230) is generalised to any list of ratings in order.
  - A show: its runs' ratings, by number.
  - A film: its viewings' ratings, by date.
- The header reads "4.5 average of 2 watch-throughs · latest 5" for a show and "… of 3 viewings ·
  latest 5" for a film. With one rated item it shows the stars alone, and with none, "Not rated
  yet".

## 4. Screens

- **Shared components:** `YourRatingRow`, `RateReadDialog` and the run cards move from
  `BookReads.kt` into `ui/components`, taking their nouns as parameters ("read", "watch-through"),
  so books, films and shows use one copy. The book screens must look identical afterwards: their
  goldens must not change.
- **The show detail screen:**
  - **Header:** a "Yours" row.
  - **Status chip:** the latest run's label, with actions:
    - Abandon or Resume;
    - Watch again, when it is offered.
  - **Above the seasons:** a card for the latest run, with "Watch-through #n · <label>", the date
    line (started, or finished), its stars, and an overflow menu with Remove this watch-through when
    allowed.
    - A show with one run shows this card too, so run #1 can be rated.
  - **The seasons and checklist** are as today, reading the latest run.
  - **Below the seasons:** earlier runs, folded: "Watch-through #n · Finished <date>" (or
    Abandoned), stars, and "N episodes".
    - Tapping one expands a read-only per-season summary ("Season 1: 7 / 7"), not a checklist.
- **The film detail screen:**
  - **Header:** a "Yours" row.
  - **The watched dialog:** stars and a "4.5" readout.
  - **History:** stars on each row.
- **"Rate this watch-through?"** is the shared dialog, with the show's title and the run number.

## 5. Stats (one engine, #126)

- **Viewings are unchanged rows**, so the following read the same as before for every existing
  library:
  - episodes watched;
  - films watched;
  - hours watched;
  - `isRewatch`;
  - the viewing breakdown;
  - first activity;
  - the year in review's film and show pages.

  The existing stats suites must pass untouched against migrated data.
- **The library status filter** for a show uses `ofShow` with the latest run's `abandoned` in
  place of `tv_details.status`. Its progress row already counts `watchedAt`, which now means the
  latest run.
- **No watching goal exists**, so there is no first-run rule to add. Goals stay books-only.
- **Rating stats are #232.**

## 6. Import and export

- **CSV schema version 7 → 8.** Version 7 shipped in no release, but files written by #230's
  builds exist, so it is a version bump and not an in-place edit.
- **`watch_logs_export.csv`** gains two columns:
  - `watch_through_number`: blank for a film;
  - `rating`: stars, as in `read_throughs_export.csv`, and blank for an episode.
- **A new `watch_throughs_export.csv`:** `media_id, number, abandoned, rating`. It is added to the
  archive's entries.
- **`episodes_export.csv`** keeps `watched_at`. On import, `watchedAt` is recomputed from the
  imported viewings, not trusted from the file.
- **Older files (version ≤ 7):** every show gets run #1, not abandoned, and every episode viewing
  belongs to it. Today's export never carried a show's abandoned flag, so nothing is lost that was
  ever saved.
- **A version 8 viewing with `watch_through_number` greater than 1** whose run is not in the file
  or the library is rejected with a reason, the same rule as #230's `readNumber`.
- **A lone `watch_throughs_export.csv`** is refused with the not-a-library hint, as #230 does for
  reads.
- **Duplicate policies apply to watch-throughs and film ratings:**
  - Skip leaves an existing row untouched;
  - Merge fills an empty rating and never overwrites a set one;
  - Replace overwrites.

## 7. Out of scope

- Rating stats (#232).
- Moving viewings between watch-throughs.
- A rating for a single episode.
- A watching goal.

## 8. Testing

- **`MigrationTest` 7 → 8**, extended with:
  - a show abandoned and one not;
  - episode viewings and film viewings;
  - a show with no episodes.

  Assert run #1 per show, `abandoned`, each viewing's `watchThroughId` (episodes) or null (films),
  null ratings, `tv_details` without `status`, and `episodes.watchedAt` unchanged.
- **DAO and repository (jvmTest):**
  - Watch again clears ticks;
  - Remove this watch-through restores the previous run's ticks, and is refused when it has
    viewings, is #1 or is not the latest;
  - tick, untick, Watched again and Clear season with Undo, all within the latest run;
  - deleting an older run's viewing leaves ticks alone;
  - the startup backfill writes into the latest run;
  - `insertShowAtomically` creates run #1;
  - rating validation 1..10;
  - an episode viewing can never carry a rating, and a film viewing never a run.
- **Pure (commonTest):**
  - completion and labels (Finished, Up to date, Watching, Not started, Abandoned), including
    specials counted and not counted;
  - the rate-prompt rule;
  - the generalised `titleRating`.
- **ViewModels:**
  - the show prompt on the completing tick and on Abandon, and not when the run is already rated;
  - Watch again and remove, including a double tap;
  - the film dialog saving its rating.

  Each awaits `uiState.first {}`.
- **Stats:** the existing film and show suites run against migrated data, plus a re-watched show:
  each run's viewings count in their own period, and `isRewatch` is unchanged.
- **Import and export:**
  - a v8 round trip, including an abandoned run and rated film viewings;
  - a v7 and a v6 archive import as run #1;
  - each duplicate policy;
  - the lone watch-throughs CSV is refused;
  - an unknown run number is rejected.
- **Robolectric:**
  - the show screen: the run card, the folded runs, Watch again and Remove in the menu,
    Abandon/Resume, and the dialog;
  - the film screen: the dialog's stars and the History stars.
- **Goldens:**
  - new: a show with two runs, and a film with rated viewings;
  - the book goldens must not change (shared components);
  - existing show and film goldens re-recorded where the header gains "Yours", then viewed.
- **Device:** a backup first, then `connectedDebugAndroidTest`, then `seedDebugDevice`, then a hand
  check:
  - finish a show, rate it, Watch again, tick through, rate #2, check the average;
  - rate a film's viewings.

## 9. Documents to update

- **AGENTS.md:** the §8 v8 ledger row (above).
- **ROADMAP.md:** Task 10 is done for books, films and shows; #232 is next.
- **CHANGELOG.md `[Unreleased]`:**
  - *Added:* show re-watches and ratings on films and shows;
  - *Internal:* schema v8 extended, CSV 8.
