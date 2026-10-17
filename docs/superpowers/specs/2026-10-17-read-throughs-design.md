# Read-throughs, each with its own rating (#230)

Part 1 of #220. Parts 2 (#231, show watch-throughs and film viewing ratings) and 3 (#232, rating
stats) build on this and are out of scope here. This is also the core of ROADMAP Task 10.

## 0. Decisions (owner, 2026-10-17)

- **Ratings are per viewing**, because feelings change over time. For a book, a viewing is a
  **read-through**. A title's rating is the **average of its rated reads**, shown with the
  **latest** rating.
- **Scale:** half stars, 0.5 to 5.
- **Storage (option A): reads own everything.** A new table of reads holds each read's status,
  dates and rating. A book's status *is* its latest read's status. `book_details.status` and
  `book_details.finishedAt` move into read #1 and are removed, so there is one source of truth.
- **Re-read:** "Read again" in the book's status menu, offered once the latest read is Finished or
  Did not finish.
- **Counting:**
  - Every finished read counts as a book finished, in the period it finished in.
  - **The reading goal counts only a book's first finished read**, because goals are for material
    new to you.
- **Reading history tab:** the latest read is shown in full, and earlier reads are folded into
  one-line cards that expand on tap.
- **Where you rate:**
  - each read's card;
  - an optional "Rate this read?" dialog when a read becomes Finished or Did not finish.
- **Header:** "Yours" with stars showing the average, then "4.5 average of 2 reads · latest 5.0".
  With exactly one rated read, just the stars.
- **Icons:** Google Material Symbols (Outlined) `star` (fill 0 and fill 1) and `star_half`
  (fill 1), added as local vector drawables. No new dependency.

Mock-ups were reviewed and approved in the session: the header in light and dark, the history tab
and the dialog.

## 1. Data model (schema v8)

### `read_throughs` (new)

| Column | Type | Notes |
| :--- | :--- | :--- |
| `id` | TEXT PK | UUID (AGENTS.md §3) |
| `mediaId` | TEXT NOT NULL | FK → `book_details.mediaId`, `ON DELETE CASCADE` |
| `number` | INTEGER NOT NULL | 1, 2, 3… per book; unique index on (`mediaId`, `number`) |
| `status` | TEXT NOT NULL | a `ReadingStatus` |
| `startedAt` | INTEGER NULL | when the read began |
| `finishedAt` | INTEGER NULL | when it became Finished; same rules as today's `book_details.finishedAt` |
| `rating` | INTEGER NULL | half-steps 1..10 (7 = 3.5 stars); null = unrated |

- **Every book has at least one read.** Read #1 of a book never started is `TO_READ`. Only read #1
  may be `TO_READ`.
- **`rating` is validated in code to 1..10.** Room cannot express a `CHECK`, so the repository
  rejects anything else, and so do the importers.
- **The latest read** (highest `number`) is the book's state. It is exposed through one DAO query
  (or a Room `@DatabaseView`, the plan's choice), so every caller reads a book's status in one
  place, never by re-deriving "highest number" itself.

### `reading_sessions`

- Gains `readThroughId TEXT NOT NULL`: FK → `read_throughs.id`, `ON DELETE CASCADE`, indexed.
- `mediaId` stays. Many existing queries filter by book, and it saves a join.

### `book_details`

- Loses `status` and `finishedAt`. SQLite needs a table rebuild for this, done inside the
  migration.

## 2. Migration 7 → 8

Done in one migration (`MIGRATION_7_8`, registered through `loggedMigration`), in this order:

1. **Create `read_throughs`.** For every `book_details` row, insert read #1:
   - `status` and `finishedAt` copied from the book;
   - `startedAt` = the earliest `reading_sessions.timestampStart` for the book, or null;
   - `rating` null.
2. **Add `readThroughId` to `reading_sessions`**, set to the book's read #1. The FK requires a
   table rebuild.
3. **Rebuild `book_details`** without `status` and `finishedAt`.

**Nothing is lost:**
- every session belongs to read #1;
- every book's status and finish date are unchanged when read through the latest read.

A `.sqlite` backup made on v7 and restored later goes through the same migration.

**Ledger:** append `v8 | not yet frozen | MIGRATION_7_8 — read_throughs; sessions grouped by read;
book status and finish date move to the read` to AGENTS.md §8, in the commit that bumps
`APP_DATABASE_VERSION`. Per #220, #231 may extend v8 in place if no release ships between the two.

## 3. Behaviour

- **The status menu acts on the latest read.** Whatever the menu allows today it still allows, on
  that read. Picking Reading on a finished read corrects that read (for example an accidental
  Finished); it does not start a new one. On a read after #1, To read is not offered, because only
  read #1 may be To read; "Remove this read" is the undo there instead.
- **Read again:**
  - offered when the latest read is Finished or Did not finish;
  - creates read *n+1* at Reading, with `startedAt` = now;
  - earlier reads keep their status, dates and rating.
- **Remove this read:**
  - on a read with **no sessions** that is not read #1;
  - deletes it, and the book returns to the previous read's state;
  - this is the undo for a mistaken Read again.
- **Sessions:** a new session always goes to the latest read. Editing or deleting a session leaves
  it in its read. Moving sessions between reads is out of scope.
- **Progress:** the progress card and its "current page" use the latest read's sessions only, so
  read #2 starts from the beginning.
- **finishedAt:** a read's `finishedAt` follows today's rules, applied per read:
  - set when the read becomes Finished;
  - left alone when saved again while Finished;
  - cleared when it moves away from Finished.
- **Rating:**
  - Set on a read's card: tap the left half of a star for x.5, the right half for x.0.
  - Tapping the current value again clears it.
  - Saved immediately.
  - When a read becomes Finished or Did not finish, "Rate this read?" offers the same stars. Skip
    leaves it unrated; Save stores it. It is offered on the status change itself, not again later.
- **A title's rating:**
  - the average of its rated reads, plus the latest rated read's value;
  - unrated reads are ignored;
  - no rated reads means "Not rated yet".

## 4. Screens

- **Detail header** (`DetailHeader`):
  - a "Yours" row under the community rating, using the shared stars component, read-only
    (setting happens on a read);
  - text: "4.5 average of 2 reads · latest 5.0" when there are 2 or more rated reads, nothing extra
    for one, and "Not rated yet" for none;
  - it must stay visually distinct from the community "★ 7.9 / 10".
- **Reading history tab:**
  - **The latest read is a card** showing:
    - "Read #n";
    - its status and start date, plus the finish date when there is one;
    - its rating row;
    - its sessions as today;
    - an overflow menu with "Remove this read" when allowed.
  - **Earlier reads are folded cards:** "Read #n · Finished <date>" (or Did not finish), their
    stars, and "N sessions · total time". They expand on tap to the same full card.
  - **A book with one read** shows the single card, so it looks like today plus a rating row.
- **"Rate this read?" dialog:** title, one line naming the book and the read number, large stars,
  a "4.5" readout, and Skip / Save.
- **The stars component** (`ui/components`): shared by header, card and dialog.
  - Draws the three Material Symbols drawables. The half star is `autoMirrored` for right-to-left
    languages.
  - **TalkBack:** one node, "Your rating, 4.5 of 5" or "Not rated". Editable instances expose
    `setProgress` and increase/decrease semantics in half steps, plus a "Clear rating" action.
  - The Material 3 minimum touch target is kept per half-star.

## 5. Stats (one engine, #126)

- **Books finished in a period:** finished reads whose `finishedAt` is in the period. Re-reads
  count, the same as `observePeriodStats`' `booksFinished`, now over `read_throughs`.
- **Reading goal:** finished reads in the year that are their book's **first finished read**
  (the lowest-numbered read with status Finished). This is a separate count from books finished.
  If read #1 was Did not finish and read #2 finishes, read #2 is the first finish and counts.
- **Finish outcomes (DNF rate, #219):** over reads that ended (Finished or Did not finish), so a
  re-read that ends is a second outcome.
- **Year in review (#221):** books finished, longest book and the goal page follow the rules above.
  The longest book picks among finished reads, so a book read twice that year is still one entry.
- **First activity (`observeFirstActivity`):** reads `read_throughs.finishedAt` instead of
  `book_details`.
- **Library status filter and row status:** the latest read's status.

Every existing stats test keeps passing for libraries without re-reads. That is the migration's
promise, checked by running the existing suites against migrated data.

## 6. Import and export

- **CSV schema version 6 → 7.**
  - `library_export.csv` keeps `status` and `finished_at`, meaning the latest read, so older readers
    of the file still work.
  - `reading_logs_export.csv` gains `read_number`.
  - A new `read_throughs_export.csv`: `media_id, number, status, started_at, finished_at, rating`
    (rating as stars, e.g. `4.5`, empty when unrated). It is added to the archive's entries.
- **Older files (version ≤ 6):** every book gets read #1 from `library_export.csv`'s status and
  finish date, and every session belongs to it. That is the same mapping as the migration.
- **Duplicate policies apply to ratings like any managed field:**
  - Skip leaves an existing read untouched;
  - Merge fills an empty rating or date and never overwrites a set one;
  - Replace overwrites.
- **Goodreads:**
  - `My Rating` 1..5 → read #1's `rating` × 2; 0 means unrated.
  - `Date Read` → read #1's `finishedAt`, as now.
  - Re-importing an old Goodreads export with Merge over books imported before this change fills
    their read #1 rating. Task 10 promised this, so it must be tested.
  - `Read Count` is still not imported, since there are no dates for earlier reads. The import
    notice names only `Bookshelves` and `Read Count` from now on.

## 7. Out of scope

- Film and show ratings, and watch-throughs (#231).
- Rating stats: top rated, average and spread, best of the year (#232).
- Moving sessions between reads.
- Importing Goodreads `Read Count`.

## 8. Testing

- **`MigrationTest`:** v7 → v8 on a database with:
  - books in every status, with and without sessions;
  - a Finished book with `finishedAt`, and one without;
  - sessions.

  Assert read #1's fields, every session's `readThroughId`, and the dropped columns.
- **DAO and repository (jvmTest):**
  - latest read; Read again; Remove this read (allowed and refused);
  - `finishedAt` rules per read;
  - rating validation 1..10;
  - average and latest.
- **Stats (jvmTest):**
  - books finished counts re-reads;
  - the goal counts first finishes only, including DNF then finish;
  - DNF rate over reads;
  - year in review;
  - first activity.
- **Import and export:**
  - a v7 round trip;
  - a v6 archive imports as read #1;
  - Goodreads `My Rating`, including the Merge backfill over an existing book;
  - each duplicate policy on ratings.
- **Robolectric:**
  - the stars component: half and whole taps, clear, and TalkBack semantics;
  - the history tab: folded and expanded;
  - Read again and Remove this read in the menu;
  - the dialog on Finished and Did not finish.
- **Goldens:** the book detail header with a rating, and the history tab with two reads.
  Re-recorded and viewed.
- **Device:** `connectedDebugAndroidTest`, then a hand check on the seeded library: re-read a
  book, rate both reads, and check the header average and the year in review.

## 9. Documents to update

- **AGENTS.md:**
  - §8 ledger row (above);
  - §4 or §3 if the data-model description names `book_details.status`.
- **ROADMAP.md:** Task 10's entry, marking what this delivers and pointing at #220, #230 and #231.
- **CHANGELOG.md `[Unreleased]`:** re-reads and per-read ratings (user-visible); the schema and CSV
  version under `### Internal`.
