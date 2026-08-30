# CSV import format

Everything needed to generate importable CSVs for this app, without reading the source. Written to
be handed to a generator (human or model) as a complete brief.

The app exports and imports **three files**, all optional and independently selectable at import
time, but two of them depend on the third: `reading_logs_export.csv` sessions attach to a book, and
`episodes_export.csv` rows attach to a show, from `library_export.csv` — so if you want reading
history or episode state to actually land somewhere, generate all the files that reference each
other as a matched set.

- `library_export.csv` — every media item: books, movies and shows in one file, distinguished by
  `type`
- `reading_logs_export.csv` — reading sessions, linked to a book by `media_id`
- `episodes_export.csv` — a show's episodes, linked to the show by `media_id`

Until Issue #106, `library_export.csv` wrote movie and show columns that nothing read back, and
`episodes_export.csv` had no importer at all — an export-then-reimport of a library with films or
shows silently came back books-only. Both gaps are closed now: the importer reads every row of all
three files.

## Rules that apply to every file

- **Standard RFC 4180 CSV.** Quote any field containing a comma, a double quote, or a newline;
  escape an embedded double quote by doubling it (`""`). UTF-8, no BOM.
- **A header row is required**, with columns in exactly the order given below. Column order is not
  negotiable — the reader matches the header against an expected list and fails the whole file if
  it does not match.
- **`csv_schema_version` is the first column of every data row**, not just the header, and its
  value is `5`. The marker is shared across all three files, so it moves when any one of them
  changes shape, even if a given file's own columns didn't move. A generated file should always
  write `5`; older values (`1`–`4`) are still accepted on import for a real historical export, via
  padding adapters keyed to each file's older header shape, but there is no reason to hand-write
  one.
- **Empty means "unknown"** for every optional field. Write an empty field, never `null`, `N/A`,
  `0`, or `-`. This matters: `0` and empty are different answers, and the app treats a `0` duration
  as a real zero-second session rather than a missing one.
- **Timestamps are ISO-8601 in UTC**, e.g. `2026-01-05T09:15:00Z`. Fractional seconds are accepted.
- **Enum values are the exact uppercase names listed below.** Anything else rejects that row.
- **`media_id` is a UUID string.** It is the join key *into* `library_export.csv`: a reading
  session's `media_id` must match a book, and an episode's `media_id` must match a **show** —
  matched against both the current library and any row in the same import's own library file. A
  session or episode whose `media_id` matches nothing is rejected and reported; an episode whose
  `media_id` matches something that exists but is a book or a film rather than a show is rejected
  too, for the same reason (see File 3 below). Note this isn't actually enforced to be UUID-shaped
  by the importer — it accepts any non-blank string — but the app's own exporter always writes a
  real UUID here, and a hand-built file should too unless there's a specific reason not to.

## File 1 — `library_export.csv`

20 columns, in this order:

| # | Column | Required | Notes |
| ---: | :--- | :--- | :--- |
| 1 | `csv_schema_version` | yes | Always `5` |
| 2 | `media_id` | yes | UUID string; this row's primary key. Referenced by the reading log (books) and by episodes (shows) |
| 3 | `type` | yes | `BOOK`, `MOVIE`, or `TV_SHOW`. Selects which of the type-specific columns below apply — see note below |
| 4 | `title` | yes | Must not be blank, for every type |
| 5 | `authors` | no | Books only. **One string**, multiple authors joined with `"; "` (semicolon + space). Leave empty on a movie or show row |
| 6 | `release_year` | no | Integer, or empty. Range depends on `type` — see note below |
| 7 | `purchase_price` | no | Decimal `>= 0`, or empty. Plain number, no currency symbol. Applies to every type |
| 8 | `created_at` | yes | ISO-8601 UTC; when the item was added. Applies to every type |
| 9 | `cover_image_hash` | no | **Leave empty** — see below. Applies to every type |
| 10 | `isbn` | no | Books only. ISBN-10 or ISBN-13, digits only, or empty |
| 11 | `format` | no | Books only. `PHYSICAL`, `EBOOK`, `AUDIOBOOK`, `PAPERBACK`, `HARDCOVER`, or empty (empty imports as `PHYSICAL`) |
| 12 | `total_pages` | no | Books only. Integer `> 0`, or empty. Empty is normal for audiobooks |
| 13 | `status` | no | Books only. `TO_READ`, `READING`, `FINISHED`, `DNF`, or empty (empty imports as `TO_READ`) |
| 14 | `finished_at` | no | Books only. ISO-8601 UTC. Only meaningful with `status=FINISHED` |
| 15 | `tracking_mode` | no | Books only. `PAGES` or `PERCENT`, or empty (empty infers `PAGES` when `total_pages` is set, `PERCENT` otherwise) |
| 16 | `external_identifiers` | no | `PROVIDER:value`, multiple joined with `\|` (pipe). Applies to every type |
| 17 | `runtime_minutes` | no | Movies only. Integer `> 0`, or empty. Leave empty on a book or show row |
| 18 | `watch_status` | no | Movies and shows. `WATCHLIST`, `WATCHING`, `WATCHED`, `ABANDONED`, or empty (empty imports as `WATCHLIST`). Leave empty on a book row |
| 19 | `watched_at` | no | Movies only. ISO-8601 UTC. Only meaningful with `watch_status=WATCHED`. Leave empty for a book or show row — a show's watched state is per-episode, in `episodes_export.csv`, not here |
| 20 | `total_seasons` | no | Shows only. Integer `> 0`, or empty for "unknown". Leave empty on a book or movie row |

**`type` selects the validation and the columns that matter (Issue #106).** A row's `title`,
`release_year` and `purchase_price` are always checked, but against that type's own rules — in
particular the `release_year` floor differs by medium: `1450`–`2100` for a `BOOK`, `1888`–`2100`
for a `MOVIE`, `1928`–`2100` for a `TV_SHOW` (a book predates cinema by centuries and cinema
predates broadcast television by decades, so one shared floor would accept nonsense for at least
two of the three types). The remaining columns are read only within their own type's branch — a
`BOOK` row's `runtime_minutes`/`watch_status`/`watched_at`/`total_seasons` are ignored even if
filled in, and a `MOVIE` or `TV_SHOW` row's `authors`/`isbn`/`format`/`total_pages`/`status`/
`finished_at`/`tracking_mode` are ignored the same way — so leaving them empty is a matter of
hygiene, not correctness. A `type` this app does not recognize at all (a newer build's file) rejects
the row rather than guessing at a column layout it was never written for.

**Columns 17–20 used to be written but not read back.** Until Issue #106, the importer rejected
every `MOVIE` and `TV_SHOW` row outright — a film's runtime/watch state and a show's season count
were written into every export (so they'd survive in a backup) but nothing could read them back
into a fresh install. That gap is closed: the importer now builds a movie or show row from these
columns exactly as it builds a book row from columns 5/10–15.

**`external_identifiers` providers:** `ISBN`, `OPEN_LIBRARY`, `GOOGLE_BOOKS`, `TMDB`, `TVDB`.
Example: `ISBN:9780441478125|OPEN_LIBRARY:OL27258W`.

**Leave `cover_image_hash` empty.** It names a content-addressed image file on the device, for a
book, movie or show alike. A hash for a file that is not there renders as a missing cover on every
affected item. Blank means no cover yet. For a book, the app's bulk cover/author backfill (ROADMAP
Task 14) can then fill it in properly; movies and shows have no equivalent backfill yet (Task 13
Phase D, not shipped), so a blank cover on one of those stays blank until it lands.

## File 2 — `reading_logs_export.csv`

10 columns, in this order:

| # | Column | Required | Notes |
| ---: | :--- | :--- | :--- |
| 1 | `csv_schema_version` | yes | Always `5` (the marker is shared across all three files, so it moves when any one changes shape) |
| 2 | `session_id` | yes | UUID string, unique per session |
| 3 | `media_id` | yes | Must match a book's `media_id`, in the library file being imported or already in the app |
| 4 | `timestamp_start` | yes | ISO-8601 UTC |
| 5 | `timestamp_end` | yes | ISO-8601 UTC, **must be `>=` `timestamp_start`** |
| 6 | `duration_seconds` | no | Integer `>= 0`, or empty for "unknown" |
| 7 | `start_unit` | yes | Decimal `>= 0`; page number, or percent `0`–`100` |
| 8 | `end_unit` | yes | Decimal `>= 0` |
| 9 | `delta_pages` | no | Integer pages covered, or empty |
| 10 | `notes` | no | Free text; quote it if it contains a comma |

**`start_unit` / `end_unit` meaning depends on the book's `tracking_mode`:** page numbers when
`PAGES`, a percentage `0`–`100` when `PERCENT`. Keep them consistent with the book they belong to,
or the numbers will read as nonsense in the UI even though they import cleanly.

**Sessions are independent facts, not a continuous log.** One session's `end_unit` does not have to
equal the next session's `start_unit` — gaps are expected and the app does not reconcile them. Do
not force them to chain.

## File 3 — `episodes_export.csv`

A show's episodes are one-to-many under the show — the same relationship reading sessions have to a
book — so they get their own file rather than columns on `library_export.csv`. Show-level data
(`total_seasons`) stays on the show's own row in `library_export.csv`, the way a movie's
`runtime_minutes` does; only the per-episode rows live here.

Until Issue #106 this file was export-only: the app wrote it on every export (so a show's
per-episode watched state would survive in a backup) but had no importer for it, so a hand-built or
re-imported `episodes_export.csv` had nowhere to go. An importer now exists and reads every row.

12 columns, in this order:

| # | Column | Required | Notes |
| ---: | :--- | :--- | :--- |
| 1 | `csv_schema_version` | yes | Always `5` (the marker is shared across all three files) |
| 2 | `episode_id` | yes | UUID string, unique per episode |
| 3 | `media_id` | yes | The show; must match a `media_id` in the library file (or the existing library) whose `type` is `TV_SHOW` |
| 4 | `season_number` | yes | Integer `>= 0`. Regular seasons are 1-based; `0` means specials, and is a legitimate value rather than a placeholder |
| 5 | `episode_number` | yes | Integer `> 0`, 1-based within the season. Unlike `season_number`, `0` is not meaningful here and is rejected |
| 6 | `title` | no | Empty is normal for a quick-filled episode whose title is not yet known |
| 7 | `air_date` | no | ISO-8601 UTC, or empty if unknown |
| 8 | `watched_at` | no | ISO-8601 UTC, or empty if unwatched. **This is the column the file exists for** — it is the watched state episode-level tracking exists to record |
| 9 | `runtime_minutes` (CSV `v5`) | no | Integer `> 0`, or empty. Per-episode, not shared with the show's own row |
| 10 | `overview` (CSV `v5`) | no | Free text; quote it if it contains a comma |
| 11 | `still_image_hash` (CSV `v5`) | no | **Leave empty** — same reasoning as `cover_image_hash` above: it names an image file this device does not have. Parsed for completeness but never written back by the importer even when a cell is filled in, for a fresh episode or an existing one alike |
| 12 | `community_rating` (CSV `v5`) | no | Decimal `0.0`–`10.0`, or empty. Already normalized to a 0–10 scale — this column's contract, regardless of what scale the original provider used (TMDB is out of 10, others differ) |

Columns 9–12 were added in CSV `v5` (Issue #106), appended after `watched_at` so no existing column
moved — a `v4` episodes file (columns 1–8 only) still imports; the four new cells are padded in as
blank.

**A row whose `media_id` names something that exists but isn't a `TV_SHOW`** (a book or a film) is
rejected rather than silently attached: `episodes.mediaId` alone doesn't constrain the row to a
show, so without this check the episode would insert cleanly against the wrong item and simply
never be shown by anything — worse than a rejection, because nothing would report it.

## Validation — what gets a row rejected

Rejected rows are skipped and reported; they do not fail the import. But a generated file full of
rejects is not useful, so:

**Every `library_export.csv` row:**
- `type` not exactly `BOOK`, `MOVIE` or `TV_SHOW`
- `title` blank
- `purchase_price` negative or not a finite number
- `release_year` outside that row's own type's range: `1450`–`2100` (`BOOK`), `1888`–`2100`
  (`MOVIE`), `1928`–`2100` (`TV_SHOW`)
- a malformed `external_identifiers` segment

**A `BOOK` row, additionally:**
- `total_pages` `<= 0` (use empty for unknown, never `0`)
- `format`, `status` or `tracking_mode` not exactly matching the lists above (an empty cell is fine
  and takes its documented default; an unrecognized value is not)

**A `MOVIE` row, additionally:**
- `runtime_minutes` `<= 0`
- `watch_status` not exactly matching the list above (empty is fine and defaults to `WATCHLIST`)

**A `TV_SHOW` row, additionally:**
- `total_seasons` `<= 0`
- `watch_status` not exactly matching the list above (same rule as a movie row)

**Every `reading_logs_export.csv` row:**
- `timestamp_end` earlier than `timestamp_start`
- `duration_seconds` negative
- `start_unit` or `end_unit` negative or not finite
- **a session whose `media_id` matches no book** in the library file or already in the app

**Every `episodes_export.csv` row:**
- `season_number` negative (`0` is valid — specials)
- `episode_number` `<= 0`
- `runtime_minutes` `<= 0` when present
- `community_rating` outside `0.0`–`10.0`, or not a finite number
- **an episode whose `media_id` matches no show** — either matching nothing at all in the library
  file or existing library, or matching something that exists but isn't a `TV_SHOW`

## Generating a realistic set

**Books and reading sessions:**

- Give each book **0–15 sessions**, weighted by status: `FINISHED` books should have a full run of
  sessions ending at `total_pages`; `READING` books a partial run; `TO_READ` books none; `DNF`
  books a few that stop early.
- Sessions for one book should advance forward in time and in position, without necessarily being
  contiguous.
- Make `duration_seconds` roughly plausible against pages covered — say 1–3 minutes a page — but
  leave it empty on a few sessions, since manually backdated entries legitimately have no duration.
- `finished_at` on a `FINISHED` book should be at or after its last session's `timestamp_end`.
- Vary deliberately: a book with no ISBN, one with no authors, a title containing a comma and one
  containing non-ASCII characters, a multi-author book, several books by one author, an audiobook
  with empty `total_pages` and `PERCENT` tracking, and a session with a long note.

**Films:**

- Cover every `watch_status` (`WATCHLIST`, `WATCHING`, `WATCHED`, `ABANDONED`), the way the books
  cover every reading status.
- Give at least one film no `runtime_minutes` — the case that renders as a blank rather than a
  stray "0 min" — and at least one a `watched_at` timestamp so that column is exercised too.

**Shows and episodes:**

- Give at least one show episodes covering more than one season, with at least one season only
  partially watched and its unwatched episodes left with a blank `title` — the state a quick-filled
  episode is in before any metadata backfill.
- Give at least one show a season `0` with a couple of unwatched specials, to exercise that
  specials count toward a show's completion the same way regular episodes do.
- Give at least one show every episode watched and titled, with `runtime_minutes`/`overview`/
  `community_rating` populated on some of them — the "already backfilled" case.
- Give at least one show a `total_seasons` on its `library_export.csv` row but **zero** rows for it
  in `episodes_export.csv` — a real state (added but not yet quick-filled), not an omission.

## Worked example

`library_export.csv` — two books, a film and a show:

```csv
csv_schema_version,media_id,type,title,authors,release_year,purchase_price,created_at,cover_image_hash,isbn,format,total_pages,status,finished_at,tracking_mode,external_identifiers,runtime_minutes,watch_status,watched_at,total_seasons
5,11111111-1111-4111-8111-111111111111,BOOK,The Left Hand of Darkness,Ursula K. Le Guin,1969,12.99,2026-01-05T09:15:00Z,,9780441478125,PAPERBACK,304,FINISHED,2026-02-01T21:40:00Z,PAGES,ISBN:9780441478125,,,,
5,33333333-3333-4333-8333-333333333333,BOOK,Good Omens,Terry Pratchett; Neil Gaiman,1990,14.00,2026-01-07T09:15:00Z,,9780060853983,HARDCOVER,491,READING,,PAGES,ISBN:9780060853983,,,,
5,44444444-4444-4444-8444-444444444444,MOVIE,Arrival,,2016,9.99,2026-01-10T09:15:00Z,,,,,,,,,116,WATCHED,2026-01-11T21:00:00Z,
5,99999999-9999-4999-8999-999999999999,TV_SHOW,Chernobyl,,2019,24.99,2026-01-12T09:15:00Z,,,,,,,,,,WATCHED,,1
```

Note the film and show rows leave every book-only column (5, 10–15) empty, and the book rows leave
every movie/show-only column (17–20) empty — `type` is what decides which half of the row means
anything, per the note under File 1 above.

`reading_logs_export.csv` — sessions attach only to books:

```csv
csv_schema_version,session_id,media_id,timestamp_start,timestamp_end,duration_seconds,start_unit,end_unit,delta_pages,notes
5,aaaaaaaa-0001-4000-8000-000000000001,11111111-1111-4111-8111-111111111111,2026-01-20T19:05:00Z,2026-01-20T20:10:00Z,3900,0.0,48.0,48,Opening chapters
5,aaaaaaaa-0001-4000-8000-000000000002,11111111-1111-4111-8111-111111111111,2026-01-24T21:00:00Z,2026-01-24T21:45:00Z,,48.0,95.0,47,
5,aaaaaaaa-0002-4000-8000-000000000001,33333333-3333-4333-8333-333333333333,2026-02-14T08:30:00Z,2026-02-14T09:00:00Z,,120.0,151.0,31,"Commute reading, noisy train"
```

Note the second session has an empty `duration_seconds` (unknown, not zero) and an empty `notes`,
and the third quotes a note containing a comma.

`episodes_export.csv` — episodes attach only to the show above, by its `media_id`:

```csv
csv_schema_version,episode_id,media_id,season_number,episode_number,title,air_date,watched_at,runtime_minutes,overview,still_image_hash,community_rating
5,bbbbbbbb-0001-4000-8000-000000000001,99999999-9999-4999-8999-999999999999,1,1,1:23:45,2019-05-06T00:00:00Z,2026-01-15T21:00:00Z,65,The plant explodes.,,8.6
5,bbbbbbbb-0001-4000-8000-000000000002,99999999-9999-4999-8999-999999999999,1,2,,,,,,,
```

Note the second episode has an empty `title`, `air_date`, `watched_at`, `runtime_minutes`,
`overview` and `community_rating` — all normal for a quick-filled row whose metadata and watched
state are not yet known, not errors. `still_image_hash` is empty on both, for the same reason
`cover_image_hash` is empty on every row above.

The first episode's `runtime_minutes`, `overview` and `community_rating` are **illustrative values
chosen to show the columns populated** — they are not real provider data for that episode, and are
not accurate. This document's job is to show what each column looks like when filled; the shipped
fixture in `episodes_sample.csv` deliberately leaves all three blank rather than invent them (see
that directory's README). Do not copy these values anywhere they would be read as fact.
