# CSV import format

Everything needed to generate importable CSVs for this app, without reading the source. Written to
be handed to a generator (human or model) as a complete brief.

The app imports **two separate files**. They are imported together, library first, and a session
can only attach to a book the importer already knows about — so if you want reading history, both
files must be generated as a matched pair.

- `library_export.csv` — the books
- `reading_logs_export.csv` — reading sessions, linked to books by `media_id`

## Rules that apply to both files

- **Standard RFC 4180 CSV.** Quote any field containing a comma, a double quote, or a newline;
  escape an embedded double quote by doubling it (`""`). UTF-8, no BOM.
- **A header row is required**, with columns in exactly the order given below. Column order is not
  negotiable — the reader matches the header against an expected list and fails the whole file if
  it does not match.
- **`csv_schema_version` is the first column of every data row**, not just the header, and its
  value is `2`.
- **Empty means "unknown"** for every optional field. Write an empty field, never `null`, `N/A`,
  `0`, or `-`. This matters: `0` and empty are different answers, and the app treats a `0` duration
  as a real zero-second session rather than a missing one.
- **Timestamps are ISO-8601 in UTC**, e.g. `2026-01-05T09:15:00Z`. Fractional seconds are accepted.
- **Enum values are the exact uppercase names listed below.** Anything else rejects that row.
- **`media_id` is a UUID string.** It is the join key between the two files, so a session's
  `media_id` must exactly match the book's.

## File 1 — `library_export.csv`

16 columns, in this order:

| # | Column | Required | Notes |
| ---: | :--- | :--- | :--- |
| 1 | `csv_schema_version` | yes | Always `2` |
| 2 | `media_id` | yes | UUID string; referenced by the reading log |
| 3 | `type` | yes | `BOOK` — the only supported value today |
| 4 | `title` | yes | Must not be blank |
| 5 | `authors` | no | **One string**, multiple authors joined with `"; "` (semicolon + space) |
| 6 | `release_year` | no | Integer `1450`–`2100`, or empty |
| 7 | `purchase_price` | no | Decimal `>= 0`, or empty. Plain number, no currency symbol |
| 8 | `created_at` | yes | ISO-8601 UTC; when the book was added |
| 9 | `cover_image_hash` | no | **Leave empty** — see below |
| 10 | `isbn` | no | ISBN-10 or ISBN-13, digits only, or empty |
| 11 | `format` | no | `PHYSICAL`, `EBOOK`, `AUDIOBOOK`, `PAPERBACK`, `HARDCOVER` |
| 12 | `total_pages` | no | Integer `> 0`, or empty. Empty is normal for audiobooks |
| 13 | `status` | no | `TO_READ`, `READING`, `FINISHED`, `DNF` |
| 14 | `finished_at` | no | ISO-8601 UTC. Only meaningful with `status=FINISHED` |
| 15 | `tracking_mode` | no | `PAGES` or `PERCENT` |
| 16 | `external_identifiers` | no | `PROVIDER:value`, multiple joined with `\|` (pipe) |

**`external_identifiers` providers:** `ISBN`, `OPEN_LIBRARY`, `GOOGLE_BOOKS`, `TMDB`, `TVDB`.
Example: `ISBN:9780441478125|OPEN_LIBRARY:OL27258W`.

**Leave `cover_image_hash` empty.** It names a content-addressed image file on the device. A hash
for a file that is not there renders as a missing cover on every affected book. Blank means no
cover yet, which the app's cover backfill can then fill in properly.

## File 2 — `reading_logs_export.csv`

10 columns, in this order:

| # | Column | Required | Notes |
| ---: | :--- | :--- | :--- |
| 1 | `csv_schema_version` | yes | Always `2` |
| 2 | `session_id` | yes | UUID string, unique per session |
| 3 | `media_id` | yes | Must match a `media_id` in the library file |
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

## Validation — what gets a row rejected

Rejected rows are skipped and reported; they do not fail the import. But a generated file full of
rejects is not useful, so:

- `title` blank
- `release_year` outside `1450`–`2100`
- `purchase_price` negative or not a finite number
- `total_pages` `<= 0` (use empty for unknown, never `0`)
- `timestamp_end` earlier than `timestamp_start`
- `duration_seconds` negative
- `start_unit` or `end_unit` negative or not finite
- any enum value not exactly matching the lists above
- a malformed `external_identifiers` segment
- **a session whose `media_id` matches no book** in the library file or already in the app

## Generating a realistic pair

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

## Worked example

`library_export.csv`:

```csv
csv_schema_version,media_id,type,title,authors,release_year,purchase_price,created_at,cover_image_hash,isbn,format,total_pages,status,finished_at,tracking_mode,external_identifiers
2,11111111-1111-4111-8111-111111111111,BOOK,The Left Hand of Darkness,Ursula K. Le Guin,1969,12.99,2026-01-05T09:15:00Z,,9780441478125,PAPERBACK,304,FINISHED,2026-02-01T21:40:00Z,PAGES,ISBN:9780441478125
2,33333333-3333-4333-8333-333333333333,BOOK,Good Omens,Terry Pratchett; Neil Gaiman,1990,14.00,2026-01-07T09:15:00Z,,9780060853983,HARDCOVER,491,READING,,PAGES,ISBN:9780060853983
```

`reading_logs_export.csv`:

```csv
csv_schema_version,session_id,media_id,timestamp_start,timestamp_end,duration_seconds,start_unit,end_unit,delta_pages,notes
2,aaaaaaaa-0001-4000-8000-000000000001,11111111-1111-4111-8111-111111111111,2026-01-20T19:05:00Z,2026-01-20T20:10:00Z,3900,0.0,48.0,48,Opening chapters
2,aaaaaaaa-0001-4000-8000-000000000002,11111111-1111-4111-8111-111111111111,2026-01-24T21:00:00Z,2026-01-24T21:45:00Z,2700,48.0,95.0,47,
2,aaaaaaaa-0002-4000-8000-000000000001,33333333-3333-4333-8333-333333333333,2026-02-14T08:30:00Z,2026-02-14T09:00:00Z,,120.0,151.0,31,"Commute reading, noisy train"
```

Note the second session has an empty `duration_seconds` (unknown, not zero) and an empty `notes`,
and the third quotes a note containing a comma.
